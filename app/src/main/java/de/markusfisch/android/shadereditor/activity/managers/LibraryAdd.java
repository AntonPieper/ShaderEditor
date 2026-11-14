package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.widget.ShaderToySource;

/**
 * Makes a shader in the library from a text the app did not read from
 * a file of it: text another app shared, a copy of a shader that is
 * still in the database, which are converted as the move converts,
 * with their textures, and copies of files of a read-only folder,
 * such as an example. Call it on the main thread; nothing it hears
 * after it was destroyed does anything.
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
	private boolean destroyed;

	public LibraryAdd(@NonNull Context context, @NonNull Projects projects,
			@NonNull DataSource database, @NonNull Listener listener) {
		this.context = context.getApplicationContext();
		this.projects = projects;
		this.database = database;
		this.listener = listener;
	}

	/**
	 * Save a copy of paths of project, a read-only folder, in the
	 * library, where entry is the file to show; see
	 * {@link Projects#saveCopy}. The unsaved text of project goes with
	 * the copy: project is read from storage again.
	 */
	public void saveCopy(@NonNull OpenProject project,
			@NonNull ProjectPath entry, @NonNull List<ProjectPath> paths) {
		projects.saveCopy(project, entry, paths, made -> {
			if (destroyed) {
				return;
			} else if (made.path() == null) {
				listener.onFailed(context.getString(R.string.copy_not_saved,
						made.problem()));
				return;
			}
			for (ProjectPath unsaved : project.unsaved()) {
				project.reload(unsaved, problem -> {
				});
			}
			show(made.path());
		});
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
					if (made.problem() != null) {
						// The file is there; its quality or picture not.
						listener.onFailed(context.getString(
								R.string.library_not_kept, made.problem()));
					}
					show(made.path());
				});
	}

	// The library learns of the file as of one made elsewhere, then
	// shows it.
	private void show(@NonNull ProjectPath path) {
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
	}

	public void destroy() {
		destroyed = true;
	}

	private void failed(@Nullable String problem) {
		if (!destroyed) {
			listener.onFailed(context.getString(R.string.library_not_added,
					problem != null ? problem : ""));
		}
	}
}
