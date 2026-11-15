package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * Where the tuning sessions of one screen start: one at a time, on the
 * screen's host. Main thread.
 *
 * What the screen that keeps it must do:
 * <ul>
 * <li>End the open session (Done or Revert) before it begins another;
 * begin() refuses while one is open.</li>
 * <li>End the open session before the text can change or the screen
 * runs it itself: before the editor takes input again, before a file
 * of the project is renamed, moved or deleted, when the project tells
 * that its files changed, as a refresh may read one again, and before
 * Run. OpenProject drops a build of a text that changed meanwhile, and
 * a run of the text replaces the session's build: the session would
 * wait for its build, or set a uniform the build that runs no longer
 * has.</li>
 * <li>Write the number back in {@link TuningSession.Host#write} as one
 * step of undo, and not start the run that change would start: the
 * session's own build of the text is that run.</li>
 * <li>Call destroy() when the screen goes.</li>
 * </ul>
 */
public final class Tuner {
	@NonNull
	private final TuningSession.Host host;
	@NonNull
	private final Executor main;
	@Nullable
	private TuningSession open;

	/**
	 * main runs on the main thread what the engine tells on the GL
	 * thread.
	 */
	public Tuner(@NonNull TuningSession.Host host, @NonNull Executor main) {
		this.host = host;
		this.main = main;
	}

	/**
	 * Tune the number from start to end of the text of path, which must
	 * be one of type; listener hears what became of it. Throws while a
	 * session is open.
	 */
	@NonNull
	public TuningSession begin(@NonNull ProjectPath path, int start, int end,
			@NonNull Tuning.Type type,
			@NonNull TuningSession.Listener listener) {
		if (open() != null) {
			throw new IllegalStateException(
					"a tuning session is open: end it first");
		}
		return open = TuningSession.begin(host, main, path, start, end, type,
				listener);
	}

	/** The session that has not ended, or null. A failed one has. */
	@Nullable
	public TuningSession open() {
		if (open != null) {
			var state = open.state();
			if (state == TuningSession.State.ENDED ||
					state == TuningSession.State.FAILED) {
				open = null;
			}
		}
		return open;
	}

	/**
	 * The screen goes, and the engine with it: the open session ends
	 * without writing or building anything, and nothing it waits for is
	 * heard anymore. The documents never held its uniform.
	 */
	public void destroy() {
		if (open != null) {
			open.drop();
			open = null;
		}
	}
}
