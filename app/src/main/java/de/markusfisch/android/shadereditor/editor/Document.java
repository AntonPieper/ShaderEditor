package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// One file being edited: its text, its selection and their undo history.
// Listeners hear of each change, and once more when an edit or a batch is
// complete. Used on one thread; its texts may be read on any.
public final class Document {
	// The most bytes of UTF-8 a text may grow to by editing; files are opened
	// for editing up to the same size.
	public static final long MAX_BYTES = 16L * 1024 * 1024;

	public record Change(int start, Text removed, Text inserted) {
		// An offset inside or at either end of the removed range ends up behind
		// the inserted text, so a caret stays behind what was typed.
		public int map(int offset) {
			return offset < start
					? offset
					: Math.max(offset - removed.length(), start) +
							inserted.length();
		}
	}

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
		// Also inside a batch; before is the text the change was made to. It
		// must not edit the document.
		default void onChange(Change change, Text before) {
		}

		// Once per edit or selection, or at the end of the outermost batch.
		default void onUpdate() {
		}

		default void onRefused(String reason) {
		}
	}

	// An action beyond the text that a step belongs to, such as a rename of a
	// file: Undo of that step asks the action, which takes back all of itself
	// through undoLinked, or nothing.
	public interface Link {
		void undo(Document document);
	}

	// What a document keeps besides its text, for a document of the same text.
	// Which state was saved is not part of it.
	public record History(List<Step> undo, List<Step> redo, int revision,
			int state, Selection selection) {
		public History {
			undo = List.copyOf(undo);
			redo = List.copyOf(redo);
		}

		// grown is the bytes the step adds to the text.
		public record Step(List<Change> changes, Selection selectionBefore,
				Selection selectionAfter, int stateBefore, int stateAfter,
				long grown) {
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
	// Becomes a step of the history when the batch ends.
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

	// bytes is what bytes(Text) counts, so the text need not be counted.
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

	// Lines joined by '\n'; a lone surrogate counts as two.
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

	// The bytes changes add to a text, for a step not taken from a document.
	public static long grown(List<Change> changes) {
		long grown = 0;
		for (Change change : changes) {
			grown += bytes(change.inserted()) - bytes(change.removed());
		}
		return grown;
	}

	// Its texts are shared, so it costs a list of the steps. The step of an
	// open batch is in it as if the batch ended now.
	public History history() {
		// A step of an action cannot be taken back without it, which is
		// not kept: the history begins behind the last such step.
		ArrayDeque<Step> kept = new ArrayDeque<>();
		for (Step step : undo) {
			if (step.link != null) {
				kept.clear();
			} else {
				kept.addLast(step);
			}
		}
		List<History.Step> undone = export(kept);
		if (batchStep != null && batchStep.link != null) {
			undone.clear();
		} else if (batchStep != null) {
			undone.add(new History.Step(batchStep.changes,
					batchStep.selectionBefore, selection,
					batchStep.stateBefore, state, batchStep.grown));
		}
		return new History(undone, export(redo), revision, state,
				selection);
	}

	// A document with a history of its own, in a batch, or whose text cannot
	// hold the history's selection keeps its own and returns false. Whether it
	// is modified stays as it is; listeners are not told.
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

	public int historyChars() {
		return historyChars;
	}

	public Text text() {
		return text;
	}

	// The number of changes made so far.
	public int revision() {
		return revision;
	}

	// Undo and redo return to an earlier state; every other change makes a new
	// one. A save takes it with the text it saves.
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

	// Not after another edit, undo, redo or a selection elsewhere.
	public boolean isTyping() {
		return typing != null;
	}

	// Another place ends a run of typing.
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

	// Replacing a range by the text it holds only moves the selection. Returns
	// false, and tells the listeners why, when the text would grow past
	// MAX_BYTES.
	public boolean replace(int from, int to, Text text) {
		return replace(from, to, text, bytes(text));
	}

	// bytes is what bytes(Text) counts, so the text need not be counted.
	public boolean replace(int from, int to, Text text, long bytes) {
		return edit(from, to, text, bytes, false);
	}

	// A typed change joins the last step if that was typed less than a second
	// ago and touches what it changed.
	public boolean type(int from, int to, Text text) {
		return edit(from, to, text, bytes(text), true);
	}

	// Changes until endBatch() are one undo step, typing only if all of them
	// are typing; listeners hear onUpdate once. Batches nest.
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

	// Call it inside a batch, after its edits; a batch without edits has no
	// step to link.
	public void link(Link link) {
		if (batchStep != null) {
			batchStep.link = link;
		}
	}

	public boolean endsWith(Link link) {
		endStep();
		Step step = undo.peekLast();
		return step != null && step.link == link;
	}

	// Redo cannot make it again, since it would not make the rest of link.
	public void undoLinked(Link link) {
		if (!endsWith(link)) {
			return;
		}
		takeBack();
		for (Step dropped : redo) {
			historyChars -= dropped.chars;
		}
		redo.clear();
	}

	// A step that belongs to a Link asks it instead.
	public void undo() {
		endStep();
		Step last = undo.peekLast();
		if (last != null && last.link != null) {
			last.link.undo(this);
			return;
		}
		takeBack();
	}

	private void takeBack() {
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
					change.removed()), 0);
		}
		bytes -= step.grown;
		selection = step.selectionBefore;
		state = step.stateBefore;
		endBatch();
	}

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
			apply(change, 0);
		}
		bytes += step.grown;
		selection = step.selectionAfter;
		state = step.stateAfter;
		endBatch();
	}

	// The file holds the text of state. A state the history cannot return to,
	// such as one in a run of typing that went on while the save ran, or -1,
	// leaves the document modified until the next markSaved.
	public void markSaved(int state) {
		if (state == this.state) {
			endStep();
			typing = null;
		}
		saved = state;
	}

	public boolean isModified() {
		return state != saved;
	}

	private boolean edit(int from, int to, Text text, long textBytes,
			boolean typed) {
		Change change = new Change(from, this.text.slice(from, to), text);
		if (same(change.removed(), text)) {
			select(change.map(selection.anchor()),
					change.map(selection.head()));
			return true;
		}
		long grown = textBytes - removedBytes(change);
		if (grown > 0 && bytes + grown > MAX_BYTES) {
			String reason = "the text would be larger than " +
					MAX_BYTES / (1024 * 1024) + " MiB";
			for (Listener listener : listeners) {
				listener.onRefused(reason);
			}
			return false;
		}
		beginBatch();
		record(change, typed, grown);
		apply(change, grown);
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

	private void apply(Change change, long grown) {
		bytes += grown;
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

	private void record(Change change, boolean typed, long grown) {
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
		batchStep.grown += grown;
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
					step.selectionAfter, step.stateBefore, step.stateAfter,
					step.grown));
		}
		return exported;
	}

	private static Step restore(History.Step exported) {
		Step step = new Step(exported.selectionBefore(),
				exported.stateBefore());
		step.selectionAfter = exported.selectionAfter();
		step.stateAfter = exported.stateAfter();
		step.grown = exported.grown();
		step.typed = false;
		for (Change change : exported.changes()) {
			step.changes.add(change);
			step.chars += Step.size(change);
		}
		return step;
	}

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

	private static final class Step {
		private final List<Change> changes = new ArrayList<>();
		private final Selection selectionBefore;
		private final int stateBefore;
		private Selection selectionAfter;
		private int stateAfter;
		// Whether this step is one typed change that typing may continue.
		private boolean typed = true;
		private Link link;
		private long time;
		private int chars;
		private long grown;

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

		private void join(Step next) {
			Change change = compose(changes.get(0), next.changes.get(0));
			changes.set(0, change);
			chars = size(change);
			grown += next.grown;
			selectionAfter = next.selectionAfter;
			stateAfter = next.stateAfter;
			time = next.time;
		}

		private static int size(Change change) {
			return change.removed().length() + change.inserted().length();
		}
	}
}
