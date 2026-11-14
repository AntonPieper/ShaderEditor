package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.LibraryMove;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;

/**
 * What a start of the main screen does in project mode: move the
 * shaders of the database that are not files yet into the library,
 * then open the library and show the file it showed last. Nothing is
 * asked. Call it on the main thread; nothing it hears after the screen
 * is gone does anything.
 */
public final class LibraryStart {
	/** What the screen shows of the start. */
	public interface Listener {
		/** The move takes long; say so. */
		void onMoving();

		/** The library is open, as the session key. */
		void onLibrary(@NonNull String key);

		/** Show the file at path of the library, known by key. */
		void onShow(@NonNull String key, @NonNull ProjectPath path);

		/** The library has no file to show. */
		void onEmpty(@NonNull String key);

		/** Tell once what the move did, as message. */
		void onMoved(@NonNull String message);

		/** The shaders of the database that could not be moved. */
		void onNotMoved(@NonNull List<LibraryMove.Failed> failed);
	}

	// A move that takes less than this says nothing.
	private static final long MOVING_MILLIS = 500;

	private final Activity activity;
	private final Projects projects;
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService listing =
			Executors.newSingleThreadExecutor();
	private boolean destroyed;

	public LibraryStart(@NonNull Activity activity, @NonNull Projects projects,
			@NonNull Listener listener) {
		this.activity = activity;
		this.projects = projects;
		this.listener = listener;
	}

	/**
	 * Move what is left, then open the library.
	 *
	 * @param show whether to show the file it showed last; a screen
	 *             made again shows the file it showed before
	 */
	public void start(@NonNull DataSource database, boolean show) {
		Runnable moving = () -> {
			if (!destroyed) {
				listener.onMoving();
			}
		};
		if (show) {
			handler.postDelayed(moving, MOVING_MILLIS);
		}
		projects.moveLibrary(database, UniformTable.builtins().names(),
				activity.getString(R.string.nav_untitled), chosen(),
				result -> {
					handler.removeCallbacks(moving);
					if (destroyed) {
						return;
					}
					String told = describe(activity, result.problem(),
							result.moved());
					if (told != null) {
						listener.onMoved(told);
					}
					listener.onNotMoved(result.failed());
					open(show);
				});
	}

	/**
	 * Put the library aside and move every shader of the database into
	 * a new one, then show that. Nothing may hold the library open.
	 */
	public void redo(@NonNull DataSource database) {
		projects.redoLibraryMove(database, UniformTable.builtins().names(),
				activity.getString(R.string.nav_untitled), chosen(),
				redone -> {
					if (destroyed) {
						return;
					}
					LibraryMove.Result result = redone.moved();
					String told = describe(activity, redone.problem() != null
									? redone.problem()
									: result != null ? result.problem() : null,
							result != null ? result.moved() : List.of());
					if (told != null) {
						listener.onMoved(told);
					}
					listener.onNotMoved(result != null
							? result.failed()
							: List.of());
					open(true);
				});
	}

	public void destroy() {
		destroyed = true;
		handler.removeCallbacksAndMessages(null);
		listing.shutdownNow();
	}

	@NonNull
	private static LibraryMove.Chosen chosen() {
		return new LibraryMove.Chosen(
				ShaderEditorApp.preferences.getDefaultNewShader(),
				ShaderEditorApp.preferences.getLastOpenedShader());
	}

	private void open(boolean show) {
		projects.openLibrary(opened -> {
			if (destroyed) {
				return;
			} else if (show) {
				show(opened);
			} else if (opened.id() != null) {
				listener.onLibrary(opened.id());
			}
		});
	}

	// What the move did that is told: how many became files, or why
	// it could not begin. Shaders that failed are listed in the
	// library, not told.
	@Nullable
	public static String describe(@NonNull Context context,
			@Nullable String problem, @NonNull List<LibraryMove.Moved> files) {
		if (problem != null) {
			return context.getString(R.string.library_not_moved, problem);
		}
		int moved = files.size();
		return moved > 0
				? context.getResources().getQuantityString(
						R.plurals.library_moved, moved, moved)
				: null;
	}

	// The file the library showed last, else its newest file.
	private void show(@NonNull Projects.Opened opened) {
		OpenProject project = opened.project();
		String key = opened.id();
		if (project == null || key == null) {
			return;
		}
		listener.onLibrary(key);
		ProjectPath shown = project.shown();
		if (shown != null) {
			listener.onShow(key, shown);
			return;
		}
		try {
			listing.execute(() -> {
				ProjectPath newest = newest(project.list(null));
				handler.post(() -> {
					if (destroyed) {
						return;
					} else if (newest != null) {
						listener.onShow(key, newest);
					} else {
						listener.onEmpty(key);
					}
				});
			});
		} catch (RejectedExecutionException e) {
			// Destroyed.
		}
	}

	@Nullable
	private static ProjectPath newest(@NonNull OpenProject.Folder folder) {
		OpenProject.Item newest = null;
		for (OpenProject.Item item : folder.items()) {
			if (!item.folder() && (newest == null ||
					item.modified() > newest.modified())) {
				newest = item;
			}
		}
		return newest != null ? newest.path() : null;
	}
}
