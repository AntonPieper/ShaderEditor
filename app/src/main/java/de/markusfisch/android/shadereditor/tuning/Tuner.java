package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * Where the tuning sessions of one screen start: one at a time, on the
 * screen's host. Main thread.
 *
 * What the screen that keeps it must do:
 * <ul>
 * <li>End the open session (Done or Revert) before it begins another;
 * begin() refuses while one is open.</li>
 * <li>End the open session before the text can change otherwise than
 * through it, or the screen runs it itself: before the editor takes
 * input again, before a file of the project is renamed, moved or
 * deleted, when the project tells that its files changed, as a refresh
 * may read one again, and before Run. A run of the text replaces the
 * session's build: the session would set uniforms the build that runs
 * no longer has.</li>
 * <li>Send Undo and Redo to the open session while it is open.</li>
 * <li>Not start the run a change in {@link TuningSession.Host#edit}
 * would start: the session's own builds are that run.</li>
 * <li>Call destroy() when the screen goes.</li>
 * </ul>
 */
public final class Tuner {
	@NonNull
	private final TuningSession.Host host;
	@NonNull
	private final Executor main;
	@NonNull
	private final Executor worker;
	@Nullable
	private TuningSession open;

	/**
	 * main runs on the main thread what the engine tells on the GL
	 * thread; worker reads the whole text for a build, off it.
	 */
	public Tuner(@NonNull TuningSession.Host host, @NonNull Executor main,
			@NonNull Executor worker) {
		this.host = host;
		this.main = main;
		this.worker = worker;
	}

	/**
	 * Tune place, a number of document, which holds the text of path;
	 * listener hears what the bar must show. Throws while a session is
	 * open.
	 */
	@NonNull
	public TuningSession begin(@NonNull Document document,
			@NonNull ProjectPath path, @NonNull TunedPlace place,
			@NonNull TuningSession.Listener listener) {
		if (open() != null) {
			throw new IllegalStateException(
					"a tuning session is open: end it first");
		}
		return open = TuningSession.begin(host, main, worker, document, path,
				place, listener);
	}

	/** The session that has not ended, or null. A failed one has. */
	@Nullable
	public TuningSession open() {
		if (open != null && !open.isOpen()) {
			open = null;
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
