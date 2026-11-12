package de.markusfisch.android.shadereditor.editor;

import de.markusfisch.android.shadereditor.editor.Document.Change;
import de.markusfisch.android.shadereditor.editor.Document.Selection;

/**
 * What a keyboard does to a document through an InputConnection, and
 * what it must be told back. The Android connection forwards each call
 * to the method of the same name; one TextInput serves one connection
 * and holds the composing region and the keyboard's batch edits.
 * <p>
 * The keyboard is told the selection, as anchor and head, and the
 * composing region whenever either changed: after each of its calls
 * outside a batch edit, at the end of its outermost batch edit, and
 * after each change the app made while no batch edit was open. A text
 * change the keyboard did not make (a key the view handled, undo, a
 * completion) finishes composing; a selection the app made keeps it, so
 * the keyboard decides. Key events, editor actions and context menu
 * actions are the view's commands and arrive here as such changes.
 * <p>
 * Committed text without line breaks, composing text and the deletion
 * of one character join the document's run of typing; text with line
 * breaks, as a paste, and longer deletions, as an autocorrection makes,
 * are undo steps of their own. A read returns at most MAX_READ
 * characters on either side of the selection and of the selection
 * itself, so it costs what it returns.
 * <p>
 * Given a KeyboardSession, it writes every call with its answer, what
 * the keyboard was told, and every change and selection of the app.
 */
public final class TextInput {
	public static final int MAX_READ = 4096;

	public interface Listener {
		/**
		 * Pass on to InputMethodManager.updateSelection; -1 means there
		 * is no composing region.
		 */
		void onUpdateSelection(int selStart, int selEnd,
				int composingStart, int composingEnd);

		/** Pass on to InputMethodManager.updateExtractedText. */
		default void onUpdateExtractedText(int token, Window window) {
		}
	}

	/** Text of the document from offset, and the selection within it. */
	public record Window(int offset, int selectionStart, int selectionEnd,
			String text) {
	}

	private static final Object[] NO_ARGS = {};
	private static final KeyboardSession NONE = new KeyboardSession(null);

	private final Document document;
	private final Listener listener;
	private final KeyboardSession session;
	private final Document.Listener changes = new Document.Listener() {
		@Override
		public void onChange(Change change, Text before) {
			if (!applying) {
				composingStart = composingEnd = -1;
				recorded = new Selection(change.map(recorded.anchor()),
						change.map(recorded.head()));
				session.replaced(change.start(),
						change.start() + change.removed().length(),
						change.inserted());
			} else if (composingStart > -1) {
				setComposing(change.map(composingStart),
						change.map(composingEnd));
			}
		}

		@Override
		public void onUpdate() {
			if (!applying) {
				noteSelection();
				if (batches == 0) {
					report();
				}
			}
		}
	};
	private int composingStart = -1;
	private int composingEnd = -1;
	private int batches;
	// Whether the keyboard is changing the document now.
	private boolean applying;
	private boolean closed;
	private boolean monitored;
	private int token;
	// What the keyboard was told last.
	private Selection toldSelection;
	private int toldComposingStart = -1;
	private int toldComposingEnd = -1;
	private int toldRevision;
	// The selection as the session has it.
	private Selection recorded;
	// The call that is changing the document, and its arguments.
	private String call;
	private Object[] args;

	/**
	 * Start a connection to document; the keyboard knows its selection
	 * from EditorInfo and no composing region.
	 */
	public TextInput(Document document, Listener listener) {
		this(document, listener, null);
	}

	/** Start a connection as above, and write it to session. */
	public TextInput(Document document, Listener listener,
			KeyboardSession session) {
		this.document = document;
		this.listener = listener;
		this.session = session != null ? session : NONE;
		toldSelection = recorded = document.selection();
		toldRevision = document.revision();
		document.addListener(changes);
		this.session.start(document.text(), recorded);
	}

	/** Return where the composing region starts, -1 if there is none. */
	public int composingStart() {
		return composingStart;
	}

	/** Return where the composing region ends, -1 if there is none. */
	public int composingEnd() {
		return composingEnd;
	}

	/**
	 * Replace the composing text, or else the selection, by text and
	 * finish composing. A position above 0 puts the caret position - 1
	 * characters behind the new text, any other position that many
	 * characters before its start.
	 */
	public boolean commitText(CharSequence text, int position) {
		if (!begin("commitText", text, position)) {
			return false;
		}
		Text committed = Text.of(text != null ? text : "");
		replace(committed, position, composingStart > -1 ||
				committed.lineCount() == 1 || committed.length() == 1);
		composingStart = composingEnd = -1;
		return done();
	}

	/** Replace as commitText does, and compose the new text. */
	public boolean setComposingText(CharSequence text, int position) {
		if (!begin("setComposingText", text, position)) {
			return false;
		}
		Text composed = Text.of(text != null ? text : "");
		int start = replace(composed, position, true);
		setComposing(start, start + composed.length());
		return done();
	}

	/**
	 * Compose the text from start to end without changing it. The ends
	 * may come in either order and are clamped to the text.
	 */
	public boolean setComposingRegion(int start, int end) {
		if (!begin("setComposingRegion", start, end)) {
			return false;
		}
		int length = document.text().length();
		setComposing(clamp(Math.min(start, end), length),
				clamp(Math.max(start, end), length));
		return done();
	}

	/** Keep the composing text and forget the region. */
	public boolean finishComposingText() {
		if (!begin("finishComposingText")) {
			return false;
		}
		composingStart = composingEnd = -1;
		return done();
	}

	/**
	 * Delete before characters before and after characters behind the
	 * selection and the composing region, never half a surrogate pair.
	 */
	public boolean deleteSurroundingText(int before, int after) {
		return delete("deleteSurroundingText", before, after, false);
	}

	/** Delete as deleteSurroundingText does, counting code points. */
	public boolean deleteSurroundingTextInCodePoints(int before, int after) {
		return delete("deleteSurroundingTextInCodePoints", before, after,
				true);
	}

	/**
	 * Select from start, the anchor, to end. A range outside the text is
	 * ignored; the keyboard will be told the real one.
	 */
	public boolean setSelection(int start, int end) {
		if (!begin("setSelection", start, end)) {
			return false;
		}
		int length = document.text().length();
		if (start >= 0 && end >= 0 && start <= length && end <= length) {
			document.select(start, end);
		}
		return done();
	}

	/** Return up to n characters before the selection. */
	public String getTextBeforeCursor(int n) {
		if (closed) {
			return null;
		}
		int start = document.selection().start();
		String before = document.text().substring(
				start - clamp(n, Math.min(MAX_READ, start)), start);
		return read(before, "getTextBeforeCursor", n);
	}

	/** Return up to n characters behind the selection. */
	public String getTextAfterCursor(int n) {
		if (closed) {
			return null;
		}
		Text text = document.text();
		int end = document.selection().end();
		String after = text.substring(end,
				end + clamp(n, Math.min(MAX_READ, text.length() - end)));
		return read(after, "getTextAfterCursor", n);
	}

	/** Return the selected text, null if the selection is empty. */
	public String getSelectedText() {
		if (closed) {
			return null;
		}
		Selection selection = document.selection();
		String selected = selection.isEmpty()
				? null
				: document.text().substring(selection.start(),
						Math.min(selection.end(),
								selection.start() + MAX_READ));
		return read(selected, "getSelectedText");
	}

	/**
	 * Return the selection with up to before characters before it and
	 * up to after behind it. A selection longer than MAX_READ ends the
	 * window.
	 */
	public Window getSurroundingText(int before, int after) {
		if (closed) {
			return null;
		}
		return read(window(before, after), "getSurroundingText", before,
				after);
	}

	/**
	 * Return a window of MAX_READ characters on either side of the
	 * selection; its offset is ExtractedText.startOffset. If monitor is
	 * set, the keyboard is sent a new window with every update from now
	 * on, under token.
	 */
	public Window getExtractedText(int token, boolean monitor) {
		if (closed) {
			return null;
		}
		if (monitor) {
			monitored = true;
			this.token = token;
		}
		return read(window(MAX_READ, MAX_READ), "getExtractedText", token,
				monitor);
	}

	/**
	 * Open a batch edit: until the outermost one ends, the keyboard is
	 * not told of changes, and they are one undo step unless they are
	 * typing.
	 */
	public boolean beginBatchEdit() {
		if (closed) {
			return false;
		}
		noteSelection();
		++batches;
		document.beginBatch();
		session.call("beginBatchEdit", NO_ARGS, true);
		return true;
	}

	/** Close a batch edit; return whether one is still open. */
	public boolean endBatchEdit() {
		if (closed) {
			return false;
		}
		noteSelection();
		if (batches == 0) {
			session.call("endBatchEdit", NO_ARGS, false);
			return false;
		}
		document.endBatch();
		boolean open = --batches > 0;
		session.call("endBatchEdit", NO_ARGS, open);
		if (!open) {
			report();
		}
		return open;
	}

	/**
	 * End open batch edits and finish composing. Every later call is
	 * ignored and answered with false or null.
	 */
	public void closeConnection() {
		if (closed) {
			return;
		}
		noteSelection();
		closed = true;
		document.removeListener(changes);
		composingStart = composingEnd = -1;
		for (; batches > 0; --batches) {
			document.endBatch();
		}
		session.end(document.text(), document.selection());
	}

	private boolean begin(String name, Object... args) {
		if (closed) {
			return false;
		}
		noteSelection();
		call = name;
		this.args = args;
		applying = true;
		document.beginBatch();
		return true;
	}

	private boolean done() {
		document.endBatch();
		applying = false;
		recorded = document.selection();
		session.call(call, args, true);
		if (batches == 0) {
			report();
		}
		return true;
	}

	// Replaces the composing region or else the selection by text, places
	// the caret and returns where the text starts.
	private int replace(Text text, int position, boolean typed) {
		Selection selection = document.selection();
		boolean composing = composingStart > -1;
		int from = composing ? composingStart : selection.start();
		int to = composing ? composingEnd : selection.end();
		if (typed) {
			document.type(from, to, text);
		} else {
			document.replace(from, to, text);
		}
		long caret = position > 0
				? (long) from + text.length() + position - 1
				: (long) from + position;
		int at = (int) Math.max(0, Math.min(caret, document.text().length()));
		document.select(at, at);
		return from;
	}

	private void setComposing(int start, int end) {
		boolean empty = start >= end;
		composingStart = empty ? -1 : start;
		composingEnd = empty ? -1 : end;
	}

	private boolean delete(String name, int before, int after,
			boolean codePoints) {
		if (!begin(name, before, after)) {
			return false;
		}
		Selection selection = document.selection();
		int start = selection.start();
		int end = selection.end();
		if (composingStart > -1) {
			start = Math.min(start, composingStart);
			end = Math.max(end, composingEnd);
		}
		erase(end, ahead(end, after, codePoints));
		erase(back(start, before, codePoints), start);
		return done();
	}

	// Deletes the range [from, to); one character, as Backspace deletes
	// it, is typing.
	private void erase(int from, int to) {
		int length = to - from;
		if (length == 1 || (length == 2 && splits(from + 1))) {
			document.type(from, to, Text.EMPTY);
		} else if (length > 0) {
			document.replace(from, to, Text.EMPTY);
		}
	}

	// Returns the offset count characters, or code points, before offset.
	private int back(int offset, int count, boolean codePoints) {
		int n = clamp(count, offset);
		if (codePoints && n > 0) {
			int max = (int) Math.min(2L * n, offset);
			String s = document.text().substring(offset - max, offset);
			n = max - s.offsetByCodePoints(max,
					-Math.min(n, s.codePointCount(0, max)));
		}
		int from = offset - n;
		return splits(from) ? from - 1 : from;
	}

	// Returns the offset count characters, or code points, behind offset.
	private int ahead(int offset, int count, boolean codePoints) {
		int length = document.text().length();
		int n = clamp(count, length - offset);
		if (codePoints && n > 0) {
			int max = (int) Math.min(2L * n, length - offset);
			String s = document.text().substring(offset, offset + max);
			n = s.offsetByCodePoints(0, Math.min(n, s.codePointCount(0, max)));
		}
		int to = offset + n;
		return splits(to) ? to + 1 : to;
	}

	// Returns whether offset is between the halves of a surrogate pair.
	private boolean splits(int offset) {
		Text text = document.text();
		if (offset <= 0 || offset >= text.length()) {
			return false;
		}
		String s = text.substring(offset - 1, offset + 1);
		return Character.isSurrogatePair(s.charAt(0), s.charAt(1));
	}

	private Window window(int before, int after) {
		Text text = document.text();
		Selection selection = document.selection();
		int start = selection.start();
		int end = Math.min(selection.end(), start + MAX_READ);
		int from = start - clamp(before, Math.min(MAX_READ, start));
		int to = end < selection.end()
				? end
				: end + clamp(after, Math.min(MAX_READ, text.length() - end));
		return new Window(from, start - from, end - from,
				text.substring(from, to));
	}

	// Tells the keyboard what changed since it was told last.
	private void report() {
		Selection selection = document.selection();
		boolean moved = !selection.equals(toldSelection) ||
				composingStart != toldComposingStart ||
				composingEnd != toldComposingEnd;
		if (monitored && (moved || document.revision() != toldRevision)) {
			Window window = window(MAX_READ, MAX_READ);
			session.updateExtractedText(token, window);
			listener.onUpdateExtractedText(token, window);
		}
		toldRevision = document.revision();
		if (moved) {
			toldSelection = selection;
			toldComposingStart = composingStart;
			toldComposingEnd = composingEnd;
			session.updateSelection(selection.anchor(), selection.head(),
					composingStart, composingEnd);
			listener.onUpdateSelection(selection.anchor(), selection.head(),
					composingStart, composingEnd);
		}
	}

	private <T> T read(T answer, String name, Object... args) {
		noteSelection();
		session.call(name, args, answer);
		return answer;
	}

	// Writes a selection the app made since the session last had one.
	private void noteSelection() {
		Selection selection = document.selection();
		if (!selection.equals(recorded)) {
			recorded = selection;
			session.selected(selection);
		}
	}

	private static int clamp(int n, int max) {
		return Math.max(0, Math.min(n, max));
	}
}
