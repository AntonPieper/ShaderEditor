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
 * steps, and a run of typing is one step. An edit that would grow the
 * text past {@link #MAX_BYTES} is refused. A document is used on one
 * thread; its texts may be read on any.
 */
public final class Document {
	/**
	 * The most bytes of UTF-8 a text may grow to by editing, on every
	 * device; files are opened for editing up to the same size.
	 */
	public static final long MAX_BYTES = 16L * 1024 * 1024;

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

		/**
		 * Called when an edit was refused; reason tells the user why.
		 * The document is as it was.
		 */
		default void onRefused(String reason) {
		}
	}

	/**
	 * What a document keeps besides its text, to be taken by a document
	 * of the same text: the steps undo and redo take, oldest first, the
	 * revision and state the text is at, and the selection. Which state
	 * was saved is not part of it.
	 */
	public record History(List<Step> undo, List<Step> redo, int revision,
			int state, Selection selection) {
		public History {
			undo = List.copyOf(undo);
			redo = List.copyOf(redo);
		}

		/**
		 * The changes of one step of the history, in the order they were
		 * made, and the selection and the state before and after them.
		 */
		public record Step(List<Change> changes, Selection selectionBefore,
				Selection selectionAfter, int stateBefore, int stateAfter) {
			public Step {
				changes = List.copyOf(changes);
			}
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

	private final ArrayDeque<Step> undo = new ArrayDeque<>();
	private final ArrayDeque<Step> redo = new ArrayDeque<>();
	private final Clock clock;
	private Listener[] listeners = new Listener[0];
	private Text text;
	private int revision;
	// The revision that first made the text; undo and redo return to it.
	private int state;
	private int saved;
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
	private int historyChars;
	// The bytes of UTF-8 of the text.
	private long bytes;

	public Document(Text text) {
		this(text, bytes(text));
	}

	/**
	 * A document of text, which has bytes of UTF-8, as
	 * {@link #bytes(Text)} counts them, so they need not be counted.
	 */
	public Document(Text text, long bytes) {
		this(text, bytes, () -> System.nanoTime() / 1_000_000);
	}

	Document(Text text, Clock clock) {
		this(text, bytes(text), clock);
	}

	private Document(Text text, long bytes, Clock clock) {
		this.text = text;
		this.bytes = bytes;
		this.clock = clock;
	}

	/**
	 * Return how many bytes of UTF-8 text has, lines joined by '\n'; a
	 * lone surrogate counts as two.
	 */
	public static long bytes(Text text) {
		long bytes = text.lineCount() - 1;
		for (int i = text.lineCount(); i-- > 0; ) {
			String line = text.line(i);
			for (int j = line.length(); j-- > 0; ) {
				char c = line.charAt(j);
				bytes += c < 0x80 ? 1
						: c < 0x800 || Character.isSurrogate(c) ? 2 : 3;
			}
		}
		return bytes;
	}

	/**
	 * Return the history of the document, to give a document of the
	 * same text later; see {@link #restore}. Its texts are shared, so it
	 * costs a list of the steps. Call it outside a batch.
	 */
	public History history() {
		return new History(export(undo), export(redo), revision, state,
				selection);
	}

	/**
	 * Take history, of a document whose text was this one's, so undo and
	 * redo take the steps they took there, and select as it was. A
	 * document that has a history of its own, or is in a batch, keeps
	 * it; so does one whose text cannot be at the selection history has.
	 * Whether the document is modified stays as it is: unmodified, its
	 * text is the saved one, else no state the history returns to is.
	 * Listeners are not told. Return whether it was taken.
	 */
	public boolean restore(History history) {
		Selection selected = history.selection();
		int length = text.length();
		if (batches > 0 || !undo.isEmpty() || !redo.isEmpty() ||
				selected.anchor() < 0 || selected.anchor() > length ||
				selected.head() < 0 || selected.head() > length) {
			return false;
		}
		for (History.Step step : history.undo()) {
			undo.addLast(restore(step));
		}
		for (History.Step step : history.redo()) {
			redo.addLast(restore(step));
		}
		historyChars = 0;
		for (Step step : undo) {
			historyChars += step.chars;
		}
		for (Step step : redo) {
			historyChars += step.chars;
		}
		revision = Math.max(revision, history.revision());
		saved = state == saved ? history.state() : -1;
		state = history.state();
		selection = selected;
		typing = null;
		return true;
	}

	/**
	 * Return how many characters the texts of the steps undo and redo
	 * keep add up to.
	 */
	public int historyChars() {
		return historyChars;
	}

	/** Return the current text. */
	public Text text() {
		return text;
	}

	/** Return the number of changes made so far. */
	public int revision() {
		return revision;
	}

	/**
	 * Return where the current text is in the history: undo and redo
	 * return to the state of the text they return to, and every other
	 * change makes a new one. A save takes it with the text it saves.
	 */
	public int state() {
		return state;
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
	 * follows the change as Change.map moves it. Replacing a range by
	 * the text it holds only moves the selection so; it is no change.
	 * Return false when the change would grow the text past
	 * {@link #MAX_BYTES}: it is refused, and the listeners hear why.
	 */
	public boolean replace(int from, int to, Text text) {
		return edit(from, to, text, false);
	}

	/**
	 * Replace as replace does, as typing: a typed change joins the
	 * last step if that was typed less than a second ago and the
	 * change touches the text it changed. Selecting another place,
	 * any other edit, undo, redo and markSaved end the run.
	 */
	public boolean type(int from, int to, Text text) {
		return edit(from, to, text, true);
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
		state = step.stateBefore;
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
		state = step.stateAfter;
		endBatch();
	}

	/**
	 * Remember that the file holds the text of state, as state() was
	 * when the save took the text. The document is unmodified whenever
	 * undo and redo return to that state, and now if it is current. A
	 * state the history cannot return to, as one of a run of typing that
	 * went on while the save ran, or -1 for a text the document never
	 * had, leaves it modified until the next markSaved.
	 */
	public void markSaved(int state) {
		if (state == this.state) {
			endStep();
			typing = null;
		}
		saved = state;
	}

	/** Return whether the text differs from the one last saved. */
	public boolean isModified() {
		return state != saved;
	}

	private boolean edit(int from, int to, Text text, boolean typed) {
		Change change = new Change(from, this.text.slice(from, to), text);
		if (same(change.removed(), text)) {
			select(change.map(selection.anchor()),
					change.map(selection.head()));
			return true;
		}
		long grown = bytes(text) - removedBytes(change);
		if (grown > 0 && bytes + grown > MAX_BYTES) {
			String reason = "the text would be larger than " +
					MAX_BYTES / (1024 * 1024) + " MiB";
			for (Listener listener : listeners) {
				listener.onRefused(reason);
			}
			return false;
		}
		beginBatch();
		record(change, typed);
		apply(change);
		state = revision;
		endBatch();
		return true;
	}

	// Return the bytes change removes, without counting the whole text.
	private long removedBytes(Change change) {
		return change.removed().length() == text.length()
				? bytes
				: bytes(change.removed());
	}

	private void apply(Change change) {
		bytes += bytes(change.inserted()) - removedBytes(change);
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
			batchStep = new Step(selection, state);
			continued = typed && typing != null &&
					now - typing.time < TYPING_PAUSE ? typing : null;
			for (Step dropped : redo) {
				historyChars -= dropped.chars;
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
		step.stateAfter = state;
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
			historyChars -= undo.removeFirst().chars;
		}
	}

	private static List<History.Step> export(ArrayDeque<Step> steps) {
		List<History.Step> exported = new ArrayList<>(steps.size());
		for (Step step : steps) {
			exported.add(new History.Step(step.changes, step.selectionBefore,
					step.selectionAfter, step.stateBefore, step.stateAfter));
		}
		return exported;
	}

	private static Step restore(History.Step exported) {
		Step step = new Step(exported.selectionBefore(),
				exported.stateBefore());
		step.selectionAfter = exported.selectionAfter();
		step.stateAfter = exported.stateAfter();
		step.typed = false;
		for (Change change : exported.changes()) {
			step.changes.add(change);
			step.chars += Step.size(change);
		}
		return step;
	}

	// Returns whether a and b hold the same characters.
	private static boolean same(Text a, Text b) {
		if (a.length() != b.length() || a.lineCount() != b.lineCount()) {
			return false;
		}
		for (int i = a.lineCount(); i-- > 0; ) {
			if (!a.line(i).equals(b.line(i))) {
				return false;
			}
		}
		return true;
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
		private final int stateBefore;
		private Selection selectionAfter;
		private int stateAfter;
		// Whether this step is one typed change that typing may continue.
		private boolean typed = true;
		private long time;
		private int chars;

		private Step(Selection selectionBefore, int stateBefore) {
			this.selectionBefore = selectionBefore;
			this.stateBefore = stateBefore;
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
			stateAfter = next.stateAfter;
			time = next.time;
		}

		private static int size(Change change) {
			return change.removed().length() + change.inserted().length();
		}
	}
}
