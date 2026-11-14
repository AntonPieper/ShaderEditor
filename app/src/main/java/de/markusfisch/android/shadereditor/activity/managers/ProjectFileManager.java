package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * Opens files of folders in the editor and keeps the one open: its
 * project stays open while it is, and the editor shows the project's
 * document of it. Call it on the main thread.
 */
public final class ProjectFileManager {
	public interface Listener extends ProjectFile.Listener {
		/** file is in the editor now, in place of what was there. */
		void onShown(@NonNull ProjectFile file);
	}

	@NonNull
	private final AppCompatActivity activity;
	@NonNull
	private final EditorFragment editor;
	@NonNull
	private final ProjectHolds holds;
	@NonNull
	private final Listener listener;
	@Nullable
	private ProjectFile file;
	@Nullable
	private String key;
	// Counts the files asked for; an answer for one before is void.
	private int asked;
	private boolean destroyed;

	public ProjectFileManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editor, @NonNull ProjectHolds holds,
			@NonNull Listener listener) {
		this.activity = activity;
		this.editor = editor;
		this.holds = holds;
		this.listener = listener;
	}

	/** Return the file in the editor, or null. */
	@Nullable
	public ProjectFile file() {
		return file;
	}

	/** Return the key of the folder of the file in the editor, or null. */
	@Nullable
	public String key() {
		return file != null ? key : null;
	}

	/**
	 * Open the file at path of the folder or single file opened before
	 * with key; return whether the drawer closes.
	 * Only the new editor can show it; the old one says so.
	 */
	public boolean open(@NonNull String key, @NonNull ProjectPath path,
			boolean single) {
		if (!ShaderEditorApp.preferences.useNewEditor()) {
			listener.onFailed(activity.getString(
					R.string.file_needs_new_editor));
			// The drawer closes all the same, so that this can be read.
			return true;
		}
		int ask = ++asked;
		holds.open(key, opened -> {
			OpenProject project = opened.project();
			if (project == null) {
				failed(ask, path, opened.problem());
				return;
			} else if (ask != asked || destroyed) {
				holds.release(project);
				return;
			}
			project.load(path, ProjectFiles.UNKNOWN, problem -> {
				Document document = problem == null && ask == asked &&
						!destroyed
						? project.show(path)
						: null;
				if (document == null) {
					holds.release(project);
					failed(ask, path, problem);
					return;
				}
				show(key, project, path, document, single);
			});
		});
		return true;
	}

	private void failed(int ask, @NonNull ProjectPath path,
			@Nullable String problem) {
		if (ask != asked || destroyed || problem == null) {
			return;
		}
		listener.onFailed(activity.getString(R.string.file_cannot_open,
				path.toString(), problem));
	}

	private void show(@NonNull String key, @NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Document document,
			boolean single) {
		leave();
		this.key = key;
		ProjectFile shown = new ProjectFile(activity, project, path, single,
				listener);
		file = shown;
		project.setListener(ContextCompat.getMainExecutor(activity),
				listener::onChanged);
		editor.showDocument(document, project.editorState(path));
		listener.onShown(shown);
		String unreadable = project.unreadable(path);
		if (unreadable != null) {
			listener.onFailed(problem(path, unreadable));
		}
	}

	/** Let the file in the editor go, as another item is shown there. */
	public void leave() {
		ProjectFile left = file;
		if (left == null) {
			return;
		}
		file = null;
		OpenProject project = left.getProject();
		project.setEditorState(left.getPath(), editor.getDocumentState());
		project.setListener(null, null);
		holds.release(project);
	}

	public void destroy() {
		leave();
		destroyed = true;
	}

	@NonNull
	private String problem(@NonNull ProjectPath path,
			@NonNull String problem) {
		return activity.getString(R.string.file_problem, path.toString(),
				problem);
	}
}
