package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.resource.Resources;
import de.markusfisch.android.shadereditor.widget.ShaderToySource;

/**
 * Makes a shader in the library from a text the app did not read from
 * a file of it: an example, text another app shared, a copy of a
 * shader that is still in the database. The text is converted as the
 * move converts, with its textures. Call it on the main thread;
 * nothing it hears after it was destroyed does anything.
 */
public final class LibraryAdd {
	/** What becomes of a shader asked for. */
	public interface Listener {
		/** It is the file at path of the library, known by key. */
		void onAdded(@NonNull String key, @NonNull ProjectPath path);

		/** It was not made, for message. */
		void onFailed(@NonNull String message);
	}

	private final Context context;
	private final Projects projects;
	private final DataSource database;
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService reading =
			Executors.newSingleThreadExecutor();
	private boolean destroyed;

	public LibraryAdd(@NonNull Context context, @NonNull Projects projects,
			@NonNull DataSource database, @NonNull Listener listener) {
		this.context = context.getApplicationContext();
		this.projects = projects;
		this.database = database;
		this.listener = listener;
	}

	/** Make a copy of a bundled example, with its picture. */
	public void addExample(@NonNull String title, int text, int picture,
			float quality) {
		try {
			reading.execute(() -> {
				String source;
				try {
					source = Resources.loadRawResource(context, text);
				} catch (IOException e) {
					String problem = e.getMessage();
					handler.post(() -> failed(problem));
					return;
				}
				byte[] png = Resources.loadBitmapResource(context, picture);
				handler.post(() -> add(title, source, quality, png));
			});
		} catch (RejectedExecutionException e) {
			// Destroyed.
		}
	}

	/**
	 * Make a shader of text another app shared or opened, named after
	 * name when there is one. ShaderToy's form is converted, as it was
	 * when the text came into a database shader.
	 */
	public void addShared(@Nullable String name, @NonNull String text) {
		String converted = ShaderToySource.convert(text);
		add(name, converted != null ? converted : text, 1f, null);
	}

	/**
	 * Make a shader of text, titled title, null for none, with quality
	 * and picture, a PNG or null.
	 */
	public void add(@Nullable String title, @NonNull String text,
			float quality, @Nullable byte[] picture) {
		projects.addShader(database, UniformTable.builtins().names(), title,
				context.getString(R.string.nav_untitled), text, quality,
				picture, made -> {
					if (destroyed) {
						return;
					} else if (made.path() == null) {
						failed(made.problem());
						return;
					}
					ProjectPath path = made.path();
					if (made.problem() != null) {
						// The file is there; its quality or picture not.
						listener.onFailed(context.getString(
								R.string.library_not_kept, made.problem()));
					}
					// The library learns of the file as of one made
					// elsewhere, then shows it.
					projects.openLibrary(opened -> {
						OpenProject project = opened.project();
						String key = opened.id();
						if (destroyed) {
							return;
						} else if (project == null || key == null) {
							failed(opened.problem());
							return;
						}
						project.refresh(reread -> {
							if (!destroyed) {
								listener.onAdded(key, path);
							}
						});
					});
				});
	}

	public void destroy() {
		destroyed = true;
		handler.removeCallbacksAndMessages(null);
		reading.shutdownNow();
	}

	private void failed(@Nullable String problem) {
		if (!destroyed) {
			listener.onFailed(context.getString(R.string.library_not_added,
					problem != null ? problem : ""));
		}
	}
}
