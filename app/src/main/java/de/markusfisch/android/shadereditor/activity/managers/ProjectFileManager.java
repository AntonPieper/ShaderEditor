package de.markusfisch.android.shadereditor.activity.managers;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.navigation.Moves;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Tap;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * Opens files of folders in the editor and keeps the one open: its
 * project stays open while it is, the editor shows the project's
 * document of it, and its unsaved text is journaled when the app goes
 * to the background and checked against storage when it returns. A
 * tap on a file runs it when it can run and what runs does not use it
 * already. Call it on the main thread.
 */
public final class ProjectFileManager {
	public interface Listener extends ProjectFile.Listener {
		/** file is in the editor now, in place of what was there. */
		void onShown(@NonNull ProjectFile file);

		/**
		 * The file in the editor was deleted, or the one open before
		 * the screen was made again is gone: show something else.
		 */
		void onGone();

		/**
		 * Files of the folder opened before with key were renamed or
		 * deleted.
		 */
		void onMoved(@NonNull String key);
	}

	/** Runs what a project runs. */
	public interface Runner {
		/** Run preparation at quality; null when nothing runs. */
		void run(@Nullable Preparation preparation, float quality);
	}

	private static final String KEY = "project_file_key";
	private static final String PATH = "project_file_path";
	private static final String SINGLE = "project_file_single";

	@NonNull
	private final AppCompatActivity activity;
	@NonNull
	private final EditorFragment editor;
	@NonNull
	private final ProjectHolds holds;
	@NonNull
	private final Listener listener;
	@NonNull
	private final Runner runner;
	@Nullable
	private ProjectFile file;
	// The build of what runs asked for last, of which project and file.
	@Nullable
	private OpenProject.Build build;
	@Nullable
	private OpenProject built;
	@Nullable
	private ProjectPath builtEntry;
	// The line to go to once the file asked for last is shown, or 0.
	private int line;
	@Nullable
	private String key;
	// The file tapped last until it is known whether it can run.
	@Nullable
	private ProjectPath deciding;
	// Counts the files asked for; an answer for one before is void.
	private int asked;
	// The number of the last rename or deletion of the project heard of.
	private int heard;
	// Whether the last build delivered is of paths renamed since.
	private boolean stale;
	private boolean destroyed;

	public ProjectFileManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editor, @NonNull ProjectHolds holds,
			@NonNull Listener listener, @NonNull Runner runner) {
		this.activity = activity;
		this.runner = runner;
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
	 * with key, as a tap on it does; return whether the drawer closes.
	 * Only the new editor can show it; the old one says so.
	 */
	public boolean open(@NonNull String key, @NonNull ProjectPath path,
			boolean single) {
		return open(key, path, single, true);
	}

	/**
	 * Show the file at path of the project of the file in the editor at
	 * line, from 1, as an error does that the list leads to; what runs
	 * stays.
	 */
	public void openAt(@NonNull ProjectPath path, int line) {
		ProjectFile shown = file;
		if (shown == null) {
			return;
		} else if (path.equals(shown.getPath())) {
			editor.navigateToLine(line);
			return;
		}
		if (open(key, path, shown.isSingle(), false)) {
			this.line = line;
		}
	}

	private boolean open(@NonNull String key, @NonNull ProjectPath path,
			boolean single, boolean tapped) {
		if (!ShaderEditorApp.preferences.useNewEditor()) {
			listener.onFailed(activity.getString(
					R.string.file_needs_new_editor));
			// A tap closes the drawer all the same, so that this can be
			// read.
			return tapped;
		}
		int ask = ++asked;
		line = 0;
		holds.open(key, opened -> {
			OpenProject project = opened.project();
			if (project == null) {
				failed(ask, tapped, path, opened.problem());
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
					failed(ask, tapped, path, problem);
					return;
				}
				show(key, project, path, document, single, tapped);
			});
		});
		return true;
	}

	private void failed(int ask, boolean tapped, @NonNull ProjectPath path,
			@Nullable String problem) {
		if (ask != asked || destroyed || problem == null) {
			return;
		}
		listener.onFailed(activity.getString(R.string.file_cannot_open,
				path.toString(), problem));
		if (!tapped) {
			listener.onGone();
		}
	}

	private void show(@NonNull String key, @NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Document document,
			boolean single, boolean tapped) {
		leave();
		this.key = key;
		ProjectFile shown = new ProjectFile(activity, project, path, single,
				listener);
		file = shown;
		heard = last(project.events(0));
		project.setListener(ContextCompat.getMainExecutor(activity),
				this::changed);
		editor.showDocument(document, project.editorState(path));
		listener.onShown(shown);
		String unreadable = project.unreadable(path);
		if (unreadable != null) {
			listener.onFailed(problem(path, unreadable));
		}
		if (line > 0) {
			editor.navigateToLine(line);
			line = 0;
		}
		deciding = tapped ? path : null;
		decide();
		if (project != built ||
				!Objects.equals(project.running(), builtEntry)) {
			run();
		}
	}

	/**
	 * Run what the project of the file in the editor runs, prepared
	 * from the text its documents have now.
	 */
	public void run() {
		ProjectFile shown = file;
		if (shown == null) {
			return;
		}
		OpenProject project = shown.getProject();
		built = project;
		builtEntry = project.running();
		project.prepare(UniformTable.builtins(), prepared -> {
			ProjectFile now = file;
			if (now == null || now.getProject() != project) {
				return;
			}
			build = prepared;
			stale = false;
			// Nothing runs, as for a folder without a shader: a picture
			// or errors left from before are not this project's.
			runner.run(prepared.preparation(), now.getQuality());
		});
	}

	/** Keep what the build run last reported, by file. */
	public void onDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics) {
		if (file != null && build != null &&
				file.getProject() == built) {
			file.getProject().setDiagnostics(build, diagnostics);
		}
	}

	/** Let the file in the editor go, as another item is shown there. */
	public void leave() {
		ProjectFile left = file;
		if (left == null) {
			return;
		}
		file = null;
		deciding = null;
		stale = false;
		build = null;
		built = null;
		builtEntry = null;
		OpenProject project = left.getProject();
		project.setEditorState(left.getPath(), editor.getDocumentState());
		project.setListener(null, null);
		holds.release(project);
	}

	/** Look for changes made elsewhere, when the app is in front again. */
	public void onStart() {
		ProjectFile checked = file;
		if (checked == null) {
			return;
		}
		OpenProject project = checked.getProject();
		project.refresh(reread -> {
			if (file != checked) {
				return;
			}
			// Conflicts have no screen of their own yet.
			for (Map.Entry<ProjectPath, String> conflict :
					project.conflicts().entrySet()) {
				listener.onFailed(problem(conflict.getKey(),
						conflict.getValue()));
			}
			String unprotected = project.unprotected();
			if (unprotected != null) {
				listener.onFailed(unprotected);
			}
			run();
			listener.onChanged();
		});
	}

	/** Keep everything typed, when the app goes to the background. */
	public void onStop() {
		if (file != null) {
			file.getProject().flush(() -> {
			});
		}
	}

	public void saveState(@NonNull Bundle state) {
		ProjectFile saved = file;
		if (saved == null) {
			return;
		}
		saved.getProject().setEditorState(saved.getPath(),
				editor.getDocumentState());
		state.putString(KEY, key);
		state.putString(PATH, saved.getPath().toString());
		state.putBoolean(SINGLE, saved.isSingle());
	}

	/**
	 * Open the file that was in the editor before the screen was made
	 * again; return whether there was one that is being opened.
	 */
	public boolean restoreState(@NonNull Bundle state) {
		String restored = state.getString(KEY);
		String path = state.getString(PATH);
		var parsed = path != null ? ProjectPath.parse(path) : null;
		return restored != null && parsed != null && parsed.isSuccess() &&
				open(restored, parsed.value(), state.getBoolean(SINGLE),
						false);
	}

	public void destroy() {
		leave();
		destroyed = true;
	}

	private void changed() {
		follow();
		decide();
		// Another file was chosen to run, or found to run; or a rename
		// made the last build stale: a build asked for while the project
		// still settles what runs is not delivered, so it is asked for
		// again until one is.
		if (file != null && file.getProject() == built && (stale ||
				!Objects.equals(built.running(), builtEntry))) {
			run();
		}
		listener.onChanged();
	}

	// The file in the editor follows a rename of it or of a folder it
	// is in, with the same document; the last build names old paths,
	// so it is built again until one is delivered. Deleted, it closes,
	// unless it was typed in after the deletion was asked for: then
	// its document stays, as unsaved text of a file that is not there.
	private void follow() {
		ProjectFile shown = file;
		if (shown == null) {
			return;
		}
		OpenProject project = shown.getProject();
		List<OpenProject.Event> events = project.events(heard);
		if (events.isEmpty()) {
			return;
		}
		// Some were missed: everything is looked at again.
		boolean gone = events.get(0).number() > heard + 1;
		heard = last(events);
		String path = shown.getPath().toString();
		boolean renamed = false;
		for (OpenProject.Event event : events) {
			if (event.change() instanceof OpenProject.Renamed rename) {
				renamed = true;
				path = Moves.renamed(path, rename.from().toString(),
						rename.to().toString());
			} else if (event.change() instanceof
					OpenProject.Deleted deleted) {
				gone |= Moves.within(path, deleted.path().toString());
			}
		}
		ProjectPath now = ProjectPath.parse(path).value();
		String moved = key;
		if (gone && project.document(now) == null) {
			leave();
			listener.onGone();
		} else if (!now.equals(shown.getPath())) {
			if (deciding != null && deciding.equals(shown.getPath())) {
				deciding = now;
			}
			file = new ProjectFile(activity, project, now, shown.isSingle(),
					listener);
			file.setQuality(shown.getQuality());
			listener.onShown(file);
		}
		stale |= renamed;
		if (moved != null) {
			listener.onMoved(moved);
		}
	}

	private static int last(@NonNull List<OpenProject.Event> events) {
		return events.isEmpty()
				? 0
				: events.get(events.size() - 1).number();
	}

	// A tap runs a file that can run unless what runs uses it, once it
	// is known whether its text defines main.
	private void decide() {
		ProjectFile tapped = file;
		if (tapped == null || deciding == null ||
				!deciding.equals(tapped.getPath())) {
			return;
		}
		OpenProject project = tapped.getProject();
		OpenProject.Runs runs = project.runs(deciding);
		if (runs == OpenProject.Runs.UNKNOWN) {
			return;
		}
		ProjectPath path = deciding;
		deciding = null;
		boolean inUse = path.equals(project.running()) || project.uses(path);
		if (Tap.of(runs == OpenProject.Runs.YES
				? Row.Kind.SHADER
				: Row.Kind.TEXT, inUse) == Tap.OPEN_AND_RUN) {
			project.runThisFile(path);
		}
	}

	@NonNull
	private String problem(@NonNull ProjectPath path,
			@NonNull String problem) {
		return activity.getString(R.string.file_problem, path.toString(),
				problem);
	}
}
