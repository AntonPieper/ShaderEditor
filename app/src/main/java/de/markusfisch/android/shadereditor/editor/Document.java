package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). Not production code.
 * <p>
 * The text of one file while it is edited: the current {@link Text}, the
 * selection, the composing region of the input method, and the history
 * for undo and redo. Used from one thread only. Every change is one
 * replace; listeners hear about each one after it happened.
 */
public final class Document {
	/** One replace: removed was at start and inserted took its place. */
	public static final class Change {
		public final int start;
		public final Text removed;
		public final Text inserted;

		Change(int start, Text removed, Text inserted) {
			this.start = start;
			this.removed = removed;
			this.inserted = inserted;
		}

		/** Where offset is after this change. */
		public int map(int offset) {
			if (offset <= start) {
				return offset;
			}
			int end = start + removed.length();
			return offset >= end
					? offset + inserted.length() - removed.length()
					: start + inserted.length();
		}
	}

	public interface Listener {
		void onChange(Document document, Change change, Text before);
	}

	private static final class Step {
		private final List<Change> changes = new ArrayList<>();
		private int selectionBefore;
		private int selectionAfter;
		private long time;
		private boolean typing;
	}

	private static final long TYPING_GROUP_MS = 1000;

	private final List<Listener> listeners = new ArrayList<>();
	private final ArrayList<Step> undo = new ArrayList<>();
	private final ArrayList<Step> redo = new ArrayList<>();
	private Text text;
	private int revision;
	private int selectionStart;
	private int selectionEnd;
	private int composingStart = -1;
	private int composingEnd = -1;
	private Step open;
	private int batch;
	private boolean replaying;

	public Document(Text text) {
		this.text = text;
	}

	public Text text() {
		return text;
	}

	public int revision() {
		return revision;
	}

	public int selectionStart() {
		return selectionStart;
	}

	public int selectionEnd() {
		return selectionEnd;
	}

	public int composingStart() {
		return composingStart;
	}

	public int composingEnd() {
		return composingEnd;
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void setSelection(int start, int end) {
		int length = text.length();
		selectionStart = Math.max(0, Math.min(start, length));
		selectionEnd = Math.max(0, Math.min(end, length));
		// Moving the caret ends a run of typing for undo.
		open = null;
	}

	public void setComposing(int start, int end) {
		int length = text.length();
		if (start < 0 || end < 0 || start == end) {
			composingStart = composingEnd = -1;
			return;
		}
		composingStart = Math.max(0, Math.min(Math.min(start, end), length));
		composingEnd = Math.max(0, Math.min(Math.max(start, end), length));
	}

	/** Several replaces between begin and end are one undo step. */
	public void beginBatch() {
		++batch;
	}

	public void endBatch() {
		if (batch > 0 && --batch == 0 && open != null && !open.typing) {
			open = null;
		}
	}

	/**
	 * Replaces [from, to) by insert and puts the caret behind it.
	 *
	 * @param typing true for a single typed character, so that a run of
	 *               them becomes one undo step
	 */
	public void replace(int from, int to, Text insert, boolean typing) {
		Change change = apply(from, to, insert);
		if (replaying) {
			return;
		}
		long now = System.currentTimeMillis();
		Step step = open;
		boolean joins = step != null && (batch > 0 || (typing && step.typing &&
				now - step.time < TYPING_GROUP_MS &&
				step.selectionAfter == from));
		if (!joins) {
			step = new Step();
			step.selectionBefore = selectionStartBefore;
			step.typing = typing;
			undo.add(step);
			if (undo.size() > 1000) {
				undo.remove(0);
			}
			open = step;
		}
		step.changes.add(change);
		step.time = now;
		step.selectionAfter = from + insert.length();
		redo.clear();
	}

	private int selectionStartBefore;

	private Change apply(int from, int to, Text insert) {
		Text before = text;
		selectionStartBefore = selectionStart;
		Change change = new Change(from, before.slice(from, to), insert);
		text = before.replace(from, to, insert);
		++revision;
		int caret = from + insert.length();
		selectionStart = selectionEnd = caret;
		if (composingStart >= 0) {
			composingStart = change.map(composingStart);
			composingEnd = change.map(composingEnd);
			if (composingStart >= composingEnd) {
				composingStart = composingEnd = -1;
			}
		}
		for (int i = 0, n = listeners.size(); i < n; ++i) {
			listeners.get(i).onChange(this, change, before);
		}
		return change;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public void undo() {
		if (undo.isEmpty()) {
			return;
		}
		Step step = undo.remove(undo.size() - 1);
		open = null;
		replaying = true;
		for (int i = step.changes.size() - 1; i >= 0; --i) {
			Change c = step.changes.get(i);
			apply(c.start, c.start + c.inserted.length(), c.removed);
		}
		replaying = false;
		setSelection(step.selectionBefore, step.selectionBefore);
		composingStart = composingEnd = -1;
		redo.add(step);
	}

	public void redo() {
		if (redo.isEmpty()) {
			return;
		}
		Step step = redo.remove(redo.size() - 1);
		open = null;
		replaying = true;
		for (Change c : step.changes) {
			apply(c.start, c.start + c.removed.length(), c.inserted);
		}
		replaying = false;
		setSelection(step.selectionAfter, step.selectionAfter);
		composingStart = composingEnd = -1;
		undo.add(step);
	}
}
