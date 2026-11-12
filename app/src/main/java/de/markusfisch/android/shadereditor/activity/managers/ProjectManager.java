package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.project.EditingSession;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ProjectSessions;
import de.markusfisch.android.shadereditor.project.SafProjects;
import de.markusfisch.android.shadereditor.view.UndoRedo;
import de.markusfisch.android.shadereditor.widget.ErrorListModal;
import de.markusfisch.android.shadereditor.widget.FileListSheet;

/**
 * Shows the open project, if any, in the editor and the preview. Its
 * files are read and written on one worker thread; the projects opened
 * stay in memory with their editor state while the activity lives.
 */
public class ProjectManager {
	public interface Listener {
		// Before a project is opened, while the editor still shows what
		// is open.
		void onProjectOpening();

		// A project replaced what the editor showed.
		void onProjectOpened();

		// The recent projects or the one that is open changed.
		void onProjectsChanged();
	}

	public interface Previewer {
		void preview(@NonNull String projectId);
	}

	// The projects opened, kept across configuration changes.
	public static class Projects extends ViewModel {
		private final ExecutorService worker =
				Executors.newSingleThreadExecutor();
		// Worker thread only.
		private final Map<String, OpenProject> opened = new HashMap<>();
		// Main thread only.
		@Nullable
		private OpenProject current;
		@NonNull
		private String currentName = "";

		@Override
		protected void onCleared() {
			worker.shutdown();
		}
	}

	private static final String PROJECT_ID = "project_id";

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ShaderViewManager shaderViewManager;
	private final UIManager uiManager;
	private final ProjectSessions sessions;
	private final Listener listener;
	private final Projects projects;
	private final ActivityResultLauncher<Intent> openFolderLauncher;

	// The project that is open or opening; edits go to project then.
	@Nullable
	private String id;
	@Nullable
	private OpenProject project;
	@Nullable
	private FileListSheet fileList;
	private boolean showing;
	private boolean listWhenOpened;
	private boolean asking;
	private boolean titleUnsaved;
	private boolean projectUnsaved;
	private float quality = 1f;

	public ProjectManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editorFragment,
			@NonNull ShaderViewManager shaderViewManager,
			@NonNull UIManager uiManager,
			@NonNull ProjectSessions sessions,
			@NonNull Listener listener) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.shaderViewManager = shaderViewManager;
		this.uiManager = uiManager;
		this.sessions = sessions;
		this.listener = listener;
		projects = new ViewModelProvider(activity).get(Projects.class);
		openFolderLauncher = activity.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(), result -> {
					Intent data = result.getData();
					if (result.getResultCode() == Activity.RESULT_OK &&
							data != null && data.getData() != null) {
						openTree(data.getData(), data.getFlags());
					}
				});
	}

	// Where the sessions of opened projects are kept.
	@NonNull
	public static ProjectSessions sessions(@NonNull Context context) {
		return new ProjectSessions(new File(context.getNoBackupFilesDir(),
				"projects"));
	}

	public boolean isOpen() {
		return id != null;
	}

	@Nullable
	public String getId() {
		return id;
	}

	public void saveState(@NonNull Bundle state) {
		state.putString(PROJECT_ID, id);
	}

	// Call it in onCreate, before the editor restores its text.
	public void restoreState(@Nullable Bundle state) {
		String restored = state != null ? state.getString(PROJECT_ID) : null;
		if (restored != null) {
			id = restored;
			load(restored);
		}
	}

	// Asks the system picker for a folder to open, keeping its grant.
	public void openFolder() {
		openFolderLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
						Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
						Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
	}

	// Opens a project; what is open stays open when it fails.
	public void open(@NonNull String projectId) {
		if (project == null || !project.id().equals(projectId)) {
			listener.onProjectOpening();
			id = projectId;
			listWhenOpened = true;
			load(projectId);
		}
	}

	// Lists the files of the open project's folder, one folder at a
	// time; a text file chosen there opens in the editor.
	public void showFiles(@Nullable ProjectPath folder) {
		OpenProject current = project;
		if (current == null) {
			return;
		}
		work(() -> {
			var listing = current.list(folder);
			var unsaved = current.unsaved();
			handler.post(() -> {
				if (current != project || activity.isFinishing()) {
					return;
				}
				if (fileList == null) {
					fileList = new FileListSheet(activity, item -> {
						if (item.folder()) {
							showFiles(item.path());
						} else {
							openFile(item.path(), 0);
						}
					});
				}
				fileList.show(folder != null
								? projects.currentName + " / " + folder
								: projects.currentName,
						folder, listing, unsaved, current.running());
			});
		});
	}

	// Shows file of the open project in the editor at line, 0 for where
	// it was, with its own text and undo history, or says why it cannot
	// be edited.
	public void openFile(@NonNull ProjectPath file, int line) {
		OpenProject current = project;
		if (current == null) {
			return;
		}
		work(() -> {
			String problem = current.load(file);
			handler.post(() -> {
				if (current != project) {
					return;
				} else if (problem != null) {
					Toast.makeText(activity, problem, Toast.LENGTH_LONG).show();
					return;
				}
				if (fileList != null) {
					fileList.dismiss();
				}
				persist();
				current.show(file);
				show();
				if (line > 0) {
					handler.post(() -> editorFragment.navigateToLine(line));
				}
			});
		});
	}

	// Keeps the diagnostics of the last build and returns the ones to
	// mark in the file shown.
	@NonNull
	public List<ProjectDiagnostic> setDiagnostics(
			@NonNull List<ProjectDiagnostic> diagnostics) {
		OpenProject current = project;
		if (current == null) {
			return List.of();
		}
		current.setDiagnostics(diagnostics);
		uiManager.setToolbarSubtitle(getSubtitle(0));
		return current.diagnostics(current.shown());
	}

	// Lists the diagnostics of all files; choosing one opens its file
	// at its line.
	public void showErrors() {
		OpenProject current = project;
		if (current != null) {
			new ErrorListModal(current.diagnostics(), (file, line) -> {
				if (file != null) {
					openFile(file, line);
				}
			}).show(activity.getSupportFragmentManager(), ErrorListModal.TAG);
		}
	}

	public void destroy() {
		if (fileList != null) {
			fileList.dismiss();
		}
	}

	private void load(@NonNull String projectId) {
		OpenProject current = projects.current;
		if (current != null && current.id().equals(projectId)) {
			// After the editor restored its text.
			handler.post(() -> opened(current, projects.currentName, null));
			return;
		}
		work(() -> {
			OpenProject opened = projects.opened.get(projectId);
			String problem = null;
			if (opened == null) {
				var locator = sessions.locator(projectId);
				var files = locator != null
						? SafProjects.open(activity, locator)
						: null;
				problem = files == null ? "it is gone" : files.problem();
				opened = problem == null
						? OpenProject.open(projectId, files.files(), sessions)
						: null;
				if (opened != null) {
					projects.opened.put(projectId, opened);
				}
			} else {
				opened.findRunning();
			}
			sessions.touch(projectId, System.currentTimeMillis());
			var recent = sessions.recent(projectId);
			String name = recent != null ? recent.name() : "";
			if (opened != null) {
				ProjectPath file = opened.shown() != null
						? opened.shown()
						: opened.running();
				opened.show(file != null && opened.load(file) == null
						? file
						: null);
			}
			OpenProject result = opened;
			String reason = problem;
			handler.post(() -> {
				if (projectId.equals(id) && !activity.isDestroyed()) {
					opened(result, name, reason);
				}
			});
		});
	}

	// Before something else opens in the editor.
	public void close() {
		persist();
		id = null;
		project = null;
		projects.current = null;
	}

	public void onTextModified() {
		if (!showing) {
			keepEdits();
			showTitle(false);
		}
	}

	// Saves every file with unsaved text where it is and reports each
	// file that could not be saved, with Retry.
	public void save(boolean explicit) {
		OpenProject current = project;
		if (current != null) {
			keepEdits();
			save(current, explicit);
		}
	}

	private void save(@NonNull OpenProject current, boolean explicit) {
		work(() -> {
			var outcomes = current.save(explicit);
			handler.post(() -> saved(current, outcomes, explicit));
		});
	}

	private void saved(@NonNull OpenProject saved,
			@NonNull Map<ProjectPath, EditingSession.Outcome> outcomes,
			boolean explicit) {
		if (activity.isFinishing()) {
			return;
		}
		StringBuilder failures = new StringBuilder();
		for (var entry : outcomes.entrySet()) {
			if (entry.getValue() instanceof EditingSession.Failed failed) {
				failures.append(entry.getKey()).append(": ")
						.append(failed.reason()).append('\n');
			}
		}
		if (failures.length() > 0) {
			new MaterialAlertDialogBuilder(activity)
					.setTitle(R.string.not_saved)
					.setMessage(failures.toString().trim())
					.setPositiveButton(R.string.retry, (dialog, which) ->
							save(saved, explicit))
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		} else if (explicit || !outcomes.isEmpty()) {
			Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show();
		}
		if (saved == project) {
			showTitle(false);
			showUnsaved(!saved.unsaved().isEmpty());
			askAboutConflicts(saved);
		}
	}

	// On return to the app: reads files changed elsewhere again where
	// that loses nothing, and asks about the others.
	public void refresh() {
		OpenProject current = project;
		if (current == null) {
			return;
		}
		keepEdits();
		ProjectPath running = current.running();
		work(() -> {
			var reloaded = current.refresh();
			handler.post(() -> {
				if (current != project) {
					return;
				} else if (reloaded.contains(current.shown())) {
					show();
				}
				if (!reloaded.isEmpty() ||
						!Objects.equals(running, current.running())) {
					run();
				}
				askAboutConflicts(current);
			});
		});
	}

	// Offers Reload from file, Keep my edits and Save a copy for each
	// file with unsaved text that changed elsewhere, one at a time.
	private void askAboutConflicts(@NonNull OpenProject current) {
		if (asking) {
			return;
		}
		asking = true;
		work(() -> {
			var conflicts = current.conflicts();
			handler.post(() -> {
				var conflict = conflicts.entrySet().iterator();
				if (current != project || !conflict.hasNext() ||
						activity.isFinishing()) {
					asking = false;
					return;
				}
				var entry = conflict.next();
				ProjectPath file = entry.getKey();
				new MaterialAlertDialogBuilder(activity)
						.setTitle(file.toString())
						.setMessage(activity.getString(R.string.conflict,
								entry.getValue()))
						.setCancelable(false)
						.setPositiveButton(R.string.reload_from_file, (d, w) ->
								resolve(current, () -> {
									String problem = current.reload(file);
									if (problem != null &&
											file.equals(current.shown())) {
										current.show(null);
									}
									return problem;
								}))
						.setNegativeButton(R.string.keep_my_edits, (d, w) ->
								resolve(current, () -> {
									current.keepEdits(file);
									return null;
								}))
						.setNeutralButton(R.string.save_a_copy, (d, w) ->
								resolve(current, () -> {
									var copy = current.saveCopy(file);
									if (copy.path() != null) {
										current.show(copy.path());
										return activity.getString(
												R.string.saved_as, copy.path());
									}
									return copy.problem();
								}))
						.show();
			});
		});
	}

	private interface Resolution {
		// Returns what to tell the user, or null.
		@Nullable
		String apply();
	}

	private void resolve(@NonNull OpenProject current,
			@NonNull Resolution resolution) {
		keepEdits();
		work(() -> {
			String message = resolution.apply();
			handler.post(() -> {
				asking = false;
				if (current != project) {
					return;
				} else if (message != null) {
					Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
				}
				show();
				run();
				askAboutConflicts(current);
			});
		});
	}

	public void run() {
		if (project != null) {
			keepEdits();
			shaderViewManager.setProject(project, quality);
		}
	}

	public void setQuality(float quality) {
		this.quality = quality;
	}

	public float getQuality() {
		return quality;
	}

	// Keeps the text in the editor durably, then hands the id of the
	// project to preview, which reads it from there.
	public void preview(@NonNull Previewer previewer) {
		OpenProject current = project;
		if (current != null) {
			keepEdits();
			work(() -> {
				current.persist();
				handler.post(() -> {
					if (current == project) {
						previewer.preview(current.id());
					}
				});
			});
		}
	}

	// Whether the file shown is a shader that does not run yet.
	public boolean canRunThisFile() {
		OpenProject current = project;
		ProjectPath file = current != null ? current.shown() : null;
		return file != null && file.getKind() == ProjectPath.Kind.SHADER &&
				!file.equals(current.running());
	}

	// Runs the file shown from now on, also after reopening the folder,
	// without changing the folder's files.
	public void runThisFile() {
		OpenProject current = project;
		ProjectPath file = current != null ? current.shown() : null;
		if (file != null) {
			work(() -> {
				current.runThisFile(file);
				handler.post(() -> {
					if (current == project) {
						run();
						uiManager.setToolbarSubtitle(getSubtitle(0));
					}
				});
			});
		}
	}

	// The preview title: the file that runs, or how to run one.
	@NonNull
	public String getSubtitle(int fps) {
		OpenProject current = project;
		ProjectPath running = current != null ? current.running() : null;
		if (running == null) {
			return current != null
					? activity.getString(R.string.open_shader_then_run)
					: "";
		}
		String title = activity.getString(R.string.preview_of, running);
		int errors = current.errors();
		return errors > 0
				? title + " \u00b7 " + activity.getString(R.string.error_count,
						errors)
				: fps > 0 ? title + " \u00b7 " + fps + " fps" : title;
	}

	// Removes a recent project with its drafts and grant, never its
	// files, and closes it when it is open.
	public void remove(@NonNull String projectId, @NonNull Runnable closed) {
		boolean open = projectId.equals(id);
		if (open) {
			close();
		}
		work(() -> {
			var locator = sessions.locator(projectId);
			if (locator != null &&
					locator.kind() == ProjectSessions.Kind.TREE) {
				SafProjects.releaseGrant(activity.getContentResolver(),
						Uri.parse(locator.value()));
			}
			projects.opened.remove(projectId);
			sessions.remove(projectId);
			handler.post(() -> {
				if (open) {
					closed.run();
				}
				listener.onProjectsChanged();
			});
		});
	}

	private void openTree(@NonNull Uri tree, int flags) {
		work(() -> {
			var locator = SafProjects.locate(activity, tree);
			if (locator != null &&
					locator.kind() == ProjectSessions.Kind.TREE) {
				SafProjects.keepGrant(activity.getContentResolver(), tree,
						flags);
			}
			// Listed among the recent projects by name and location.
			String[] description = locator != null
					? SafProjects.describe(activity, locator)
					: null;
			String projectId = description != null
					? sessions.open(locator)
					: null;
			boolean listed = projectId != null && sessions.remember(projectId,
					description[0], description[1], System.currentTimeMillis());
			handler.post(() -> {
				if (listed) {
					open(projectId);
				} else {
					showProblem(null);
				}
			});
		});
	}

	private void opened(@Nullable OpenProject opened, @NonNull String name,
			@Nullable String problem) {
		if (opened == null) {
			id = project != null ? project.id() : null;
			listWhenOpened = false;
			showProblem(problem);
			return;
		}
		if (project != null) {
			close();
		}
		id = opened.id();
		project = opened;
		projectUnsaved = !opened.unsaved().isEmpty();
		projects.current = opened;
		projects.currentName = name;
		listener.onProjectOpened();
		show();
		run();
		listener.onProjectsChanged();
		if (listWhenOpened && opened.shown() == null) {
			showFiles(null);
		}
		listWhenOpened = false;
	}

	// Shows the file the project shows in the editor, with its own undo
	// history, or nothing.
	private void show() {
		OpenProject current = Objects.requireNonNull(project);
		ProjectPath file = current.shown();
		Object state = file != null ? current.editorState(file) : null;
		UndoRedo.EditHistory history = state instanceof UndoRedo.EditHistory h
				? h
				: new UndoRedo.EditHistory();
		if (file != null) {
			current.setEditorState(file, history);
		}
		showing = true;
		editorFragment.setText(file != null ? current.text(file) : "",
				history);
		showing = false;
		// Once the text is laid out.
		handler.post(() -> {
			if (current == project && file == current.shown()) {
				editorFragment.setErrors(current.diagnostics(file));
			}
		});
		showTitle(true);
	}

	// Shows the name of the file shown, with a dot while it is unsaved.
	private void showTitle(boolean always) {
		OpenProject current = project;
		ProjectPath file = current != null ? current.shown() : null;
		boolean unsaved = file != null && current.unsaved().contains(file);
		if (always || unsaved != titleUnsaved) {
			titleUnsaved = unsaved;
			uiManager.setToolbarTitle(file == null
					? projects.currentName
					: unsaved ? file.getFileName() + " \u2022" : file.getFileName());
			uiManager.setToolbarSubtitle(getSubtitle(0));
		}
	}

	// The drawer marks a project with unsaved files.
	private void showUnsaved(boolean unsaved) {
		if (unsaved != projectUnsaved) {
			projectUnsaved = unsaved;
			listener.onProjectsChanged();
		}
	}

	// Keeps the text in the editor durably.
	public void persist() {
		OpenProject current = project;
		if (current != null) {
			keepEdits();
			work(() -> {
				current.persist();
				boolean unsaved = !current.unsaved().isEmpty();
				handler.post(() -> {
					if (current == project) {
						showUnsaved(unsaved);
					}
				});
			});
		}
	}

	private void work(@NonNull Runnable task) {
		try {
			projects.worker.execute(task);
		} catch (RejectedExecutionException e) {
			// The activity is finishing.
		}
	}

	// Hands the text in the editor to the project.
	private void keepEdits() {
		OpenProject current = project;
		ProjectPath file = current != null ? current.shown() : null;
		if (file != null) {
			current.edit(file, editorFragment.getRawText());
		}
	}

	private void showProblem(@Nullable String problem) {
		Toast.makeText(activity, activity.getString(R.string.cannot_open_folder,
						problem != null ? problem : "?"),
				Toast.LENGTH_LONG).show();
	}
}
