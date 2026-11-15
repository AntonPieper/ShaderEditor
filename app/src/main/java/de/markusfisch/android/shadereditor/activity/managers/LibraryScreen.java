package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import org.jetbrains.annotations.Contract;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.editor.InsertTexture;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.project.FileRole;
import de.markusfisch.android.shadereditor.project.Library;
import de.markusfisch.android.shadereditor.project.LibraryMove;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ShaderLanguage;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;
import de.markusfisch.android.shadereditor.widget.Message;

/**
 * The main screen where the shaders are files: of the library, of
 * other folders, of the examples. It owns everything that is started
 * for them, and what is open: a file, a shader of the database that
 * was not moved, or nothing.
 */
public final class LibraryScreen implements ShaderScreen {
	// How long the notice that a folder is read-only stays.
	private static final int READ_ONLY_MILLIS = 10000;
	// Reads which file of the library follows one that went.
	private final ExecutorService newest =
			Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	// Whether a picture of what runs is being drawn, as the engine
	// reports it: the one truth of "runs now", for the toolbar's ▶ and
	// the drawer's.
	private boolean drawing;
	private boolean destroyed;

	private static final String OPEN = "open";
	private static final int NEEDS_NEW_EDITOR_MILLIS = 10000;

	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final UIManager uiManager;
	private final ShaderViewManager shaderViewManager;
	private final ExtraKeysManager extraKeysManager;
	private final NavigationManager navigationManager;
	private final DataSource dataSource;
	private final Host host;
	private ShaderManager shaderManager;
	// What the editor has open. shaderManager is only used directly to
	// open and create database shaders.
	private OpenItem open;
	private ShaderList shaderListManager;
	// Where the shaders are files, the list is the drawer of folders.
	private NavigationDrawer drawer;
	// The files of folders, with project mode only, else null.
	@Nullable
	private ProjectHolds holds;
	@Nullable
	private ProjectFileManager files;
	@Nullable
	private LibraryStart libraryStart;
	@Nullable
	private LibraryLooks looks;
	@Nullable
	private LibraryAdd libraryAdd;
	// The editor was given back the text of a file of a folder that the
	// old editor cannot show; it is not a shader to run.
	private boolean holdsUnshownFile = false;
	// Where the shaders are files: what is open while no file and no
	// shader of the database is.
	private NothingOpen nothing;
	// The start has not shown a file yet, and whether the screen says
	// that the shaders are being moved.
	private boolean moving;
	private boolean movingTold;
	// The shaders of the database that could not be moved.
	private List<LibraryMove.Failed> notMoved = List.of();
	// The code was hidden when the screen was made again with nothing
	// open yet: it is hidden again once a text is shown.
	private boolean hideCodeOnShow;
	// Where the shaders are files: keeps a file the app ended on from
	// running by itself, and the message that says so while it shows.
	private RunGuard guard;
	@Nullable
	private Snackbar heldMessage;
	// The quality chosen last on this screen, and for which file that
	// runs, as folder and path: a run right after the choice may come
	// before the library has kept it.
	@Nullable
	private List<Object> chosenFor;
	private float chosenQuality;
	// What was open before the screen was made again; null for a new
	// screen, and where the shaders are rows of the database.
	@Nullable
	private OpenState restored;

	public LibraryScreen(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editorFragment,
			@NonNull UIManager uiManager,
			@NonNull ShaderViewManager shaderViewManager,
			@NonNull ExtraKeysManager extraKeysManager,
			@NonNull NavigationManager navigationManager,
			@NonNull DataSource dataSource,
			@NonNull ShaderViewManager.Listener shaderViewListener,
			@NonNull ShaderViewManager.Listener shaderListener,
			@NonNull Host host) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.uiManager = uiManager;
		this.shaderViewManager = shaderViewManager;
		this.extraKeysManager = extraKeysManager;
		this.navigationManager = navigationManager;
		this.dataSource = dataSource;
		this.host = host;
		holds = new ProjectHolds(new Projects(activity));
		libraryAdd = new LibraryAdd(activity, holds.projects(), dataSource,
				new LibraryAdd.Listener() {
					@Override
					public void onAdded(@NonNull String key,
							@NonNull ProjectPath path) {
						if (ShaderEditorApp.preferences.autoSave()) {
							open.save(false);
						}
						files.open(key, path, false);
						shaderListManager.loadShadersAsync();
					}

					@Override
					public void onFailed(@NonNull String message) {
						Message.show(activity, message);
						// The start left the screen to what was
						// to be added: the library's file, then.
						if (!open.hasText() && moving) {
							libraryStart.show();
						}
					}
				});
		looks = new LibraryLooks(activity, holds.projects(), shaderViewManager,
				() -> shaderListManager.loadShadersAsync(),
				// A picture taken: the drawer takes it as it is.
				(key, path, png) -> {
					if (drawer != null) {
						drawer.setPicture(key, path, png);
					}
				},
				problem -> Message.show(activity, activity.getString(
						R.string.library_not_kept, problem)));
		// The quality chosen is told once it changes, also when the
		// spinner first shows what it holds: what it holds now.
		shaderViewManager.setQuality(1f);
		// "Runs now" is what the engine draws.
		shaderViewManager.setDrawingListener(drawn ->
				handler.post(() -> setDrawing(drawn)));
		guard = new RunGuard(new RunGuard.Store() {
			@NonNull
			@Override
			public String pending() {
				return ShaderEditorApp.preferences.getPendingCrashFile();
			}

			@Override
			public void setPending(@NonNull String file) {
				ShaderEditorApp.preferences.setPendingCrashFile(file);
			}

			@NonNull
			@Override
			public String held() {
				return ShaderEditorApp.preferences.getHeldFile();
			}

			@Override
			public void setHeld(@NonNull String file) {
				ShaderEditorApp.preferences.setHeldFile(file);
			}
		});
		files = new ProjectFileManager(activity, editorFragment, holds,
				createProjectFileListener(),
				(preparation, nothingRuns, quality) -> {
					if (preparation != null) {
						ProjectFile file = files.file();
						String key = files.key();
						ProjectPath running = file != null
								? file.getProject().running()
								: null;
						looks.quality(files.key(), running, quality,
								read -> {
									float kept = Arrays.asList(key, running)
											.equals(chosenFor)
											? chosenQuality
											: read;
									if (file != null) {
										file.setQuality(kept);
									}
									if (key != null && running != null &&
											!mayRun(key, running)) {
										return;
									}
									// The folder and the file that
									// runs: what another one showed
									// never stays when this fails.
									shaderViewManager.setPlan(
											preparation, kept,
											Arrays.asList(key, running));
									looks.ran(files.key(), running);
								});
					} else {
						// No build reports, so only the manifest
						// can be wrong.
						forgetHeldMessage();
						showNothing();
						shaderViewListener.onInfoLog(nothingRuns);
					}
				});
		editorFragment.setOnErrorSelectedListener((file, line) -> {
			if (file != null) {
				files.openAt(file, line);
			} else {
				editorFragment.navigateToLine(line);
			}
		});
		editorFragment.setOnShaderToyShaderListener(this::askToImport);
	
		drawer = createDrawer();
		shaderListManager = drawer;
		shaderManager = new ShaderManager(activity,
				editorFragment,
				shaderViewManager,
				shaderListManager,
				navigationManager,
				dataSource,
				true,
				shaderListener,
				this::showOpenTitle,
				() -> uiManager.showNotice(activity.getText(R.string.shader_saved),
						ShaderScreen.SAVED_NOTICE_MILLIS));
		nothing = new NothingOpen(activity.getString(R.string.nav_shaders));
		// Where the shaders are files, nothing is open until the start
		// shows a file: no shader of the database stands in for it.
		open = nothing;
		// A shader that was not moved is shown.
		shaderManager.setOnShownListener(() -> {
			files.leave();
			open = shaderManager;
			moving = false;
			forgetHeldMessage();
			showOpen();
		});
		shaderManager.setOnGoneListener(this::showLibraryFile);
	}

	@Override
	public void start(@Nullable Bundle state, @Nullable Intent intent) {
		// Only show the library's file when no incoming text was
		// opened, or it would replace that text.
		boolean openedText = addShared(intent);
		restored = state != null
				? OpenState.decode(state.getString(OPEN))
				: null;
		holdsUnshownFile = restored != null &&
				restored.kind() == OpenState.Kind.FILE &&
				!ShaderEditorApp.preferences.useNewEditor();
		// A screen that had a file, a shader or nothing open comes
		// back to that; one that had not come that far shows what
		// the library shows, as a cold start does, unless text
		// that was shared comes instead, or a shader the screen
		// before this one asked for.
		boolean show = !openedText && !libraryAdd.awaits() &&
				(restored == null || restored.showsLibrary());
		// Nothing stands where the code is until what was open is
		// back, or the start has shown a file.
		moving = true;
		movingTold = restored != null &&
				restored.kind() == OpenState.Kind.MOVING;
		showOpen();
		startLibrary(show);
		libraryAdd.resume();
		// The examples are a folder like any other, with the pictures
		// that come with them.
		ExamplePictures.fill(activity, holds.projects(),
				shaderListManager::loadShadersAsync);
	}

	@Override
	public void onRestoreInstanceState(@NonNull Bundle state) {
		if (restored != null && restored.isFile()) {
			files.restore(restored);
		} else if (restored != null &&
				restored.kind() == OpenState.Kind.NOT_MOVED) {
			shaderManager.restore(restored.shader(),
					restored.title() != null ? restored.title() : "",
					restored.quality(), restored.modified());
			open = shaderManager;
			moving = false;
			showOpen();
		} else if (restored != null &&
				restored.kind() == OpenState.Kind.NOTHING) {
			moving = false;
			showOpen();
		}
		// Not that far yet: the start shows what the library shows.
		if (!state.getBoolean(ShaderScreen.CODE_VISIBLE, true)) {
			if (open.hasText()) {
				uiManager.toggleCodeVisibility();
			} else {
				hideCodeOnShow = true;
			}
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle state) {
		state.putString(OPEN, openState().encode());
		// With nothing open the editor's view is gone for that reason.
		state.putBoolean(ShaderScreen.CODE_VISIBLE, open.hasText()
				? editorFragment.isCodeVisible()
				: !hideCodeOnShow);
	}

	@Override
	public void onPostCreate() {
		// A file that comes back keeps its title until it is shown.
		if (!open.hasText() && (restored == null ||
				restored.showsLibrary())) {
			showOpenTitle();
		}
	}

	@Override
	public void onStart() {
		// The editor's view exists by now.
		if (!open.hasText()) {
			showOpen();
		}
		files.onStart();
	}

	@Override
	public void onStop() {
		files.onStop();
	}

	@Override
	public void onResume() {
		shaderListManager.loadShadersAsync();
	}

	@Override
	public void onPause() {
		guard.ran();
	}

	@Override
	public void onDestroy(boolean finishing) {
		// The screen made again after a configuration change takes them.
		if (!finishing) {
			holds.keepOpen();
		}
		destroyed = true;
		shaderViewManager.setDrawingListener(null);
		newest.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		files.destroy();
		if (libraryStart != null) {
			libraryStart.destroy();
		}
		libraryAdd.destroy();
		looks.destroy();
		shaderManager.destroy();
		shaderListManager.destroy();
	}

	// Show the newest file of the library, session key, if it has one
	// and nothing else was opened meanwhile.
	private void showNewest(@NonNull String key) {
		Projects projects = holds.projects();
		try {
			newest.execute(() -> {
				List<Library.RootFile> root = projects.library().rootFiles();
				var path = root.isEmpty()
						? null
						: ProjectPath.parse(root.get(0).name());
				handler.post(() -> {
					if (!destroyed && open == nothing && path != null &&
							path.isSuccess()) {
						// As tapped: it runs, and if it cannot be opened
						// that is said, and nothing more follows.
						files.open(key, path.value(), false);
					}
				});
			});
		} catch (RejectedExecutionException e) {
			// Destroyed.
		}
	}

	@Override
	public void onNewIntent(@Nullable Intent intent) {
		addShared(intent);
	}

	@NonNull
	@Override
	public OpenItem open() {
		return open;
	}

	@Override
	public void newShader() {
		// A shader is a file: of the library, or beside the
		// file of another folder that is open.
		if (files.file() == open) {
			drawer.showNew(files.key(), files.file().getPath(),
					files.file().isReadOnly());
		} else {
			drawer.showNewShader();
		}
	}

	@Override
	public void saveCopy() {
		// A shader that was not moved is copied into the library.
		if (open == shaderManager &&
				open.can(OpenItem.Action.SAVE_COPY)) {
			libraryAdd.add(shaderManager.getTitle(),
					editorFragment.getText(),
					shaderManager.getQuality(), null);
			return;
		}
		open.saveCopy();
	}

	@Override
	public void delete() {
		long id = open == shaderManager
				? shaderManager.getSelectedShaderId()
				: 0;
		open.delete();
		forgetNotMoved(id);
	}

	@Override
	public void updateWallpaper() {
		updateFileWallpaper();
	}

	@Override
	public boolean isWallpaper() {
		return open instanceof ProjectFile file
				? looks.isWallpaper(files.key(),
						file.getProject().running())
				: open.isWallpaper();
	}

	@Override
	public void showExamples() {
		drawer.showExamples();
	}

	@Override
	public void moveLibraryAgain() {
		askToMoveLibraryAgain();
	}

	@Override
	public void run() {
		if (open instanceof ProjectFile) {
			runFile();
		} else if (!open.hasText()) {
			sayNothingIsOpen();
		} else {
			host.runText(open, shaderManager.previewShaderLauncher);
		}
	}

	@Override
	public void toggleCode() {
		// Without a file there is no code to show.
		if (open.hasText()) {
			uiManager.toggleCodeVisibility();
		} else {
			sayNothingIsOpen();
		}
	}

	@Override
	public void addUniform() {
		if (open.hasText()) {
			navigationManager.goToAddUniform(
					shaderManager.addUniformLauncher);
		}
	}

	@Override
	public void onEditPaused(@NonNull String text) {
		if (ShaderEditorApp.preferences.doesRunOnChange() &&
				open instanceof ProjectFile) {
			editorFragment.runningEdit();
			files.run();
		} else if (ShaderEditorApp.preferences.doesRunOnChange() &&
				!holdsUnshownFile && open.hasText()) {
			editorFragment.runningEdit();
			shaderViewManager.setShader(text, open.getQuality());
		}
	}

	@Override
	public void onQualityChanged(float quality) {
		open.setQuality(quality);
		if (open instanceof ProjectFile file) {
			chosenFor = Arrays.asList(files.key(),
					file.getProject().running());
			chosenQuality = quality;
			looks.keepQuality(files.key(),
					file.getProject().running(), quality);
		}
	}

	@Override
	public void onFrame() {
		guard.ran();
	}

	@Override
	public boolean isAbout(@NonNull ProjectDiagnostic diagnostic) {
		if (!(open instanceof ProjectFile file)) {
			return true;
		}
		OpenProject project = file.getProject();
		return diagnostic.isAbout(file.getPath(), project.running(),
				project::reads);
	}

	@Override
	public void showErrors(@NonNull List<ProjectDiagnostic> infoLog) {
		if (open instanceof ProjectFile file) {
			files.onDiagnostics(infoLog);
			editorFragment.setErrors(infoLog, file.getPath());
		} else {
			editorFragment.setErrors(infoLog);
		}
	}

	// Nothing is drawn: ▶ goes wherever it stands.
	private void showNothing() {
		shaderViewManager.showNothing();
		setDrawing(false);
	}

	private void setDrawing(boolean drawing) {
		if (this.drawing != drawing) {
			this.drawing = drawing;
			showRunning();
		}
	}

	// What runs, as the toolbar and the drawer show it: ▶ only on a
	// file whose picture is being drawn, not on one chosen to run that
	// does not draw, such as one whose build failed or one held back
	// after a crash.
	private void showRunning() {
		ProjectFile file = files.file();
		if (file != null) {
			file.setDrawing(drawing);
		}
		showOpenTitle();
		if (file != null && file == open) {
			OpenProject project = file.getProject();
			List<ProjectPath> used = new ArrayList<>();
			for (OpenProject.Used use : project.used()) {
				used.add(use.path());
			}
			drawer.setOpenFile(files.key(), file.getPath(),
					drawing ? project.running() : null, used);
		}
	}

	// Move what is left in the database into the library, then show
	// the file the library showed last.
	private void startLibrary(boolean show) {
		libraryStart = new LibraryStart(activity, holds,
				new LibraryStart.Listener() {
					@Override
					public void onMoving() {
						movingTold = true;
						showOpenTitle();
					}

					@Override
					public void onLibrary(@NonNull String key) {
						drawer.setLibrary(key);
						// Let go of with the library, when the move
						// was asked for again and refused.
						if (!notMoved.isEmpty()) {
							drawer.setNotMoved(notMoved);
						}
						looks.onLibrary();
						files.onLibrary();
						// A screen made again ran its file before the
						// library was known: its quality is the library's.
						if (key.equals(files.key())) {
							files.run();
						}
					}

					@Override
					public void onShow(@NonNull String key,
							@NonNull ProjectPath path, boolean single,
							boolean chosen) {
						if (chosen) {
							files.show(key, path, single);
						} else {
							files.open(key, path, single);
						}
					}

					@Override
					public void onEmpty(@NonNull String key) {
						moving = false;
						showOpen();
					}

					// Said, and then there is a screen to act on: the
					// drawer, the examples, the settings.
					@Override
					public void onFailed(@NonNull String message) {
						Message.show(activity, message);
						moving = false;
						showOpen();
					}

					@Override
					public void onMoved(@NonNull String message) {
						Message.show(activity, message);
					}

					@Override
					public void onNotMoved(
							@NonNull List<LibraryMove.Failed> failed) {
						notMoved = failed;
						drawer.setNotMoved(failed);
					}
				});
		libraryStart.start(dataSource, show);
	}

	// A shader from ShaderToy as JSON was put into a shader of the
	// library. It has passes, which one file cannot hold, so it is not
	// converted in place as one tab's code is: asked, it becomes a
	// folder. The text put in is the user's and stays.
	private void askToImport(@NonNull String json) {
		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.shadertoy_import_title)
				.setMessage(R.string.shadertoy_import_message)
				.setPositiveButton(R.string.shadertoy_import,
						(d, w) -> libraryAdd.importShaderToy(json))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// Asks first: the library is put aside and made anew.
	private void askToMoveLibraryAgain() {
		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.library_move_again)
				.setMessage(R.string.library_move_again_message)
				.setPositiveButton(R.string.library_move_again, (d, w) -> {
					// Nothing may hold the library open, and no text
					// of it stays in the editor to be typed into and
					// saved to nowhere while it is put aside.
					if (open == shaderManager) {
						if (shaderManager.isModified()) {
							shaderManager.saveShader();
						}
						shaderManager.deselect();
					}
					files.leave();
					open = nothing;
					moving = true;
					movingTold = true;
					showOpen();
					drawer.closeLibrary();
					libraryStart.redo(dataSource);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// Where the shaders are files, text another app shares or opens
	// becomes a file of the library.
	private boolean addShared(@Nullable Intent intent) {
		ShaderManager.Shared shared = shaderManager.readShared(intent);
		if (shared == null) {
			return false;
		}
		libraryAdd.addShared(shared.name(), shared.text());
		return true;
	}

	@NonNull
	private NavigationDrawer createDrawer() {
		return new NavigationDrawer(activity, holds,
				new NavigationDrawer.Host() {
					@Override
					public boolean onOpenFile(@NonNull String key,
							@NonNull ProjectPath path, boolean single) {
						if (ShaderEditorApp.preferences.autoSave()) {
							open.save(false);
						}
						return files.open(key, path, single);
					}

					@Override
					public void onOpenShader(long id) {
						if (ShaderEditorApp.preferences.autoSave()) {
							open.save(false);
						}
						shaderManager.selectShader(id);
					}

					// The file in the editor, for an image's sheet.
					@Nullable
					@Override
					public InsertTexture.Target edited(@NonNull String key) {
						if (!(open instanceof ProjectFile file) ||
								!key.equals(files.key())) {
							return null;
						}
						ProjectPath path = file.getPath();
						String text = editorFragment.getText();
						OpenProject project = file.getProject();
						OpenProject.Runs runs = project.runs(path);
						// Included by what runs: never the target.
						boolean included = false;
						for (OpenProject.Used use : project.used()) {
							included |= use.path().equals(path) &&
									use.role() != null &&
									use.role().kind() == FileRole.Kind.INCLUDE;
						}
						return new InsertTexture.Target(path,
								text != null ? text : "",
								ShaderLanguage.of(path.toString()),
								runs == OpenProject.Runs.UNKNOWN
										? null
										: runs == OpenProject.Runs.YES,
								included, file.isReadOnly());
					}

					// As Add uniform adds a statement: one step to undo.
					// The caret goes to the sampler's line.
					@Override
					public void insertTexture(@NonNull String statement,
							@NonNull String name) {
						editorFragment.addUniform(statement);
						String text = editorFragment.getText();
						if (text == null) {
							return;
						}
						int at = text.indexOf("uniform sampler2D " + name + ";");
						if (at > -1) {
							int line = 1;
							for (int i = 0; i < at; ++i) {
								if (text.charAt(i) == '\n') {
									++line;
								}
							}
							editorFragment.navigateToLine(line);
						}
					}
				});
	}

	@NonNull
	@Contract(" -> new")
	private ProjectFileManager.Listener createProjectFileListener() {
		return new ProjectFileManager.Listener() {
			@Override
			public void onShown(@NonNull ProjectFile file) {
				if (open == shaderManager) {
					// As when another shader is chosen.
					if (shaderManager.isModified()) {
						shaderManager.saveShader();
					}
					shaderManager.deselect();
				}
				open = file;
				file.setDrawing(drawing);
				moving = false;
				showOpen();
				// The last build's, until the next one reports.
				editorFragment.setErrors(file.getProject().diagnostics(),
						file.getPath());
				onChanged();
			}

			// What comes next is the library's last shader, until there
			// is a screen for no file open.
			@Override
			public void onGone() {
				// A file of the library that went is followed by the
				// newest it has, as a start whose file went (NAVIGATION
				// 11.4); a file of another folder by nothing.
				String gone = open instanceof ProjectFile file
						? file.getProject().id()
						: null;
				if (holdsUnshownFile) {
					// Its text is not a shader of the library to save.
					holdsUnshownFile = false;
					shaderManager.deselect();
				}
				// No shader of the database stands in for a file.
				shaderManager.deselect();
				open = nothing;
				moving = false;
				forgetHeldMessage();
				showOpen();
				if (gone != null && holds.projects().isLibrary(gone)) {
					showNewest(gone);
				}
			}

			@Override
			public void onMoved(@NonNull String key) {
				drawer.reread(key);
			}

			@Override
			public void onChanged() {
				showRunning();
			}

			@Override
			public void onSaved() {
				uiManager.showNotice(activity.getText(R.string.shader_saved),
						ShaderScreen.SAVED_NOTICE_MILLIS);
				ProjectFile file = files.file();
				if (file != null) {
					looks.saved(files.key(),
							file.getProject().running());
					drawer.saved(files.key(), file.getPath());
				}
			}

			@Override
			public void onFailed(@NonNull String message) {
				Message.show(activity, message);
			}

			@Override
			public void onReadOnly(@NonNull ProjectFile file) {
				String name = file.getProject().name();
				// Its action is the way out of a folder that cannot be
				// written: it stays long enough to be read and reached,
				// which a long message's 2.75 s are not.
				Message.make(activity, activity.getString(
								R.string.file_read_only, name != null
										? name
										: file.getTitle()),
						READ_ONLY_MILLIS)
						.setAction(R.string.duplicate_shader,
								v -> file.saveCopy())
						.show();
			}

			// What runs and what it reads, else the file alone.
			@Override
			public void onSaveCopy(@NonNull ProjectFile file) {
				OpenProject project = file.getProject();
				ProjectPath running = project.running();
				List<ProjectPath> paths = new ArrayList<>();
				paths.add(running != null ? running : file.getPath());
				if (running != null) {
					for (OpenProject.Used use : project.used()) {
						if (!paths.contains(use.path())) {
							paths.add(use.path());
						}
					}
				}
				libraryAdd.saveCopy(project, paths.get(0), paths);
			}

			// Long enough to be read after the drawer closed, and to be
			// acted on.
			@Override
			public void onNeedsNewEditor(@NonNull Runnable turnOn) {
				Message.make(activity,
								activity.getText(R.string.file_needs_new_editor),
								NEEDS_NEW_EDITOR_MILLIS)
						.setAction(R.string.turn_on, v -> turnOn.run())
						.show();
			}
		};
	}

	private void showOpenTitle() {
		if (!open.hasText()) {
			uiManager.setToolbarTitle(open.getTitle(), moving && movingTold
					? activity.getString(R.string.library_moving)
					: null, false);
			return;
		}
		// Where the shaders are files, one of the database was not moved.
		String subtitle = open == shaderManager &&
				shaderManager.getSelectedShaderId() > 0
				? activity.getString(R.string.library_not_moved_line)
				: open.getSubtitle();
		uiManager.setToolbarTitle(open.getTitle(), subtitle,
				open.isModified());
	}

	// Set what is open as the wallpaper: a file of a folder, or a
	// shader that was not moved while a file may be the wallpaper.
	private void updateFileWallpaper() {
		if (open instanceof ProjectFile file) {
			// What the editor shows, as one build.
			looks.setWallpaper(files.key(), file.getProject(),
					file.getQuality(), problem -> {
						if (problem != null) {
							Message.show(activity, activity.getString(
									R.string.cannot_install_wallpaper,
									problem));
						} else {
							host.showWallpaper();
						}
					});
			return;
		}
		// A shader that was not moved; a file may be the wallpaper.
		OpenItem item = open;
		looks.setDatabaseWallpaper(item::setAsWallpaper, problem -> {
			if (problem != null) {
				Message.show(activity, activity.getString(
						R.string.cannot_install_wallpaper, problem));
			} else {
				host.showWallpaper();
			}
		});
	}

	// What is open decides what stands where the code is: the editor,
	// or, with nothing open, a cover over it with a line that says so
	// (NAVIGATION 6.7). While the start has not shown a file yet the
	// cover is empty: nothing is typed into nowhere, and nothing runs.
	private void showOpen() {
		boolean text = open.hasText();
		TextView cover = activity.findViewById(R.id.no_file);
		boolean covered = cover.getVisibility() == View.VISIBLE;
		cover.setText(moving ? "" : activity.getString(R.string.no_file_open));
		cover.setVisibility(text ? View.GONE : View.VISIBLE);
		// A quality is chosen for what runs, a key inserts into a text.
		activity.findViewById(R.id.quality).setVisibility(text
				? View.VISIBLE
				: View.GONE);
		extraKeysManager.setHasText(text);
		// The editor's view is made after this screen is: it must not
		// come to lie over the cover.
		View editor = editorFragment.getView();
		if (!text) {
			cover.bringToFront();
			if (editor != null) {
				SoftKeyboard.hide(activity, editor);
			}
			// Also before the editor's view is there: the picture's
			// limit follows it.
			editorFragment.setCovered(true);
			if (editor != null) {
				// Tells the editor that nothing is reported, so not
				// before its view is there; nothing ran until then.
				showNothing();
			}
		} else {
			editorFragment.setCovered(false);
			if (covered && editor != null && hideCodeOnShow) {
				hideCodeOnShow = false;
				uiManager.toggleCodeVisibility();
			}
		}
		showOpenTitle();
	}

	// What is open, as the one thing kept of it when the screen is made
	// again.
	@NonNull
	private OpenState openState() {
		if (open == shaderManager &&
				shaderManager.getSelectedShaderId() > 0) {
			return OpenState.notMoved(shaderManager.getSelectedShaderId(),
					shaderManager.getTitle(), shaderManager.getQuality(),
					shaderManager.isModified());
		}
		OpenState file = files.state();
		return file != null ? file
				: !moving ? OpenState.NOTHING
				: movingTold ? OpenState.MOVING : OpenState.STARTING;
	}

	// The shader of the database that was open is gone: what the
	// library showed last comes in its place, or nothing.
	private void showLibraryFile() {
		open = nothing;
		moving = true;
		showOpen();
		libraryStart.show();
	}

	// A shader that was not moved and is deleted is not listed anymore.
	private void forgetNotMoved(long id) {
		if (id <= 0) {
			return;
		}
		List<LibraryMove.Failed> left = new ArrayList<>();
		for (LibraryMove.Failed failed : notMoved) {
			if (failed.id() != id) {
				left.add(failed);
			}
		}
		notMoved = left;
		drawer.setNotMoved(left);
	}

	// A tap that has nothing to act on says so.
	private void sayNothingIsOpen() {
		Message.show(activity, moving
				? R.string.library_moving
				: R.string.no_file_open);
	}

	// The project of a file runs only behind the code.
	private void runFile() {
		host.clearErrors();
		if (ShaderEditorApp.preferences.doesSaveOnRun()) {
			open.save(false);
		}
		if (ShaderEditorApp.preferences.doesRunInBackground()) {
			runHeld();
		} else {
			Message.show(activity, R.string.file_runs_behind_code);
		}
	}

	// The user asks for it: also a file the app ended on runs.
	private void runHeld() {
		guard.release();
		forgetHeldMessage();
		files.run();
	}

	// The message is about the file that runs behind the code shown.
	private void forgetHeldMessage() {
		if (heldMessage != null) {
			heldMessage.dismiss();
			heldMessage = null;
		}
	}

	// Return whether the file that runs may be handed to the engine.
	// A file the app ended on is not, until the user asks for it: the
	// message that says so stays, with the way to run it. Any other
	// is pending from here until its first frame.
	private boolean mayRun(@NonNull String key, @NonNull ProjectPath running) {
		if (!guard.holds(key, running.toString())) {
			forgetHeldMessage();
			// A screen that is not in front draws no frame.
			if (activity.getLifecycle().getCurrentState().isAtLeast(
					Lifecycle.State.RESUMED)) {
				guard.running(key, running.toString());
			}
			return true;
		}
		showNothing();
		if (heldMessage == null || !heldMessage.isShownOrQueued()) {
			heldMessage = Message.make(activity, activity.getString(
							R.string.file_not_run_after_crash,
							running.toString()),
					Snackbar.LENGTH_INDEFINITE)
					.setAction(R.string.run_code, v -> runHeld());
			heldMessage.show();
		}
		return false;
	}
}
