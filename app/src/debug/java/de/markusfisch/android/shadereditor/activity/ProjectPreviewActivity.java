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
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
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
 * would and keeps it in the journal;</li>
 * <li>"save", true, saves explicitly; "refresh", true, looks for changes
 * made elsewhere;</li>
 * <li>"resolve", reload or keep, resolves the conflict of the path in
 * "file".</li>
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
							projects().openTree(tree, flags, opened -> {
								use(opened);
								report(1f);
							});
						}
					});
	private DialogFragment errorList;
	private Projects projects;
	private OpenProject.Build build;
	private OpenProject project;

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
		float quality = extras.getFloat(QUALITY, 1f);
		open(extras, () -> apply(extras,
				() -> afterWorker(() -> report(quality))));
		return true;
	}

	// Opens a project, then removes one and runs then; the projects
	// answer on the main thread.
	private void open(Bundle extras, Runnable then) {
		String folder = extras.getString(FOLDER);
		String session = extras.getString("session");
		FileTexts.Callback<Projects.Opened> opened = result -> {
			use(result);
			remove(extras, then);
		};
		var path = folder != null ? ProjectPath.parse(folder) : null;
		if (path != null && path.isSuccess()) {
			worker.execute(() -> {
				File dir = new File(UserFilesProvider.getUserFilesDir(this),
						path.value().toString());
				runOnUiThread(() -> projects().openDirectory(dir, opened));
			});
		} else if (path != null) {
			opened.done(new Projects.Opened(null, null,
					folder + " is no path"));
		} else if (session != null) {
			projects().open(session, opened);
		} else {
			remove(extras, then);
		}
	}

	private void remove(Bundle extras, Runnable then) {
		String remove = extras.getString("remove");
		if (remove != null) {
			projects().remove(remove, removed -> {
				log("removed " + remove + ": " + removed);
				recent(then);
			});
		} else {
			recent(then);
		}
	}

	private void recent(Runnable then) {
		ProjectSessions sessions = projects().sessions();
		worker.execute(() -> {
			List<String> recent = new ArrayList<>();
			for (ProjectSessions.Recent kept : sessions.recent()) {
				recent.add(kept.id() + " " + kept.name() + " (" +
						kept.location() +
						(kept.unsaved() ? ", unsaved" : "") + ")");
			}
			log("recent " + recent);
			runOnUiThread(then);
		});
	}

	// Drives the open project, then runs then; on the main thread, which
	// the project belongs to.
	private void apply(Bundle extras, Runnable then) {
		OpenProject current = project;
		if (current == null) {
			then.run();
			return;
		}
		ProjectPath runThis = path(extras.getString("run_this"));
		if (runThis != null) {
			current.runThisFile(runThis);
		}
		ProjectPath edit = path(extras.getString("edit"));
		String text = extras.getString("text");
		if (edit != null && text != null) {
			current.load(edit, ProjectFiles.UNKNOWN, problem -> {
				Document document = current.document(edit);
				if (problem != null) {
					log(problem);
				} else if (document != null) {
					document.replace(0, document.text().length(),
							Text.of(text.replace("\\n", "\n")));
					current.persist();
				}
				resolve(current, extras);
				then.run();
			});
		} else {
			resolve(current, extras);
			then.run();
		}
	}

	private void resolve(OpenProject current, Bundle extras) {
		if (extras.getBoolean("save")) {
			current.save(true, outcomes -> log("saved " + outcomes));
		}
		if (extras.getBoolean("refresh")) {
			current.refresh(reread -> log("read again " + reread));
		}
		ProjectPath file = path(extras.getString("file"));
		String resolve = extras.getString("resolve");
		if (file != null && "reload".equals(resolve)) {
			current.reload(file, problem -> log("reload " + problem));
		} else if (file != null && "keep".equals(resolve)) {
			current.resolve(file, OpenProject.Choice.KEEP,
					current.seen(file));
		}
	}

	// Logs the state of the project and runs what it runs.
	// On the main thread.
	private void report(float quality) {
		OpenProject current = project;
		if (current == null) {
			return;
		}
		worker.execute(() -> {
			List<String> files = new ArrayList<>();
			for (OpenProject.Item item : current.list(null).items()) {
				files.add(item.path() + (item.folder() ? "/" : ""));
			}
			log("project " + current.id() + ", files " + files);
		});
		log("project " + current.id() + " runs " + current.running() +
				", conflicts " + current.conflicts());
		for (ProjectPath unsaved : current.unsaved()) {
			current.load(unsaved, ProjectFiles.UNKNOWN, problem -> {
				Document document = current.document(unsaved);
				log("unsaved " + unsaved + ": " + (document != null
						? document.text()
						: problem));
			});
		}
		current.prepare(UniformTable.builtins(), build -> {
			Preparation preparation = build.preparation();
			this.build = build;
			if (preparation == null) {
				log("nothing runs");
			} else {
				getShaderView().getEngine().setPlan(preparation, quality);
			}
		});
	}

	// Runs task on the main thread once the worker did what it was given
	// before.
	private void afterWorker(Runnable task) {
		worker.execute(() -> runOnUiThread(task));
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
			if (build != null) {
				current.setDiagnostics(build, diagnostics);
			}
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
