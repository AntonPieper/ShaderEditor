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
 * after each change the app made while no batch edit was open. It is
 * told one update at a time: a call it makes while it is told, from a
 * listener, is told after, so the last update holds the selection. A
 * change is the keyboard's if one of its calls made it. Any other text
 * change (a key the view handled, undo, a completion, an edit from a
 * listener) finishes composing; a selection the app made keeps it, so
 * the keyboard decides. Key events, editor actions and context menu
 * actions are the view's commands and arrive here as such changes.
 * <p>
 * A commit or composing text the document refuses, as one that would
 * make the text too large, changes nothing: text, selection and
 * composing region stay as they were, and the keyboard is told them
 * again, so it does not take its text as entered. The document tells
 * its listeners why.
 * <p>
 * Committed text without line breaks, composing text and the deletion
 * of one character join the document's run of typing; text with line
 * breaks and longer deletions, as an autocorrection makes, are undo
 * steps of their own. A keyboard commits text from its own clipboard
 * as it commits glide typing, so a paste from there joins the run if it
 * is one line. Text the view pastes itself, from its context menu or a
 * key, goes to Document.replace, which ends the run.
 * <p>
 * A read returns at most MAX_READ characters on either side of the
 * selection and of the selection itself, so it costs what it returns,
 * and it never tells a selection that is not there. A selection longer
 * than MAX_READ makes getSelectedText and getSurroundingText decline,
 * with null; getExtractedText then cuts its text after MAX_READ
 * characters of the selection and keeps the real anchor and head, so
 * the keyboard sees that the text is cut and not the selection.
 * <p>
 * Given a KeyboardSession, it writes when it opens and closes, every
 * call with its answer, what the keyboard was told, and every change
 * and selection of the app.
 */
public final class TextInput {
	public static final int MAX_READ = 4096;

	// A delete counting code points reads the text in pieces this long.
	private static final int PIECE = 1024;

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

	/**
	 * Text of the document from offset, and the ends of the selection
	 * counted from offset: sorted for getSurroundingText, anchor and head
	 * for getExtractedText, where the head may lie beyond a cut text.
	 */
	public record Window(int offset, int selectionStart, int selectionEnd,
			String text) {
	}

	private static final Object[] NO_ARGS = {};

	private final Document document;
	private final Listener listener;
	private final KeyboardSession session;
	// The number of this connection in the session.
	private final int id;
	private final Document.Listener changes = new Document.Listener() {
		@Override
		public void onChange(Change change, Text before) {
			if (!own) {
				composingStart = composingEnd = -1;
				recorded = new Selection(change.map(recorded.anchor()),
						change.map(recorded.head()));
				session.changed(id, change.start(),
						change.start() + change.removed().length(),
						change.inserted());
			} else if (composingStart > -1) {
				setComposing(change.map(composingStart),
						change.map(composingEnd));
			}
		}

		@Override
		public void onUpdate() {
			noteSelection();
			if (batches == 0) {
				report();
			}
		}
	};
	private int composingStart = -1;
	private int composingEnd = -1;
	private int batches;
	// Whether the change being made is the keyboard's.
	private boolean own;
	private boolean reporting;
	// Whether the keyboard is to be told the selection and the
	// composing region although they did not change.
	private boolean refused;
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
		this.session = session != null ? session : KeyboardSession.NONE;
		toldSelection = recorded = document.selection();
		toldRevision = document.revision();
		document.addListener(changes);
		id = this.session.open(document, recorded);
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
		if (!begin()) {
			return false;
		}
		Text committed = Text.of(text != null ? text : "");
		if (replace(committed, position, composingStart > -1 ||
				committed.lineCount() == 1 || committed.length() == 1) > -1) {
			composingStart = composingEnd = -1;
		}
		return done("commitText", text, position);
	}

	/** Replace as commitText does, and compose the new text. */
	public boolean setComposingText(CharSequence text, int position) {
		if (!begin()) {
			return false;
		}
		Text composed = Text.of(text != null ? text : "");
		int start = replace(composed, position, true);
		if (start > -1) {
			setComposing(start, start + composed.length());
		}
		return done("setComposingText", text, position);
	}

	/**
	 * Compose the text from start to end without changing it. The ends
	 * may come in either order and are clamped to the text.
	 */
	public boolean setComposingRegion(int start, int end) {
		if (!begin()) {
			return false;
		}
		int length = document.text().length();
		setComposing(clamp(Math.min(start, end), length),
				clamp(Math.max(start, end), length));
		return done("setComposingRegion", start, end);
	}

	/**
	 * Keep the composing text and forget the region. On a closed
	 * connection, there is nothing to finish.
	 */
	public boolean finishComposingText() {
		if (begin()) {
			composingStart = composingEnd = -1;
			done("finishComposingText");
		}
		return true;
	}

	/**
	 * Delete before characters before and after characters behind the
	 * selection and the composing region, never half a surrogate pair:
	 * a range that ends inside a pair takes all of it, and if one would
	 * start inside a pair, nothing is deleted.
	 */
	public boolean deleteSurroundingText(int before, int after) {
		return delete("deleteSurroundingText", before, after, false);
	}

	/**
	 * Delete as deleteSurroundingText does, counting code points. As on
	 * Android, nothing is deleted if either range holds half a pair.
	 */
	public boolean deleteSurroundingTextInCodePoints(int before, int after) {
		return delete("deleteSurroundingTextInCodePoints", before, after,
				true);
	}

	/**
	 * Select from start, the anchor, to end. A range outside the text is
	 * ignored; the keyboard will be told the real one.
	 */
	public boolean setSelection(int start, int end) {
		if (!begin()) {
			return false;
		}
		int length = document.text().length();
		if (start >= 0 && end >= 0 && start <= length && end <= length) {
			document.select(start, end);
		}
		return done("setSelection", start, end);
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

	/**
	 * Return the selected text, null if the selection is empty or longer
	 * than MAX_READ.
	 */
	public String getSelectedText() {
		if (closed) {
			return null;
		}
		Selection selection = document.selection();
		String selected = selection.isEmpty() || !fits(selection)
				? null
				: document.text().substring(selection.start(),
						selection.end());
		return read(selected, "getSelectedText");
	}

	/**
	 * Return the selection with up to before characters before it and
	 * up to after behind it, null if it is longer than MAX_READ.
	 */
	public Window getSurroundingText(int before, int after) {
		if (closed) {
			return null;
		}
		return read(fits(document.selection())
						? window(before, after, true)
						: null,
				"getSurroundingText", before, after);
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
		return read(window(MAX_READ, MAX_READ, false), "getExtractedText",
				token, monitor);
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
		session.call(id, "beginBatchEdit", NO_ARGS, true);
		return true;
	}

	/** Close a batch edit; return whether one is still open. */
	public boolean endBatchEdit() {
		if (closed) {
			return false;
		}
		noteSelection();
		if (batches == 0) {
			session.call(id, "endBatchEdit", NO_ARGS, false);
			return false;
		}
		document.endBatch();
		boolean open = --batches > 0;
		session.call(id, "endBatchEdit", NO_ARGS, open);
		if (!open) {
			report();
		}
		return open;
	}

	/**
	 * End open batch edits and finish composing. Every later call is
	 * ignored and answered with false or null, finishComposingText with
	 * true.
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
		session.close(id, document.text(), document.selection());
	}

	private boolean begin() {
		if (closed) {
			return false;
		}
		noteSelection();
		document.beginBatch();
		return true;
	}

	// Writes the call, which listeners may answer with changes of their
	// own when the document updates, and tells the keyboard.
	private boolean done(String name, Object... args) {
		recorded = document.selection();
		session.call(id, name, args, true);
		document.endBatch();
		if (batches == 0) {
			report();
		}
		return true;
	}

	// Makes a change of the keyboard's; returns whether the document
	// took it.
	private boolean edit(int from, int to, Text text, boolean typed) {
		own = true;
		try {
			boolean took = typed
					? document.type(from, to, text)
					: document.replace(from, to, text);
			refused |= !took;
			return took;
		} finally {
			own = false;
		}
	}

	// Replaces the composing region or else the selection by text, places
	// the caret and returns where the text starts, or -1 when the
	// document refused it and nothing changed.
	private int replace(Text text, int position, boolean typed) {
		Selection selection = document.selection();
		boolean composing = composingStart > -1;
		int from = composing ? composingStart : selection.start();
		int to = composing ? composingEnd : selection.end();
		if (!edit(from, to, text, typed)) {
			return -1;
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
		if (!begin()) {
			return false;
		}
		Selection selection = document.selection();
		int start = selection.start();
		int end = selection.end();
		if (composingStart > -1) {
			start = Math.min(start, composingStart);
			end = Math.max(end, composingEnd);
		}
		int from = codePoints
				? backInCodePoints(start, before)
				: back(start, before);
		int to = codePoints
				? aheadInCodePoints(end, after)
				: ahead(end, after);
		if (from > -1 && to > -1) {
			erase(end, to);
			erase(from, start);
		}
		return done(name, before, after);
	}

	// Deletes the range [from, to); one character, as Backspace deletes
	// it, is typing.
	private void erase(int from, int to) {
		int length = to - from;
		if (length > 0) {
			edit(from, to, Text.EMPTY,
					length == 1 || (length == 2 && splits(from + 1)));
		}
	}

	// Returns the offset count characters before offset, taking a whole
	// pair at the far end, or -1 if offset splits one.
	private int back(int offset, int count) {
		if (count <= 0) {
			return offset;
		}
		if (splits(offset)) {
			return -1;
		}
		int from = offset - Math.min(count, offset);
		return splits(from) ? from - 1 : from;
	}

	// Returns the offset count characters behind offset, taking a whole
	// pair at the far end, or -1 if offset splits one.
	private int ahead(int offset, int count) {
		if (count <= 0) {
			return offset;
		}
		if (splits(offset)) {
			return -1;
		}
		int to = offset + Math.min(count, document.text().length() - offset);
		return splits(to) ? to + 1 : to;
	}

	// Returns the offset count code points before offset, or -1 if the
	// range holds half a surrogate pair, as Android's findIndexBackward.
	private int backInCodePoints(int offset, int count) {
		Text text = document.text();
		boolean low = false;
		int i = offset;
		while (count > 0) {
			if (i == 0) {
				return low ? -1 : 0;
			}
			String piece = text.substring(Math.max(0, i - PIECE), i);
			for (int j = piece.length(); j-- > 0 && count > 0; --i) {
				char c = piece.charAt(j);
				if (low) {
					if (!Character.isHighSurrogate(c)) {
						return -1;
					}
					low = false;
					--count;
				} else if (Character.isHighSurrogate(c)) {
					return -1;
				} else if (Character.isLowSurrogate(c)) {
					low = true;
				} else {
					--count;
				}
			}
		}
		return i;
	}

	// Returns the offset count code points behind offset, or -1 if the
	// range holds half a surrogate pair, as Android's findIndexForward.
	private int aheadInCodePoints(int offset, int count) {
		Text text = document.text();
		int length = text.length();
		boolean high = false;
		int i = offset;
		while (count > 0) {
			if (i == length) {
				return high ? -1 : length;
			}
			String piece = text.substring(i, i + Math.min(PIECE, length - i));
			for (int j = 0; j < piece.length() && count > 0; ++j, ++i) {
				char c = piece.charAt(j);
				if (high) {
					if (!Character.isLowSurrogate(c)) {
						return -1;
					}
					high = false;
					--count;
				} else if (Character.isLowSurrogate(c)) {
					return -1;
				} else if (Character.isHighSurrogate(c)) {
					high = true;
				} else {
					--count;
				}
			}
		}
		return i;
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

	// Returns the selection, cut after MAX_READ characters, with up to
	// before characters before it and after behind it if it is not cut;
	// its ends sorted or as anchor and head.
	private Window window(int before, int after, boolean sorted) {
		Text text = document.text();
		Selection selection = document.selection();
		int start = selection.start();
		int end = Math.min(selection.end(), start + MAX_READ);
		int from = start - clamp(before, Math.min(MAX_READ, start));
		int to = end < selection.end()
				? end
				: end + clamp(after, Math.min(MAX_READ, text.length() - end));
		String s = text.substring(from, to);
		return sorted
				? new Window(from, start - from, selection.end() - from, s)
				: new Window(from, selection.anchor() - from,
						selection.head() - from, s);
	}

	private static boolean fits(Selection selection) {
		return selection.end() - selection.start() <= MAX_READ;
	}

	// Tells the keyboard what changed since it was told last, until
	// nothing did; a report from a listener is left to the loop.
	private void report() {
		if (reporting) {
			return;
		}
		reporting = true;
		try {
			boolean told;
			do {
				told = tell();
			} while (told && !closed);
		} finally {
			reporting = false;
		}
	}

	// Tells the keyboard one update, stored and written before any
	// listener hears of it; returns whether there was one.
	private boolean tell() {
		Selection selection = document.selection();
		int start = composingStart;
		int end = composingEnd;
		boolean moved = refused || !selection.equals(toldSelection) ||
				start != toldComposingStart || end != toldComposingEnd;
		refused = false;
		boolean extract = monitored &&
				(moved || document.revision() != toldRevision);
		toldRevision = document.revision();
		toldSelection = selection;
		toldComposingStart = start;
		toldComposingEnd = end;
		Window window = extract ? window(MAX_READ, MAX_READ, false) : null;
		if (extract) {
			session.updateExtractedText(id, token, window);
		}
		if (moved) {
			session.updateSelection(id, selection.anchor(), selection.head(),
					start, end);
		}
		if (extract) {
			listener.onUpdateExtractedText(token, window);
		}
		if (moved) {
			listener.onUpdateSelection(selection.anchor(), selection.head(),
					start, end);
		}
		return moved || extract;
	}

	private <T> T read(T answer, String name, Object... args) {
		noteSelection();
		session.call(id, name, args, answer);
		return answer;
	}

	// Writes a selection the app made since the session last had one.
	private void noteSelection() {
		Selection selection = document.selection();
		if (!selection.equals(recorded)) {
			recorded = selection;
			session.selected(id, selection);
		}
	}

	private static int clamp(int n, int max) {
		return Math.max(0, Math.min(n, max));
	}
}
