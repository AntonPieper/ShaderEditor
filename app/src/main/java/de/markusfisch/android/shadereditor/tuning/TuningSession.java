package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * One number of the running shader tuned. begin() builds the shader
 * once with the number replaced by a uniform; set() gives the uniform a
 * new value with the next frame, without a build; end() builds the text
 * once more, with the new number written in its place, or as it was.
 * Every build of a session goes on from the picture: time, frame and
 * the passes' histories run on (see ShaderEngine.continuePlan()).
 *
 * Where GLSL needs a constant, the number cannot be a uniform: the
 * session is then MUST_REBUILD, and rebuild() builds the shader with a
 * new text in the number's place, each time it is asked to.
 *
 * Nothing here writes a file, and no document ever holds the uniform:
 * its builds replace the number in the text they are made of only, and
 * are temporary (Preparation.temporary()), so the wallpaper never
 * installs one. At end(), the host writes the caller's new text in
 * place of the number. Main thread.
 */
public final class TuningSession {
	public enum State {
		/** Its build is being made. */
		STARTING,
		/** Its build draws: set() shows a value with the next frame. */
		LIVE,
		/** The number must stay a constant: rebuild() shows a text. */
		MUST_REBUILD,
		/** It cannot be tuned. */
		FAILED,
		ENDED
	}

	/** The project and the engine a session works on. */
	public interface Host extends TunedPreparation.Project {
		/** Prepare the running shader from the text as it is now. */
		void prepare(@NonNull Callback<Preparation> done);

		/**
		 * Run preparation as a revision of the picture that runs, going
		 * on from it (ShaderEngine.continuePlan()); tuning is the
		 * uniform of the tuned number, or null for a text.
		 */
		void run(@NonNull Preparation preparation, @Nullable Tuning tuning);

		/** Show the value of the tuning that runs with the next frame. */
		void applyTuning();

		/**
		 * Write text in place of start to end of path as one step of
		 * undo. The session builds the text right after it; the run that
		 * the change would start otherwise is that build.
		 */
		void write(@NonNull ProjectPath path, int start, int end,
				@NonNull String text);
	}

	/** Hears once, on the main thread, what became of begin(). */
	public interface Listener {
		/** The build draws: set() shows from now on. */
		void onLive();

		/**
		 * The number must stay a constant, as the tokens say or the
		 * compiler refused (why says where); rebuild() shows a text.
		 */
		void onMustRebuild(@NonNull List<ProjectDiagnostic> why);

		/** It cannot be tuned: errors say why. */
		void onFailed(@NonNull List<ProjectDiagnostic> errors);
	}

	@NonNull
	private final Host host;
	@NonNull
	private final Executor main;
	@NonNull
	private final ProjectPath path;
	private final int start;
	private final int end;
	@NonNull
	private final Tuning.Type type;
	@NonNull
	private final Listener listener;
	@NonNull
	private State state = State.STARTING;
	@Nullable
	private Tuning tuning;
	// The value set before the uniform exists.
	private double value = Double.NaN;
	// Whether a build of the session was handed to the engine, so that
	// the text is built again at the end.
	private boolean ran;
	// The text a rebuild is being prepared for, and the one asked for
	// meanwhile, which follows it.
	private boolean rebuilding;
	@Nullable
	private String pending;

	private TuningSession(@NonNull Host host, @NonNull Executor main,
			@NonNull ProjectPath path, int start, int end,
			@NonNull Tuning.Type type, @NonNull Listener listener) {
		this.host = host;
		this.main = main;
		this.path = path;
		this.start = start;
		this.end = end;
		this.type = type;
		this.listener = listener;
	}

	/**
	 * Tune the number from start to end of the text of path, which
	 * must be one of type; listener hears what became of it. main runs
	 * on the main thread what the engine tells on the GL thread.
	 */
	@NonNull
	public static TuningSession begin(@NonNull Host host,
			@NonNull Executor main, @NonNull ProjectPath path, int start,
			int end, @NonNull Tuning.Type type, @NonNull Listener listener) {
		var session = new TuningSession(host, main, path, start, end, type,
				listener);
		TunedPreparation.prepare(host, path, start, end, type,
				session::started);
		return session;
	}

	@NonNull
	public State state() {
		return state;
	}

	/** Show value with the next frame while LIVE; kept while STARTING. */
	public void set(double value) {
		this.value = value;
		if (tuning != null) {
			tuning.set(value);
			if (state == State.LIVE) {
				host.applyTuning();
			}
		}
	}

	/**
	 * Build the shader with text in place of the number while
	 * MUST_REBUILD. While one is being prepared, the newest text asked
	 * for meanwhile follows it.
	 */
	public void rebuild(@NonNull String text) {
		if (state != State.MUST_REBUILD) {
			return;
		}
		if (rebuilding) {
			pending = text;
			return;
		}
		rebuilding = true;
		host.prepare(new OpenProject.Replacement(path, start, end, text),
				preparation -> {
					rebuilding = false;
					if (state != State.MUST_REBUILD) {
						return;
					}
					if (preparation != null) {
						ran = true;
						host.run(preparation, null);
					}
					String next = pending;
					pending = null;
					if (next != null) {
						rebuild(next);
					}
				});
	}

	/**
	 * End the session: commit, if not null, is written in place of the
	 * number (Done), else the text stays as it was (Revert). The text is
	 * built once more, going on from the picture, when a build of the
	 * session ran or the text changed.
	 */
	public void end(@Nullable String commit) {
		if (state == State.ENDED) {
			return;
		}
		state = State.ENDED;
		if (commit != null) {
			if (tuning != null && commit.contains(tuning.name())) {
				throw new IllegalArgumentException(
						"the uniform of a session is never written");
			}
			host.write(path, start, end, commit);
		}
		if (ran || commit != null) {
			host.prepare(preparation -> {
				if (preparation != null) {
					host.run(preparation, null);
				}
			});
		}
	}

	private void started(@NonNull TunedPreparation.Result result) {
		if (state != State.STARTING) {
			return;
		}
		if (result instanceof TunedPreparation.Ready ready) {
			// What the engine tells is about this uniform only.
			Tuning[] made = {null};
			made[0] = tuning = new Tuning(ready.name(), type,
					Double.isNaN(value) ? ready.number().value() : value,
					new Tuning.Listener() {
						@Override
						public void onLive() {
							main.execute(() -> live(made[0]));
						}

						@Override
						public void onRefused(
								@NonNull List<ProjectDiagnostic> errors) {
							main.execute(() -> refused(made[0], errors));
						}
					});
			ran = true;
			host.run(ready.preparation(), tuning);
		} else if (result instanceof TunedPreparation.Constant constant) {
			state = State.MUST_REBUILD;
			listener.onMustRebuild(List.of(new ProjectDiagnostic(
					ProjectDiagnostic.Severity.WARNING,
					"GLSL needs a constant here (" + where(constant.kind()) +
							"): the number is built each time",
					path, line())));
		} else if (result instanceof TunedPreparation.Failed failed) {
			state = State.FAILED;
			listener.onFailed(failed.errors());
		}
	}

	private void live(@NonNull Tuning tuning) {
		if (state == State.STARTING && tuning == this.tuning) {
			state = State.LIVE;
			listener.onLive();
		}
	}

	private void refused(@NonNull Tuning tuning,
			@NonNull List<ProjectDiagnostic> errors) {
		if (state == State.STARTING && tuning == this.tuning) {
			state = State.MUST_REBUILD;
			listener.onMustRebuild(errors);
		}
	}

	// The 1-based line of the number.
	private int line() {
		String text = host.text(path);
		int line = 1;
		for (int i = 0; text != null && i < Math.min(start, text.length());
				++i) {
			line += text.charAt(i) == '\n' ? 1 : 0;
		}
		return line;
	}

	@NonNull
	private static String where(@NonNull ConstantContext.Kind kind) {
		return switch (kind) {
			case DIRECTIVE -> "a directive";
			case LAYOUT -> "a layout";
			case ARRAY -> "an array size or index";
			case LOOP -> "a for loop of GLSL ES 1.00";
			case CASE -> "a case label";
			case CONST -> "a const";
			case GLOBAL -> "the initializer of a global";
		};
	}
}
