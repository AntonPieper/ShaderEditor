package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One file being edited: its text, the selection and the changes made
 * to them. Every edit is a replace of a range by a text, which Text.of
 * has cleaned. Listeners hear of each change as it happens and once
 * more when an edit, or a batch of them, is complete. Positions held
 * elsewhere follow the edits through Change.map. Undo and redo work in
 * steps, and a run of typing is one step. A document is used on one
 * thread; its texts may be read on any.
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

	interface Clock {
		long millis();
	}

	// A run of typing ends after a pause this long.
	private static final long TYPING_PAUSE = 1000;
	// The history keeps at most this many steps, and drops the oldest
	// while their texts add up to more characters than MAX_CHARS. The
	// newest step is always kept.
	private static final int MAX_STEPS = 1000;
	private static final int MAX_CHARS = 4_000_000;
	// The saved text is no longer reachable by undo or redo.
	private static final Step UNREACHABLE = new Step(null);

	private final ArrayDeque<Step> undo = new ArrayDeque<>();
	private final ArrayDeque<Step> redo = new ArrayDeque<>();
	private final Clock clock;
	private Listener[] listeners = new Listener[0];
	private Text text;
	private int revision;
	private Selection selection = new Selection(0, 0);
	private int batches;
	private boolean updated;
	// The changes of this batch, which become a step of the history when
	// it ends.
	private Step batchStep;
	// The step batchStep continues if it is typing.
	private Step continued;
	// The step that typing may continue.
	private Step typing;
	// Whether the selection moved since the last change of this batch.
	private boolean moved;
	// The step the saved text is after, null for the start of history.
	private Step saved;
	private int historyChars;

	public Document(Text text) {
		this(text, () -> System.nanoTime() / 1_000_000);
	}

	Document(Text text, Clock clock) {
		this.text = text;
		this.clock = clock;
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

	/** Select from anchor to head; another place ends a run of typing. */
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
			typing = null;
			moved = true;
			endBatch();
		}
	}

	/**
	 * Replace the range [from, to) by text, as an undo step of its own
	 * or as part of the step of the current batch. The selection
	 * follows the change as Change.map moves it.
	 */
	public void replace(int from, int to, Text text) {
		edit(from, to, text, false);
	}

	/**
	 * Replace as replace does, as typing: a typed change joins the
	 * last step if that was typed less than a second ago and the
	 * change touches the text it changed. Selecting another place,
	 * any other edit, undo, redo and markSaved end the run.
	 */
	public void type(int from, int to, Text text) {
		edit(from, to, text, true);
	}

	/**
	 * Start a batch: until the matching endBatch, the changes are one
	 * undo step, which continues the run of typing only if they are all
	 * typing, and listeners hear of each change but are told only once
	 * that the document updated. Batches nest.
	 */
	public void beginBatch() {
		++batches;
	}

	public void endBatch() {
		if (batches == 0) {
			throw new IllegalStateException("No batch to end");
		}
		if (--batches > 0) {
			return;
		}
		endStep();
		if (updated) {
			updated = false;
			for (Listener listener : listeners) {
				listener.onUpdate();
			}
		}
	}

	public boolean canUndo() {
		return !undo.isEmpty() || batchStep != null;
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	/** Undo the last step and select what was selected before it. */
	public void undo() {
		endStep();
		Step step = undo.pollLast();
		if (step == null) {
			return;
		}
		beginBatch();
		typing = null;
		redo.addLast(step);
		for (int i = step.changes.size(); i-- > 0; ) {
			Change change = step.changes.get(i);
			apply(new Change(change.start(), change.inserted(),
					change.removed()));
		}
		selection = step.selectionBefore;
		endBatch();
	}

	/** Redo the last undone step and select what it selected. */
	public void redo() {
		endStep();
		Step step = redo.pollLast();
		if (step == null) {
			return;
		}
		beginBatch();
		typing = null;
		undo.addLast(step);
		for (Change change : step.changes) {
			apply(change);
		}
		selection = step.selectionAfter;
		endBatch();
	}

	/**
	 * Remember that the file holds text now. If that is the current
	 * text, the document is unmodified until it changes, and again
	 * whenever undo or redo return to it. Otherwise, as when the user
	 * typed on while a save was running, it is modified until the next
	 * time the file holds its text.
	 */
	public void markSaved(Text text) {
		if (text == this.text) {
			endStep();
			typing = null;
			saved = undo.peekLast();
		} else {
			saved = UNREACHABLE;
		}
	}

	/** Return whether the text differs from the one last saved. */
	public boolean isModified() {
		return batchStep != null || undo.peekLast() != saved;
	}

	private void edit(int from, int to, Text text, boolean typed) {
		Change change = new Change(from, this.text.slice(from, to), text);
		if (from == to && text.length() == 0) {
			return;
		}
		beginBatch();
		record(change, typed);
		apply(change);
		endBatch();
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

	private void record(Change change, boolean typed) {
		long now = clock.millis();
		if (batchStep == null) {
			batchStep = new Step(selection);
			continued = typed && typing != null &&
					now - typing.time < TYPING_PAUSE ? typing : null;
			for (Step dropped : redo) {
				historyChars -= dropped.chars;
				if (dropped == saved) {
					saved = UNREACHABLE;
				}
			}
			redo.clear();
		}
		batchStep.add(change, typed);
		batchStep.time = now;
		moved = false;
	}

	// Ends the step of the current batch: one typed change that touches
	// what the run of typing changed continues it, any other is a step
	// of its own.
	private void endStep() {
		Step step = batchStep;
		if (step == null) {
			return;
		}
		batchStep = null;
		step.selectionAfter = selection;
		if (continued != null && step.typed &&
				touches(continued.changes.get(0), step.changes.get(0))) {
			historyChars -= continued.chars;
			continued.join(step);
			step = continued;
		} else {
			undo.addLast(step);
		}
		historyChars += step.chars;
		continued = null;
		typing = step.typed && !moved ? step : null;
		while (undo.size() > 1 && (undo.size() > MAX_STEPS ||
				historyChars > MAX_CHARS)) {
			Step dropped = undo.removeFirst();
			historyChars -= dropped.chars;
			if (saved == null) {
				saved = UNREACHABLE;
			} else if (saved == dropped) {
				saved = null;
			}
		}
	}

	// Returns whether b replaces a range that touches what a inserted.
	private static boolean touches(Change a, Change b) {
		return b.start() <= a.start() + a.inserted().length() &&
				b.start() + b.removed().length() >= a.start();
	}

	// Returns the change that makes a and then b, where b touches a.
	private static Change compose(Change a, Change b) {
		int aEnd = a.start() + a.inserted().length();
		int bEnd = b.start() + b.removed().length();
		Text removed = a.removed();
		if (b.start() < a.start()) {
			removed = removed.replace(0, 0,
					b.removed().slice(0, a.start() - b.start()));
		}
		if (bEnd > aEnd) {
			int length = b.removed().length();
			removed = removed.replace(removed.length(), removed.length(),
					b.removed().slice(length - (bEnd - aEnd), length));
		}
		Text inserted = a.inserted().replace(
				Math.max(a.start(), b.start()) - a.start(),
				Math.min(aEnd, bEnd) - a.start(), b.inserted());
		return new Change(Math.min(a.start(), b.start()), removed, inserted);
	}

	// The changes that undo and redo apply together.
	private static final class Step {
		private final List<Change> changes = new ArrayList<>();
		private final Selection selectionBefore;
		private Selection selectionAfter;
		// Whether this step is one typed change that typing may continue.
		private boolean typed = true;
		private long time;
		private int chars;

		private Step(Selection selectionBefore) {
			this.selectionBefore = selectionBefore;
		}

		private void add(Change change, boolean typed) {
			int last = changes.size() - 1;
			if (typed && this.typed && last == 0 &&
					touches(changes.get(0), change)) {
				chars -= size(changes.get(0));
				changes.set(0, compose(changes.get(0), change));
				chars += size(changes.get(0));
				return;
			}
			changes.add(change);
			chars += size(change);
			this.typed = typed && last < 0;
		}

		// Continues this typed step by the typed step next.
		private void join(Step next) {
			Change change = compose(changes.get(0), next.changes.get(0));
			changes.set(0, change);
			chars = size(change);
			selectionAfter = next.selectionAfter;
			time = next.time;
		}

		private static int size(Change change) {
			return change.removed().length() + change.inserted().length();
		}
	}
}
