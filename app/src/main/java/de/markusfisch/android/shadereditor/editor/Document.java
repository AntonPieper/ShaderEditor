package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;

/**
 * One file being edited: its text, the selection and the changes made
 * to them. Every edit is a replace of a range by a text, which Text.of
 * has cleaned. Listeners hear of each change as it happens and once
 * more when an edit, or a batch of them, is complete. Positions held
 * elsewhere follow the edits through Change.map. A document is used on
 * one thread; its texts may be read on any.
 */
public final class Document {
	/** A replace: inserted took the place of removed at start. */
	public record Change(int start, Text removed, Text inserted) {
		/**
		 * Return where offset of the text before this change is in the
		 * text after it. An offset before the change stays, one after it
		 * moves with the text, and one inside or at either end of the
		 * removed range ends up behind the inserted text, so a caret
		 * stays behind what was typed.
		 */
		public int map(int offset) {
			return offset < start
					? offset
					: Math.max(offset - removed.length(), start) +
							inserted.length();
		}
	}

	/** The selection from anchor to head; when empty, the caret. */
	public record Selection(int anchor, int head) {
		public int start() {
			return Math.min(anchor, head);
		}

		public int end() {
			return Math.max(anchor, head);
		}

		public boolean isEmpty() {
			return anchor == head;
		}
	}

	public interface Listener {
		/**
		 * Called after every change, also inside a batch; before is the
		 * text the change was made to. It must not edit the document.
		 */
		default void onChange(Change change, Text before) {
		}

		/**
		 * Called once the text or the selection changed, after an edit,
		 * a selection or the end of the outermost batch.
		 */
		default void onUpdate() {
		}
	}

	private Listener[] listeners = new Listener[0];
	private Text text;
	private int revision;
	private Selection selection = new Selection(0, 0);
	private int batches;
	private boolean updated;

	public Document(Text text) {
		this.text = text;
	}

	/** Return the current text. */
	public Text text() {
		return text;
	}

	/** Return the number of changes made so far. */
	public int revision() {
		return revision;
	}

	public Selection selection() {
		return selection;
	}

	public void addListener(Listener listener) {
		listeners = Arrays.copyOf(listeners, listeners.length + 1);
		listeners[listeners.length - 1] = listener;
	}

	public void removeListener(Listener listener) {
		for (int i = listeners.length; i-- > 0; ) {
			if (listeners[i] == listener) {
				Listener[] rest = new Listener[listeners.length - 1];
				System.arraycopy(listeners, 0, rest, 0, i);
				System.arraycopy(listeners, i + 1, rest, i, rest.length - i);
				listeners = rest;
				return;
			}
		}
	}

	/** Select from anchor to head. */
	public void select(int anchor, int head) {
		int length = text.length();
		if (anchor < 0 || anchor > length || head < 0 || head > length) {
			throw new IndexOutOfBoundsException("Selection " + anchor +
					" to " + head + " of " + length);
		}
		if (anchor != selection.anchor() || head != selection.head()) {
			beginBatch();
			selection = new Selection(anchor, head);
			updated = true;
			endBatch();
		}
	}

	/**
	 * Replace the range [from, to) by text. The selection follows the
	 * change as Change.map moves it.
	 */
	public void replace(int from, int to, Text text) {
		Change change = new Change(from, this.text.slice(from, to), text);
		if (from == to && text.length() == 0) {
			return;
		}
		beginBatch();
		apply(change);
		endBatch();
	}

	/**
	 * Start a batch: until the matching endBatch, listeners hear of
	 * each change but are told only once that the document updated.
	 * Batches nest.
	 */
	public void beginBatch() {
		++batches;
	}

	public void endBatch() {
		if (batches == 0) {
			throw new IllegalStateException("No batch to end");
		}
		if (--batches == 0 && updated) {
			updated = false;
			for (Listener listener : listeners) {
				listener.onUpdate();
			}
		}
	}

	private void apply(Change change) {
		Text before = text;
		text = before.replace(change.start(),
				change.start() + change.removed().length(), change.inserted());
		++revision;
		selection = new Selection(change.map(selection.anchor()),
				change.map(selection.head()));
		updated = true;
		for (Listener listener : listeners) {
			listener.onChange(change, before);
		}
	}
}
