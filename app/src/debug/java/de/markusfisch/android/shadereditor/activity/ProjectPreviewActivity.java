package de.markusfisch.android.shadereditor.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ProjectSessions;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.provider.UserFilesProvider;
import de.markusfisch.android.shadereditor.widget.ErrorListModal;

/**
 * Development harness, debug builds only; remove it when the project UI
 * lands. Drives one open project through the extras of the intent that
 * starts it, runs what the project runs in the fullscreen preview, lists
 * its diagnostics, and logs its state with the tag "ProjectHarness".
 * Extras, applied in this order:
 * <ul>
 * <li>"folder", a path relative to files/user-files, "pick", true to
 * choose a folder with the system picker, or "session", the id of a
 * project opened before, opens that project;</li>
 * <li>"remove", a session id, forgets that project, never its files;</li>
 * <li>"run_this", a path, runs that file from now on;</li>
 * <li>"edit", a path, with "text" sets that file's text as the editor
 * would and keeps it as a draft;</li>
 * <li>"save", true, saves explicitly; "refresh", true, looks for changes
 * made elsewhere;</li>
 * <li>"resolve", reload, keep or copy, resolves the conflict of the path
 * in "file".</li>
 * </ul>
 */
public class ProjectPreviewActivity extends PreviewActivity {
	public static final String FOLDER = "folder";

	private static final String TAG = "ProjectHarness";

	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private final ActivityResultLauncher<Intent> picker =
			registerForActivityResult(
					new ActivityResultContracts.StartActivityForResult(),
					result -> {
						Intent data = result.getData();
						if (data != null && data.getData() != null) {
							Uri tree = data.getData();
							int flags = data.getFlags();
							worker.execute(() -> {
								use(projects().openTree(tree, flags));
								report(1f);
							});
						}
					});
	private DialogFragment errorList;
	// Worker thread only.
	private Projects projects;
	// Set on the worker thread.
	private volatile OpenProject project;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (isFinishing()) {
			return;
		}
		getShaderView().getEngine().setListener(new ShaderEngine.Listener() {
			@Override
			public void onDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics) {
				runOnUiThread(() -> showDiagnostics(diagnostics));
			}

			@Override
			public void onFramesPerSecond(int fps) {
			}
		});
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		worker.shutdown();
	}

	@Override
	protected boolean setShaderFromIntent(Intent intent) {
		if (intent == null || intent.getExtras() == null ||
				!getResources().getBoolean(R.bool.project_mode)) {
			return false;
		}
		Bundle extras = new Bundle(intent.getExtras());
		if (extras.getBoolean("pick")) {
			picker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
							Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
							Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
		}
		worker.execute(() -> {
			apply(extras);
			report(extras.getFloat(QUALITY, 1f));
		});
		return true;
	}

	private void apply(Bundle extras) {
		String folder = extras.getString(FOLDER);
		String session = extras.getString("session");
		if (folder != null) {
			var path = ProjectPath.parse(folder);
			use(path.isSuccess()
					? projects().openDirectory(new File(
							UserFilesProvider.getUserFilesDir(this),
							path.value().toString()))
					: new Projects.Opened(null, null, folder + " is no path"));
		} else if (session != null) {
			use(projects().open(session));
		}
		String remove = extras.getString("remove");
		if (remove != null) {
			log("removed " + remove + ": " + projects().remove(remove));
		}
		OpenProject current = project;
		if (current == null) {
			return;
		}
		ProjectPath runThis = path(extras.getString("run_this"));
		if (runThis != null) {
			current.runThisFile(runThis);
		}
		ProjectPath edit = path(extras.getString("edit"));
		String text = extras.getString("text");
		if (edit != null && text != null) {
			String problem = current.load(edit);
			if (problem != null) {
				log(problem);
			} else {
				current.edit(edit, text.replace("\\n", "\n"));
				current.persist();
			}
		}
		if (extras.getBoolean("save")) {
			log("saved " + current.save(true));
		}
		if (extras.getBoolean("refresh")) {
			log("read again " + current.refresh());
		}
		ProjectPath file = path(extras.getString("file"));
		String resolve = extras.getString("resolve");
		if (file != null && "reload".equals(resolve)) {
			log("reload " + current.reload(file));
		} else if (file != null && "keep".equals(resolve)) {
			current.keepEdits(file);
		} else if (file != null && "copy".equals(resolve)) {
			log("copy " + current.saveCopy(file));
		}
	}

	// Logs the state of the project and runs what it runs.
	private void report(float quality) {
		List<String> recent = new ArrayList<>();
		for (ProjectSessions.Recent session : projects().sessions().recent()) {
			recent.add(session.id() + " " + session.name() + " (" +
					session.location() + (session.unsaved() ? ", unsaved" : "") +
					")");
		}
		log("recent " + recent);
		OpenProject current = project;
		if (current == null) {
			return;
		}
		List<String> files = new ArrayList<>();
		for (OpenProject.Item item : current.list(null).items()) {
			files.add(item.path() + (item.folder() ? "/" : ""));
		}
		log("project " + current.id() + " runs " + current.running() +
				", files " + files + ", conflicts " + current.conflicts());
		for (ProjectPath unsaved : current.unsaved()) {
			current.load(unsaved);
			log("unsaved " + unsaved + ": " + current.text(unsaved));
		}
		Preparation preparation = current.prepare(UniformTable.types());
		if (preparation == null) {
			log("nothing runs");
		} else {
			getShaderView().getEngine().setPlan(preparation, quality);
		}
	}

	private void use(Projects.Opened opened) {
		if (opened.project() != null) {
			project = opened.project();
		}
		log("open " + opened.id() + (opened.problem() != null
				? ": " + opened.problem()
				: ""));
	}

	private Projects projects() {
		if (projects == null) {
			projects = new Projects(this);
		}
		return projects;
	}

	private void showDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics) {
		OpenProject current = project;
		if (current != null) {
			current.setDiagnostics(diagnostics);
			log("diagnostics " + diagnostics + ", errors " + current.errors());
		}
		if (errorList != null) {
			errorList.dismissAllowingStateLoss();
			errorList = null;
		}
		if (diagnostics.isEmpty() || isFinishing()) {
			return;
		}
		errorList = new ErrorListModal(diagnostics, (file, line) -> {
		});
		errorList.showNow(getSupportFragmentManager(), ErrorListModal.TAG);
	}

	private static ProjectPath path(String path) {
		var parsed = path != null ? ProjectPath.parse(path) : null;
		return parsed != null && parsed.isSuccess() ? parsed.value() : null;
	}

	private static void log(String message) {
		Log.i(TAG, message);
	}
}
