package de.markusfisch.android.shadereditor.activity.managers;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;

/**
 * What the library keeps of its files besides their text, as the
 * database kept it of its shaders: the picture of a file. A file that
 * runs without a picture gets one of its first frame, and a file saved
 * on request one of what it draws then. Files of other folders keep
 * none. Call it on the main thread; nothing it hears after it was
 * destroyed does anything.
 */
public final class LibraryLooks {
	private final Projects projects;
	private final ShaderViewManager view;
	private final Runnable kept;
	private final Consumer<String> told;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService reading = Executors.newSingleThreadExecutor();
	@Nullable
	private String library;
	private boolean destroyed;

	/**
	 * @param kept called when something was kept that a list shows
	 * @param told called with why something could not be kept
	 */
	public LibraryLooks(@NonNull Projects projects,
			@NonNull ShaderViewManager view, @NonNull Runnable kept,
			@NonNull Consumer<String> told) {
		this.projects = projects;
		this.view = view;
		this.kept = kept;
		this.told = told;
	}

	/** Takes the session of the library; files of others get none. */
	public void setLibrary(@NonNull String key) {
		library = key;
	}

	/** The file at path of the folder key runs now. */
	public void ran(@Nullable String key, @Nullable ProjectPath path) {
		if (path == null || !inLibrary(key)) {
			return;
		}
		execute(() -> {
			boolean missing = projects.library().previews()
					.get(path) == null;
			handler.post(() -> {
				if (missing && !destroyed) {
					capture(path);
				}
			});
		});
	}

	/** The file at path of the folder key, which runs, was saved. */
	public void saved(@Nullable String key, @Nullable ProjectPath path) {
		if (path != null && inLibrary(key)) {
			capture(path);
		}
	}

	public void destroy() {
		destroyed = true;
		handler.removeCallbacksAndMessages(null);
		reading.shutdownNow();
	}

	private boolean inLibrary(@Nullable String key) {
		return key != null && key.equals(library);
	}

	// The preview answers after its next complete frame.
	private void capture(@NonNull ProjectPath path) {
		view.requestThumbnail(png -> {
			if (png == null) {
				return;
			}
			handler.post(() -> {
				if (!destroyed) {
					projects.setPreview(path, null, png, problem -> {
						tell(problem);
						if (problem == null && !destroyed) {
							kept.run();
						}
					});
				}
			});
		});
	}

	private void tell(@Nullable String problem) {
		if (problem != null && !destroyed) {
			told.accept(problem);
		}
	}

	private void execute(@NonNull Runnable task) {
		try {
			reading.execute(task);
		} catch (RejectedExecutionException e) {
			// Destroyed.
		}
	}
}
