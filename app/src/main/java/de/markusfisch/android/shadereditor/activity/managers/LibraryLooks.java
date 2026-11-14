package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.graphics.OldPlaceholder;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.Previews;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;

/**
 * What the library keeps of its files besides their text, as the
 * database kept it of its shaders: the picture of a file, the quality
 * it runs at, and whether the wallpaper shows it. A file that runs
 * without a picture gets one of its first frame, and a file saved on
 * request one of what it draws then. Files of other folders keep none
 * of it. Call it on the main thread; nothing it hears after it was
 * destroyed does anything.
 */
public final class LibraryLooks {
	/** Gets the quality to run at. */
	public interface Quality {
		void run(float quality);
	}

	private final Context context;
	private final Projects projects;
	private final ShaderViewManager view;
	private final Runnable kept;
	private final Consumer<String> told;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService reading = Executors.newSingleThreadExecutor();
	@Nullable
	private String library;
	// The file of the library the wallpaper shows, once read.
	@Nullable
	private ProjectPath wallpaper;
	private int runs;
	private boolean destroyed;

	/**
	 * @param kept called when something was kept that a list shows
	 * @param told called with why something could not be kept
	 */
	public LibraryLooks(@NonNull Context context, @NonNull Projects projects,
			@NonNull ShaderViewManager view, @NonNull Runnable kept,
			@NonNull Consumer<String> told) {
		this.context = context.getApplicationContext();
		this.projects = projects;
		this.view = view;
		this.kept = kept;
		this.told = told;
	}

	/** Takes the session of the library; files of others get none. */
	public void setLibrary(@NonNull String key) {
		library = key;
		findWallpaper();
	}

	/**
	 * Hand run the quality to run running of the folder key at: the one
	 * the library keeps for it, else fallback. Of several asked, only
	 * the last is answered.
	 */
	public void quality(@Nullable String key, @Nullable ProjectPath running,
			float fallback, @NonNull Quality run) {
		int asked = ++runs;
		if (!inLibrary(key) || running == null) {
			run.run(fallback);
			return;
		}
		execute(() -> {
			float quality = projects.library().settings().quality(running);
			handler.post(() -> {
				if (!destroyed && asked == runs) {
					run.run(quality);
				}
			});
		});
	}

	/** Keep quality for running of the folder key, if in the library. */
	public void keepQuality(@Nullable String key,
			@Nullable ProjectPath running, float quality) {
		if (inLibrary(key) && running != null) {
			projects.setQuality(running, quality, this::tell);
		}
	}

	/** The file at path of the folder key runs now. */
	public void ran(@Nullable String key, @Nullable ProjectPath path) {
		if (path == null || !inLibrary(key)) {
			return;
		}
		execute(() -> {
			Previews.Picture picture = projects.library().previews()
					.get(path);
			// The database's grey square is no picture either.
			boolean missing = picture == null ||
					OldPlaceholder.real(context, picture.png()) == null;
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

	/** Return whether the wallpaper shows running of the folder key. */
	public boolean isWallpaper(@Nullable String key,
			@Nullable ProjectPath running) {
		return inLibrary(key) && running != null && running.equals(wallpaper);
	}

	/**
	 * Make what project of the folder key runs, with the text in its
	 * editor, what the wallpaper shows; done gets why it does not, or
	 * null when it does.
	 *
	 * @param fallback the quality where the library keeps none
	 */
	public void setWallpaper(@Nullable String key,
			@NonNull OpenProject project, float fallback,
			@NonNull FileTexts.Callback<String> done) {
		ProjectPath entry = project.running();
		if (entry == null) {
			return;
		}
		boolean ofLibrary = inLibrary(key);
		project.prepare(UniformTable.builtins(), build -> {
			Preparation preparation = build != null
					? build.preparation()
					: null;
			quality(key, entry, fallback, quality -> projects.setWallpaper(
					preparation, entry, quality, ofLibrary ? entry : null,
					problem -> {
						if (destroyed) {
							return;
						}
						if (problem == null) {
							wallpaper = ofLibrary ? entry : null;
							showsInstalled();
							kept.run();
						}
						done.done(problem);
					}));
		});
	}

	/**
	 * Make a shader of the database what the wallpaper shows: the
	 * snapshot a file installed, which the service would show first,
	 * is removed, then set sets the shader. done gets why it was not,
	 * or null.
	 */
	public void setDatabaseWallpaper(@NonNull Runnable set,
			@NonNull FileTexts.Callback<String> done) {
		projects.clearWallpaper(problem -> {
			if (destroyed) {
				return;
			}
			if (problem == null) {
				set.run();
				wallpaper = null;
				kept.run();
			}
			done.done(problem);
		});
	}

	public void destroy() {
		destroyed = true;
		handler.removeCallbacksAndMessages(null);
		reading.shutdownNow();
	}

	/**
	 * Have the wallpaper service look again: it shows what was installed
	 * last while no shader of the database is set.
	 */
	public static void showsInstalled() {
		if (ShaderEditorApp.preferences.getWallpaperShader() == 0) {
			// Only a change is heard.
			ShaderEditorApp.preferences.setWallpaperShader(-1);
		}
		ShaderEditorApp.preferences.setWallpaperShader(0);
	}

	private boolean inLibrary(@Nullable String key) {
		return key != null && key.equals(library);
	}

	private void findWallpaper() {
		long id = ShaderEditorApp.preferences.getWallpaperShader();
		execute(() -> {
			ProjectPath path = projects.library().wallpaperFile(
					projects.wallpaper().current(), id);
			handler.post(() -> wallpaper = path);
		});
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
