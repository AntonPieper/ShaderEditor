package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.editor.Document;
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
 * such as an example. Call it on the main thread. What it hears after
 * it was destroyed goes to the one made after it, of the screen that
 * is made again: the shader asked for is shown and told there.
 */
public final class LibraryAdd {
	/** What becomes of a shader asked for. */
	public interface Listener {
		/** It is the file at path of the library, known by key. */
		void onAdded(@NonNull String key, @NonNull ProjectPath path);

		/** Tell message: it was not made, or what to know of it. */
		void onFailed(@NonNull String message);
	}

	private final Context context;
	// The screen, for a dialog.
	private final Context screen;
	private final Projects projects;
	private final DataSource database;
	private final Listener listener;
	private boolean destroyed;
	// The one of the screen in front, and what became of a shader
	// while there was none: the file made, the files a copy kept, or
	// why there is none.
	@Nullable
	private static LibraryAdd current;
	// How many shaders asked for are not made yet, and whether one of
	// them is a copy: across screens, as they are made behind them.
	private static int asking;
	private static boolean copying;
	@Nullable
	private static ProjectPath lateAdded;
	@Nullable
	private static List<ProjectPath> lateKept;
	@Nullable
	private static String lateFailed;

	public LibraryAdd(@NonNull Context context, @NonNull Projects projects,
			@NonNull DataSource database, @NonNull Listener listener) {
		this.context = context.getApplicationContext();
		this.screen = context;
		this.projects = projects;
		this.database = database;
		this.listener = listener;
		current = this;
	}

	/**
	 * Return whether a shader the screen before this one asked for is
	 * still to be shown: the start then shows no other file.
	 */
	public boolean awaits() {
		return asking > 0 || lateAdded != null;
	}

	/**
	 * Hear what became of a shader the screen before this one asked
	 * for, once this screen can show it.
	 */
	public void resume() {
		String failed = lateFailed;
		ProjectPath added = lateAdded;
		List<ProjectPath> kept = lateKept;
		lateFailed = null;
		lateAdded = null;
		lateKept = null;
		if (failed != null) {
			listener.onFailed(failed);
		}
		if (added != null) {
			show(added);
		}
		if (kept != null) {
			tellKept(kept);
		}
	}

	/**
	 * Save a copy of paths of project, a read-only folder, in the
	 * library, where entry is the file to show; see
	 * {@link Projects#saveCopy}. The unsaved text of a file that went
	 * into the copy goes with it: that file is read from storage again,
	 * unless it was edited since. Files the library has already with
	 * other bytes are named. While a copy is made, another is not.
	 */
	public void saveCopy(@NonNull OpenProject project,
			@NonNull ProjectPath entry, @NonNull List<ProjectPath> paths) {
		if (copying) {
			return;
		}
		copying = true;
		++asking;
		Map<ProjectPath, Integer> revisions = new HashMap<>();
		for (ProjectPath path : paths) {
			Document document = project.document(path);
			if (document != null) {
				revisions.put(path, document.revision());
			}
		}
		projects.saveCopy(project, entry, paths, made -> {
			copying = false;
			--asking;
			if (made.path() == null) {
				tell(context.getString(R.string.copy_not_saved,
						made.problem()));
				return;
			}
			Set<ProjectPath> unsaved = project.unsaved();
			for (ProjectPath copied : made.copied()) {
				Document document = project.document(copied);
				Integer revision = revisions.get(copied);
				if (unsaved.contains(copied) && document != null &&
						revision != null && document.revision() == revision) {
					project.reload(copied, problem -> {
					});
				}
			}
			added(made.path());
			if (!made.kept().isEmpty()) {
				kept(made.kept());
			}
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
		++asking;
		projects.addShader(database, UniformTable.builtins().names(), title,
				context.getString(R.string.nav_untitled), text, quality,
				picture, made -> {
					--asking;
					if (made.path() == null) {
						failed(made.problem());
						return;
					}
					if (made.problem() != null) {
						// The file is there; its quality or picture not.
						tell(context.getString(
								R.string.library_not_kept, made.problem()));
					}
					added(made.path());
				});
	}

	// The library learns of the file as of one made elsewhere, then
	// shows it.
	private void show(@NonNull ProjectPath path) {
		projects.openLibrary(opened -> {
			OpenProject project = opened.project();
			String key = opened.id();
			if (destroyed) {
				added(path);
				return;
			} else if (project == null || key == null) {
				failed(opened.problem());
				return;
			}
			project.refresh(reread -> {
				if (destroyed) {
					added(path);
				} else {
					listener.onAdded(key, path);
				}
			});
		});
	}

	public void destroy() {
		destroyed = true;
		if (current == this) {
			current = null;
		}
	}

	// The file was made: the screen in front shows it, or the next.
	private void added(@NonNull ProjectPath path) {
		LibraryAdd front = destroyed ? current : this;
		if (front != null) {
			front.show(path);
		} else {
			lateAdded = path;
		}
	}

	// A dialog, not a bottom message: the copy may fail to compile
	// against a kept file right away, and that message must not hide
	// why.
	private void tellKept(@NonNull List<ProjectPath> kept) {
		StringBuilder files = new StringBuilder();
		for (ProjectPath path : kept) {
			files.append("\n").append(path);
		}
		new MaterialAlertDialogBuilder(screen)
				.setTitle(R.string.copy_saved)
				.setMessage(screen.getString(R.string.copy_kept, files))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	// The files a copy kept are named by the screen in front, or the
	// next.
	private void kept(@NonNull List<ProjectPath> kept) {
		LibraryAdd front = destroyed ? current : this;
		if (front != null) {
			front.tellKept(kept);
		} else {
			lateKept = kept;
		}
	}

	private void failed(@Nullable String problem) {
		tell(context.getString(R.string.library_not_added,
				problem != null ? problem : ""));
	}

	// Never dropped: the screen in front says it, or the next.
	private void tell(@NonNull String message) {
		LibraryAdd front = destroyed ? current : this;
		if (front != null) {
			front.listener.onFailed(message);
		} else {
			lateFailed = message;
		}
	}
}
