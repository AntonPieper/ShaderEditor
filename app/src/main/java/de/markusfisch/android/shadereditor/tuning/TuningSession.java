package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The numbers of one file tuned while the tuning bar is open (EDITOR
 * 15). A number that changed and is left is written as one step of undo;
 * the builds have the tuned and the written ones as uniforms. No
 * document ever holds a uniform's name, and nothing is written where the
 * document does not have what the session saw there. Main thread.
 */
public final class TuningSession {
	public interface Host extends TunedPreparation.Project {
		void prepare(@NonNull Callback<Preparation> done);

		/** Run preparation as ShaderEngine.continuePlan() does. */
		void run(@NonNull Preparation preparation,
				@NonNull List<Tuning> tunings,
				@Nullable Tuning.Listener heard);

		void applyTuning();

		/** Run edit of the document, which the session builds itself. */
		void edit(@NonNull Runnable edit);

		/** Run build once nothing asked for it for a moment. */
		void rest(@NonNull Runnable build);

		/** The lexer state line starts in, as the editor keeps it, or -1. */
		int lineState(int line);
	}

	/** Hears on the main thread what the bar must show. */
	public interface Listener {
		void onConstant(boolean constant);

		void onBuilding(boolean building);

		/** Undo or Redo changed the tuned number, or tuned another. */
		void onTuned(@NonNull Tuned tuned);

		/** It cannot tune the file: errors say why. It ended. */
		void onFailed(@NonNull List<ProjectDiagnostic> errors);

		/**
		 * The session is about to end or move: a value typed and not yet
		 * taken is set for the tuned number now, or let go.
		 */
		void onSettle();
	}

	/**
	 * The tuned number: its place, and its text when the bar opened and
	 * now, from signStart to end.
	 */
	public record Tuned(@NonNull TunedPlace place, @NonNull String start,
			@NonNull String text, int id) {
		@NonNull
		public BigDecimal startValue() {
			return TuningSession.value(start);
		}

		@NonNull
		public BigDecimal value() {
			return TuningSession.value(text);
		}

		/** The literal of text: the style a new value is written in. */
		@NonNull
		public String style() {
			return literal(text);
		}

		@NonNull
		public String startStyle() {
			return literal(start);
		}
	}

	// [from, to) is its text in the document, moved by every change; seen
	// is what the session last saw there.
	private static final class Number {
		@NonNull
		final TunedPlace place;
		@NonNull
		final String start;
		final int id;
		int from;
		int to;
		@NonNull
		String seen;
		boolean written;
		@Nullable
		Tuning uniform;
		boolean drawn;
		// GLSL needs a constant here: it is built as text.
		boolean constant;

		Number(@NonNull TunedPlace place, @NonNull String start, int id) {
			this.place = place;
			this.start = start;
			this.id = id;
			from = place.signStart;
			to = place.end;
			seen = start;
		}
	}

	@NonNull
	private final Host host;
	@NonNull
	private final Executor main;
	@NonNull
	private final Executor worker;
	@NonNull
	private final Document document;
	@NonNull
	private final ProjectPath path;
	@NonNull
	private final Listener listener;
	private final List<Number> numbers = new ArrayList<>();
	private int joined;
	// The numbers the document's last change replaced.
	private final List<Number> replaced = new ArrayList<>();
	// The document's states the session made, and the one it began with.
	private final Set<Integer> states = new HashSet<>();
	private final Document.Listener changes = new Document.Listener() {
		@Override
		public void onChange(@NonNull Document.Change change,
				@NonNull Text before) {
			moved(change);
		}
	};
	@Nullable
	private Number tuned;
	// The text of the tuned number while it is not written, or null.
	@Nullable
	private String pending;
	private long named;
	private final Set<String> taken = new HashSet<>();
	// The session's steps the document has, and those Undo took back.
	private int steps;
	private int undone;
	// A build was ready once; a failure before means no tuning at all.
	private boolean known;
	// Ids of the newest build asked for, answered and handed over.
	private int builds;
	private int answered;
	private int submitted;
	private boolean oneAtATime;
	private boolean building;
	private boolean open = true;
	// The session itself changes the document now.
	private boolean editing;

	private TuningSession(@NonNull Host host, @NonNull Executor main,
			@NonNull Executor worker, @NonNull Document document,
			@NonNull ProjectPath path, @NonNull Listener listener) {
		this.host = host;
		this.main = main;
		this.worker = worker;
		this.document = document;
		this.path = path;
		this.listener = listener;
	}

	// main runs what the engine tells on the GL thread; worker reads the
	// whole text for a build.
	@NonNull
	static TuningSession begin(@NonNull Host host, @NonNull Executor main,
			@NonNull Executor worker, @NonNull Document document,
			@NonNull ProjectPath path, @NonNull TunedPlace place,
			@NonNull Listener listener) {
		var session = new TuningSession(host, main, worker, document, path,
				listener);
		session.states.add(document.state());
		document.addListener(session.changes);
		session.tuned = session.join(place);
		session.build();
		return session;
	}

	public boolean isOpen() {
		return open;
	}

	@Nullable
	public Tuned tuned() {
		Number n = tuned;
		if (!open || n == null) {
			return null;
		}
		// As the text has it now, but for a negative value in parentheses.
		TunedPlace now = window(n.to - 1).place(n.to - 1);
		return new Tuned(now != null && now.signStart == n.from &&
				now.end == n.to ? now : n.place, n.start, text(n), n.id);
	}

	/** Try text, from signStart to end, for the tuned number. */
	public void set(@NonNull String text) {
		if (text.contains(TunedPreparation.PREFIX)) {
			throw new IllegalArgumentException(
					"the uniform of a session is never written");
		}
		Number n = tuned;
		// A value its type cannot hold is never written, nor shown.
		if (!open || n == null ||
				LiteralText.type(literal(text)) != n.place.type) {
			return;
		}
		// Back at its start, it is what was there, byte for byte.
		String now = value(text).compareTo(value(n.start)) == 0
				? n.start
				: text;
		// The same value in other digits, as a step's, is no change.
		pending = value(now).compareTo(value(written(n))) == 0 ? null : now;
		show(n);
	}

	/** Tune the number at offset, writing the one left; null if none. */
	@Nullable
	public Tuned move(int offset) {
		if (!open) {
			return null;
		}
		listener.onSettle();
		Number next = numberAt(offset);
		if (next == null) {
			TunedPlace place = window(offset).place(offset);
			if (place == null) {
				return null;
			}
			// Before the one left is written: the write moves it.
			next = join(place);
		}
		Number left = tuned;
		if (next != left) {
			if (left != null) {
				leave(left);
			}
			tuned = next;
			listener.onConstant(next.constant);
			if (next.uniform == null) {
				host.rest(this::build);
			}
		}
		return tuned();
	}

	@Nullable
	public Statement statement() {
		Number n = tuned;
		return !open || n == null
				? null
				: Statement.of(window(n.from), n.from, n.to, offset -> {
					Number other = numberAt(offset);
					return other != null && other != n &&
							!text(other).equals(other.start);
				});
	}

	/** The text of the next or previous number, or null at an end. */
	@Nullable
	public String neighbour(boolean forward) {
		int at = neighbourAt(forward);
		TunedPlace place = at < 0 ? null : window(at).place(at);
		return place != null ? place.literalWithSign() : null;
	}

	/** {@link #move} to the next or previous number; null at an end. */
	@Nullable
	public Tuned step(boolean forward) {
		int at = neighbourAt(forward);
		return at < 0 ? null : move(at);
	}

	// Window by window, so that no step reads the whole text.
	private int neighbourAt(boolean forward) {
		Number n = tuned;
		if (!open || n == null) {
			return -1;
		}
		int length = document.text().length();
		for (int at = forward ? n.to : n.from; ; ) {
			Window lines = window(at);
			int found = lines.number(at, forward);
			if (found > -1) {
				return found;
			}
			at = forward ? lines.end() + 1 : lines.start() - 1;
			if (at < 0 || at > length) {
				return -1;
			}
		}
	}

	public boolean canUndo() {
		return open && ((steps > 0 && document.canUndo()) || pending != null);
	}

	public boolean canRedo() {
		return open && undone > 0 && document.canRedo() && pending == null;
	}

	/** The value tried is written first, so that Redo brings it back. */
	public void undo() {
		if (open) {
			listener.onSettle();
		}
		if (!canUndo()) {
			return;
		}
		if (pending != null && !write()) {
			return;
		}
		walk(true);
	}

	public void redo() {
		if (open) {
			listener.onSettle();
		}
		if (canRedo()) {
			walk(false);
		}
	}

	/**
	 * The tuned number is written as a step; then, unless keep, every
	 * number goes back to its start as one more, as Revert does.
	 */
	public void end(boolean keep) {
		if (!open) {
			return;
		}
		listener.onSettle();
		write();
		if (!keep) {
			revert();
		}
		// The picture has the text's values at once, not once its build
		// swaps in.
		for (Number n : numbers) {
			if (n.uniform != null) {
				n.uniform.set(value(written(n)).doubleValue());
			}
		}
		host.applyTuning();
		drop();
		// The text's build replaces the session's, also with an edit made
		// just before the bar opened, whose own run the bar held back.
		host.prepare(preparation -> {
			if (preparation != null) {
				host.run(preparation, List.of(), null);
			}
		});
	}

	/** The project changed: a build being prepared is asked for again. */
	public void projectChanged() {
		if (open && answered != builds) {
			host.rest(this::build);
		}
	}

	/** Between these states, Undo and Redo change only tuned numbers. */
	@NonNull
	public Set<Integer> states() {
		return Collections.unmodifiableSet(states);
	}

	// Ends without writing or building, as when the screen goes.
	void drop() {
		open = false;
		document.removeListener(changes);
		setBuilding(false);
	}

	@NonNull
	private Number join(@NonNull TunedPlace place) {
		var number = new Number(place, document.text().substring(
				place.signStart, place.end), ++joined);
		numbers.add(number);
		return number;
	}

	@Nullable
	private Number numberAt(int offset) {
		for (Number n : numbers) {
			if (n.from <= offset && offset <= n.to) {
				return n;
			}
		}
		return null;
	}

	@NonNull
	private String text(@NonNull Number n) {
		return n == tuned && pending != null ? pending : written(n);
	}

	@NonNull
	private String written(@NonNull Number n) {
		return document.text().substring(n.from, n.to);
	}

	// One never written is no uniform of the builds anymore.
	private void leave(@NonNull Number n) {
		write();
		if (!n.written) {
			n.uniform = null;
			n.drawn = false;
		}
	}

	// Only where the document still has what the session saw, and only
	// a write the document takes counts.
	private boolean write() {
		Number n = tuned;
		String text = pending;
		if (n == null || text == null) {
			return false;
		}
		boolean[] took = {false};
		if (written(n).equals(n.seen)) {
			edit(() -> took[0] = document.replace(n.from, n.to, Text.of(text)));
		}
		pending = null;
		if (!took[0]) {
			// Refused, as past the text's limit, which the document says:
			// the value goes, and no other number gets it.
			show(n);
			return false;
		}
		++steps;
		undone = 0;
		n.written = true;
		changed();
		return true;
	}

	private void edit(@NonNull Runnable change) {
		host.edit(() -> {
			editing = true;
			document.beginBatch();
			try {
				change.run();
			} finally {
				document.endBatch();
				editing = false;
			}
		});
	}

	// The project drops a build being prepared when its text changes.
	private void changed() {
		states.add(document.state());
		if (answered != builds) {
			host.rest(this::build);
		}
	}

	private void walk(boolean back) {
		replaced.clear();
		edit(back ? document::undo : document::redo);
		steps += back ? -1 : 1;
		undone += back ? 1 : -1;
		changed();
		if (!replaced.isEmpty()) {
			Number n = replaced.get(replaced.size() - 1);
			Number left = tuned;
			if (n != left) {
				tuned = n;
				// The one tuned before had no value to write: Undo
				// wrote it first.
				if (left != null && !left.written) {
					left.uniform = null;
					left.drawn = false;
				}
				listener.onConstant(n.constant);
			}
		}
		for (Number n : replaced) {
			show(n);
		}
		Tuned now = tuned();
		if (now != null) {
			listener.onTuned(now);
		}
	}

	// Shrinking ones first, so that the text never grows on the way;
	// each only where the document has what the session saw.
	private void revert() {
		List<Number> back = new ArrayList<>();
		for (Number n : numbers) {
			if (!n.start.equals(written(n)) && written(n).equals(n.seen)) {
				back.add(n);
			}
		}
		if (back.isEmpty()) {
			return;
		}
		Collections.sort(back, (a, b) -> (a.start.length() - a.seen.length()) -
				(b.start.length() - b.seen.length()));
		edit(() -> {
			for (Number n : back) {
				if (!document.replace(n.from, n.to, Text.of(n.start))) {
					return;
				}
			}
		});
		changed();
	}

	// The session's own edits replace a number exactly. Any other change
	// that touches a number or its ends may have made it another token,
	// as a digit typed in front: it is dropped, its value unwritten.
	private void moved(@NonNull Document.Change change) {
		int start = change.start();
		int end = start + change.removed().length();
		int delta = change.inserted().length() - change.removed().length();
		for (int i = numbers.size(); i-- > 0; ) {
			Number n = numbers.get(i);
			if (editing && start == n.from && end == n.to) {
				n.to = n.from + change.inserted().length();
				n.seen = change.inserted().toString();
				replaced.add(n);
			} else if (editing ? end <= n.from : end < n.from) {
				n.from += delta;
				n.to += delta;
			} else if (editing ? start < n.to : start <= n.to) {
				numbers.remove(i);
				if (n == tuned) {
					tuned = null;
					pending = null;
				}
			}
		}
	}

	private void show(@NonNull Number n) {
		if (n.uniform != null) {
			n.uniform.set(value(text(n)).doubleValue());
			host.applyTuning();
		} else {
			host.rest(this::build);
		}
	}

	// A uniform no build drew yet is tried; once several tried together
	// were refused, one at a time.
	private void build() {
		if (!open) {
			return;
		}
		List<Number> order = new ArrayList<>(numbers);
		if (tuned != null && order.remove(tuned)) {
			order.add(0, tuned);
		}
		List<OpenProject.Replacement> parts = new ArrayList<>();
		List<Tuning> uniforms = new ArrayList<>();
		List<Number> trying = new ArrayList<>();
		List<Integer> literals = new ArrayList<>();
		for (Number n : order) {
			if (n.uniform == null && !n.constant &&
					(n == tuned || n.written) &&
					!(oneAtATime && !trying.isEmpty())) {
				n.uniform = uniform(n);
			}
			if (n.uniform != null && !n.drawn && oneAtATime &&
					!trying.isEmpty()) {
				// Its turn comes; the text has its value meanwhile.
				n.uniform = null;
			}
			if (n.uniform != null) {
				parts.add(new OpenProject.Replacement(path, n.from, n.to,
						"(" + n.uniform.name() + ")"));
				uniforms.add(n.uniform);
				if (!n.drawn) {
					trying.add(n);
					literals.add(literal(n));
				}
			} else if (n.constant || n == tuned) {
				parts.add(new OpenProject.Replacement(path, n.from, n.to,
						text(n)));
			}
		}
		List<Tuning> theirs = new ArrayList<>();
		for (Number n : trying) {
			theirs.add(n.uniform);
		}
		Build build = new Build(++builds, trying, theirs, uniforms);
		int revision = document.revision();
		setBuilding(trying.isEmpty());
		TunedPreparation.prepare(host, path, parts, literals, this::work, main,
				result -> {
					// Of a text that changed meanwhile, which asked again.
					if (revision == document.revision()) {
						built(build, result);
					}
				});
	}

	// An answer can come after the end, when the screen shut the worker
	// down: a closed session hands no work on.
	private void work(@NonNull Runnable task) {
		if (open) {
			worker.execute(task);
		}
	}

	// theirs.get(i) is the uniform trying.get(i) has in this build.
	private record Build(int id, @NonNull List<Number> trying,
			@NonNull List<Tuning> theirs, @NonNull List<Tuning> uniforms) {
		boolean has(@NonNull Number n) {
			int i = trying.indexOf(n);
			return i > -1 && n.uniform == theirs.get(i);
		}
	}

	@NonNull
	private Tuning uniform(@NonNull Number n) {
		String name;
		do {
			name = TunedPreparation.name(named++);
		} while (taken.contains(name));
		return new Tuning(name, n.place.type, value(text(n)).doubleValue());
	}

	private static int literal(@NonNull Number n) {
		return n.from + n.place.start - n.place.signStart;
	}

	private void built(@NonNull Build build,
			@NonNull TunedPreparation.Result result) {
		if (!open || build.id != builds) {
			return;
		}
		answered = build.id;
		if (result instanceof TunedPreparation.Taken names) {
			// A file has a name of ours: every uniform gets another.
			taken.addAll(names.names());
			for (Number n : numbers) {
				if (n.uniform != null) {
					n.uniform = uniform(n);
					n.drawn = false;
				}
			}
			build();
			return;
		}
		if (result instanceof TunedPreparation.Failed failed) {
			// Nothing to blame and nothing to try: asking again would fail
			// the same way.
			if (!known || build.trying.isEmpty()) {
				drop();
				listener.onFailed(failed.errors());
			} else {
				refused(build);
			}
			return;
		}
		var ready = (TunedPreparation.Ready) result;
		known = true;
		if (!ready.constants().isEmpty()) {
			// GLSL needs these as constants: built again without them.
			for (Number n : build.trying) {
				if (ready.constants().contains(literal(n)) && build.has(n)) {
					constant(n);
				}
			}
			host.rest(this::build);
			return;
		}
		submitted = build.id;
		setBuilding(false);
		host.run(ready.preparation(), build.uniforms, build.trying.isEmpty()
				? null
				: new Tuning.Listener() {
					@Override
					public void onLive() {
						main.execute(() -> drew(build));
					}

					@Override
					public void onRefused(
							@NonNull List<ProjectDiagnostic> errors) {
						main.execute(() -> refused(build));
					}
				});
	}

	// Only the newest build handed over counts, for the uniforms it had.
	private void drew(@NonNull Build build) {
		if (!open || build.id != submitted) {
			return;
		}
		for (Number n : build.trying) {
			n.drawn |= build.has(n);
		}
		// A value set while this news was on its way would wait otherwise.
		host.applyTuning();
		if (waiting()) {
			host.rest(this::build);
		}
	}

	// Tried one at a time: a number waits for its turn.
	private boolean waiting() {
		for (Number n : numbers) {
			if (oneAtATime && n.uniform == null && !n.constant &&
					(n == tuned || n.written)) {
				return true;
			}
		}
		return false;
	}

	// One uniform tried alone needs a constant; of several, any may be at
	// fault. One that drew before (the context was lost) is asked again.
	private void refused(@NonNull Build build) {
		if (!open || (build.id != submitted && build.id != answered)) {
			return;
		}
		setBuilding(false);
		List<Number> tried = new ArrayList<>();
		boolean drew = false;
		for (Number n : build.trying) {
			drew |= n.drawn;
			if (build.has(n) && !n.drawn) {
				tried.add(n);
			}
		}
		if (tried.size() == 1) {
			constant(tried.get(0));
		} else if (!tried.isEmpty()) {
			oneAtATime = true;
		}
		if (drew || tried.size() > 1 || waiting() || (tried.size() == 1 &&
				!text(tried.get(0)).equals(tried.get(0).start))) {
			host.rest(this::build);
		}
	}

	private void constant(@NonNull Number n) {
		n.uniform = null;
		n.constant = true;
		if (n == tuned) {
			listener.onConstant(true);
		}
	}

	private void setBuilding(boolean building) {
		if (this.building != building) {
			this.building = building;
			listener.onBuilding(building);
		}
	}

	@NonNull
	private Window window(int offset) {
		return Window.around(document.text(), offset, host::lineState);
	}

	@NonNull
	private static BigDecimal value(@NonNull String text) {
		BigDecimal value = LiteralText.value(literal(text));
		if (value == null) {
			return BigDecimal.ZERO;
		}
		return isNegative(text) ? value.negate() : value;
	}

	// Without the parentheses of a negative value, and its minus.
	@NonNull
	private static String literal(@NonNull String text) {
		String literal = text.replace("(", "").replace(")", "").trim();
		return literal.startsWith("-") ? literal.substring(1) : literal;
	}

	private static boolean isNegative(@NonNull String text) {
		return text.replace("(", "").trim().startsWith("-");
	}
}
