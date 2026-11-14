package de.markusfisch.android.shadereditor.activity;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.managers.ExtraKeysManager;
import de.markusfisch.android.shadereditor.activity.managers.MainMenuManager;
import de.markusfisch.android.shadereditor.activity.managers.LibraryAdd;
import de.markusfisch.android.shadereditor.activity.managers.LibraryLooks;
import de.markusfisch.android.shadereditor.activity.managers.LibraryStart;
import de.markusfisch.android.shadereditor.activity.managers.NavigationDrawer;
import de.markusfisch.android.shadereditor.activity.managers.NavigationManager;
import de.markusfisch.android.shadereditor.activity.managers.NothingOpen;
import de.markusfisch.android.shadereditor.activity.managers.OpenItem;
import de.markusfisch.android.shadereditor.activity.managers.OpenState;
import de.markusfisch.android.shadereditor.activity.managers.ProjectFile;
import de.markusfisch.android.shadereditor.activity.managers.ProjectFileManager;
import de.markusfisch.android.shadereditor.activity.managers.ProjectHolds;
import de.markusfisch.android.shadereditor.activity.managers.RunGuard;
import de.markusfisch.android.shadereditor.activity.managers.ShaderList;
import de.markusfisch.android.shadereditor.activity.managers.ShaderListManager;
import de.markusfisch.android.shadereditor.activity.managers.ShaderManager;
import de.markusfisch.android.shadereditor.activity.managers.ShaderViewManager;
import de.markusfisch.android.shadereditor.activity.managers.UIManager;
import de.markusfisch.android.shadereditor.adapter.Samples;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.project.LibraryMove;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.service.ShaderWallpaperService;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;
import de.markusfisch.android.shadereditor.widget.Message;

public class MainActivity extends AppCompatActivity {
	private static final String OPEN = "open";
	private static final String CODE_VISIBLE = "code_visible";
	private static final String TOOLBAR_TITLE = "toolbar_title";
	// How long an explicit save is told in the toolbar's second line.
	private static final long SAVED_NOTICE_MILLIS = 2000;
	private static final int NEEDS_NEW_EDITOR_MILLIS = 10000;
	private static final String CRASH_RECOVERY_COMMENT = """
			// Shader Editor disabled this shader because it caused a crash while loading.
			// Fix the shader and remove the #if 0 wrapper to re-enable it.
			""";

	private EditorFragment editorFragment;
	private UIManager uiManager;
	private ShaderManager shaderManager;
	// What the editor has open. shaderManager is only used directly to
	// open and create database shaders.
	private OpenItem open;
	private ShaderList shaderListManager;
	private ShaderViewManager shaderViewManager;
	private ExtraKeysManager extraKeysManager;
	private NavigationManager navigationManager;
	// The files of folders, with project mode only, else null.
	@Nullable
	private ProjectHolds holds;
	@Nullable
	private ProjectFileManager files;
	private DataSource dataSource;
	private boolean isInitialLoad = false;
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
	// What was open before the screen was made again; null for a new
	// screen, and where the shaders are rows of the database.
	@Nullable
	private OpenState restored;

	@Override
	protected void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_main);
		SystemBarMetrics.initMainLayout(this, null);

		dataSource = Database.getInstance(this).getDataSource();
		recoverFromCrashIfNeeded();

		editorFragment = state == null ? new EditorFragment() : (EditorFragment)
				getSupportFragmentManager().findFragmentByTag(EditorFragment.TAG);

		if (state == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.content_frame, editorFragment, EditorFragment.TAG)
					.commit();
		}

		navigationManager = new NavigationManager(this);
		ShaderViewManager.Listener shaderViewListener =
				createShaderViewListener();
		shaderViewManager = new ShaderViewManager(this,
				findViewById(R.id.preview),
				findViewById(R.id.quality),
				shaderViewListener);
		extraKeysManager = new ExtraKeysManager(this,
				findViewById(android.R.id.content),
				editorFragment::insert);
		uiManager = new UIManager(this,
				editorFragment,
				extraKeysManager,
				shaderViewManager);
		if (getResources().getBoolean(R.bool.project_mode)) {
			holds = new ProjectHolds(new Projects(this));
			libraryAdd = new LibraryAdd(this, holds.projects(), dataSource,
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
							Message.show(MainActivity.this, message);
							// The start left the screen to what was
							// to be added: the library's file, then.
							if (!open.hasText() && moving) {
								libraryStart.show();
							}
						}
					});
			looks = new LibraryLooks(this, holds.projects(), shaderViewManager,
					() -> shaderListManager.loadShadersAsync(),
					problem -> Message.show(this, getString(
							R.string.library_not_kept, problem)));
			// The quality chosen is told once it changes, also when the
			// spinner first shows what it holds: what it holds now.
			shaderViewManager.setQuality(1f);
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
			files = new ProjectFileManager(this, editorFragment, holds,
					createProjectFileListener(),
					(preparation, nothingRuns, quality) -> {
						if (preparation != null) {
							ProjectFile file = files.file();
							String key = files.key();
							ProjectPath running = file != null
									? file.getProject().running()
									: null;
							looks.quality(files.key(), running, quality,
									kept -> {
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
							shaderViewManager.showNothing();
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
		}
		MainMenuManager.ShaderActions shaderActions =
				createShaderActions(extraKeysManager);
		shaderListManager = createShaderList(shaderActions);
		shaderManager = new ShaderManager(this,
				editorFragment,
				shaderViewManager,
				shaderListManager,
				navigationManager,
				dataSource,
				files != null,
				createShaderViewListener(),
				this::showOpenTitle,
				() -> uiManager.showNotice(getText(R.string.shader_saved),
						SAVED_NOTICE_MILLIS));
		nothing = new NothingOpen(getString(R.string.nav_shaders));
		// Where the shaders are files, nothing is open until the start
		// shows a file: no shader of the database stands in for it.
		open = files != null ? nothing : shaderManager;
		if (files != null) {
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

		MainMenuManager mainMenuManager = new MainMenuManager(
				this,
				createEditorActions(),
				shaderActions,
				createNavigationActions());

		uiManager.setupToolbar(mainMenuManager::show,
				v -> this.runShader(),
				v -> {
					// Without a file there is no code to show.
					if (open.hasText()) {
						uiManager.toggleCodeVisibility();
					} else {
						sayNothingIsOpen();
					}
				},
				v -> editorFragment.showErrors());

		// Only load the last shader when no incoming text was opened,
		// or it would replace that text.
		boolean openedText = libraryAdd != null
				? addShared(getIntent())
				: shaderManager.handleSendText(getIntent());
		// Where the shaders are files, the start opens the library.
		isInitialLoad = state == null && !openedText && files == null;
		restored = files != null && state != null
				? OpenState.decode(state.getString(OPEN))
				: null;
		holdsUnshownFile = restored != null &&
				restored.kind() == OpenState.Kind.FILE &&
				!ShaderEditorApp.preferences.useNewEditor();

		editorFragment.setOnEditPausedListener(text -> {
			if (ShaderEditorApp.preferences.doesRunOnChange() &&
					open instanceof ProjectFile) {
				editorFragment.runningEdit();
				files.run();
			} else if (ShaderEditorApp.preferences.doesRunOnChange() &&
					!holdsUnshownFile && open.hasText()) {
				editorFragment.runningEdit();
				shaderViewManager.setShader(text, open.getQuality());
			}
		});
		editorFragment.setOnTextModifiedListener(() -> open.onTextModified());
		editorFragment.setCodeCompletionListener(extraKeysManager::setCompletions);
		if (files != null) {
			// A screen that had a file, a shader or nothing open comes
			// back to that; one that had not come that far shows what
			// the library shows, as a cold start does, unless text
			// that was shared comes instead.
			boolean show = !openedText && (restored == null ||
					restored.showsLibrary());
			// Nothing stands where the code is until what was open is
			// back, or the start has shown a file.
			moving = true;
			movingTold = restored != null &&
					restored.kind() == OpenState.Kind.MOVING;
			showOpen();
			startLibrary(show);
		}
	}

	// Move what is left in the database into the library, then show
	// the file the library showed last.
	private void startLibrary(boolean show) {
		libraryStart = new LibraryStart(this, holds.projects(),
				new LibraryStart.Listener() {
					@Override
					public void onMoving() {
						movingTold = true;
						showOpenTitle();
					}

					@Override
					public void onLibrary(@NonNull String key) {
						if (shaderListManager instanceof NavigationDrawer
								drawer) {
							drawer.setLibrary(key);
						}
						looks.setLibrary(key);
						files.setLibrary(key);
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
						Message.show(MainActivity.this, message);
						moving = false;
						showOpen();
					}

					@Override
					public void onMoved(@NonNull String message) {
						Message.show(MainActivity.this, message);
					}

					@Override
					public void onNotMoved(
							@NonNull List<LibraryMove.Failed> failed) {
						notMoved = failed;
						if (shaderListManager instanceof NavigationDrawer
								drawer) {
							drawer.setNotMoved(failed);
						}
					}
				});
		libraryStart.start(dataSource, show);
	}

	// Asks first: the library is put aside and made anew.
	private void moveLibraryAgain() {
		new MaterialAlertDialogBuilder(this)
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
					if (shaderListManager instanceof NavigationDrawer
							drawer) {
						drawer.closeLibrary();
					}
					libraryStart.redo(dataSource);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	@Override
	protected void onRestoreInstanceState(@NonNull Bundle state) {
		super.onRestoreInstanceState(state);
		if (files == null) {
			shaderManager.restoreState(state);
		} else if (restored != null && restored.isFile()) {
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
		if (!state.getBoolean(CODE_VISIBLE, true)) {
			if (open.hasText()) {
				uiManager.toggleCodeVisibility();
			} else {
				hideCodeOnShow = true;
			}
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		if (files == null) {
			shaderManager.saveState(state);
		} else {
			state.putString(OPEN, openState().encode());
		}
		// With nothing open the editor's view is gone for that reason.
		state.putBoolean(CODE_VISIBLE, open.hasText()
				? editorFragment.isCodeVisible()
				: !hideCodeOnShow);
		state.putCharSequence(TOOLBAR_TITLE, uiManager.getToolbarTitle());
		super.onSaveInstanceState(state);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (files != null) {
			// The editor's view exists by now.
			if (!open.hasText()) {
				showOpen();
			}
			files.onStart();
		}
	}

	@Override
	protected void onStop() {
		if (files != null) {
			files.onStop();
		}
		super.onStop();
	}

	@Override
	protected void onResume() {
		super.onResume();
		uiManager.updateUiToPreferences();
		shaderListManager.loadShadersAsync();
		shaderViewManager.onResume();
	}

	@Override
	protected void onPause() {
		if (ShaderEditorApp.preferences.autoSave()) {
			open.save(false);
		}
		ShaderEditorApp.preferences.setPendingCrashShaderId(0);
		if (guard != null) {
			guard.ran();
		}
		super.onPause();
		shaderViewManager.onPause();
	}

	@Override
	protected void onDestroy() {
		// The screen made again after a configuration change takes them.
		if (holds != null && !isFinishing()) {
			holds.keepOpen();
		}
		if (files != null) {
			files.destroy();
		}
		if (libraryStart != null) {
			libraryStart.destroy();
		}
		if (libraryAdd != null) {
			libraryAdd.destroy();
		}
		if (looks != null) {
			looks.destroy();
		}
		if (shaderManager != null) {
			shaderManager.destroy();
		}
		if (shaderListManager != null) {
			shaderListManager.destroy();
		}
		if (extraKeysManager != null) {
			extraKeysManager.destroy();
		}
		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (libraryAdd != null) {
			addShared(intent);
		} else {
			shaderManager.handleSendText(intent);
		}
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

	@Override
	protected void onPostCreate(Bundle state) {
		// Activity.onPostCreate() sets the toolbar to the activity label,
		// which would replace a title set in onCreate(), for example the
		// name of an opened file.
		CharSequence title = state != null
				? state.getCharSequence(TOOLBAR_TITLE)
				: uiManager.getToolbarTitle();
		super.onPostCreate(state);
		uiManager.setToolbarTitle(title, open.getSubtitle(),
				open.isModified());
		// A file that comes back keeps its title until it is shown.
		if (!open.hasText() && (restored == null ||
				restored.showsLibrary())) {
			showOpenTitle();
		}
		if (uiManager.drawerToggle != null) {
			uiManager.drawerToggle.syncState();
		}
	}

	// The one place that chooses between the old and the new drawer.
	@NonNull
	private ShaderList createShaderList(
			@NonNull MainMenuManager.ShaderActions shaderActions) {
		if (!getResources().getBoolean(R.bool.project_mode)) {
			return new ShaderListManager(this,
					findViewById(R.id.shaders),
					dataSource,
					createShaderListListener());
		}
		return new NavigationDrawer(this, holds,
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

					// Opened where it is, in the read-only examples;
					// Save a copy keeps it.
					@Override
					public void onOpenExample(@NonNull Samples.Sample sample) {
						ProjectPath path = ProjectPath.parse(sample.path())
								.value();
						holds.projects().examples(getString(
								R.string.load_sample), key -> {
							if (isFinishing() || isDestroyed()) {
								return;
							} else if (key == null) {
								Message.show(MainActivity.this, getString(
										R.string.file_cannot_open,
										sample.path(),
										"its session cannot be kept"));
								return;
							}
							if (ShaderEditorApp.preferences.autoSave()) {
								open.save(false);
							}
							files.open(key, path, false);
						});
					}
				});
	}

	@NonNull
	@Contract(" -> new")
	private ShaderListManager.Listener createShaderListListener() {
		return new ShaderListManager.Listener() {
			@Override
			public void onShaderSelected(long id) {
				isInitialLoad = false;
				if (ShaderEditorApp.preferences.autoSave()) {
					open.save(false);
				}
				uiManager.closeDrawers();
				shaderManager.selectShader(id);
			}

			@Override
			public void onShaderRenamed(long id, @NonNull String name) {
				shaderManager.shaderRenamed(id, name);
				shaderListManager.loadShadersAsync();
			}

			@Override
			public void onAllShadersDeleted() {
				isInitialLoad = false;
				shaderManager.selectShader(0);
			}

			@Override
			public void onShadersLoaded(@NonNull List<DataRecords.ShaderInfo> shaders) {
				if (isInitialLoad && !shaders.isEmpty()) {
					// Try to load the last opened shader.
					// NOTE: This assumes a method like `getLastOpenedShader()` exists
					// in your SharedPreferences helper class.
					long lastOpenedId = ShaderEditorApp.preferences.getLastOpenedShader();
					long idToLoad = 0;

					// Check if the last opened shader still exists in the list.
					if (lastOpenedId > 0) {
						for (DataRecords.ShaderInfo shader : shaders) {
							if (shader.id() == lastOpenedId) {
								idToLoad = lastOpenedId;
								break;
							}
						}
					}

					// If no last-opened shader was found (or it was deleted),
					// fall back to the first shader in the list.
					if (idToLoad == 0) {
						idToLoad = shaders.get(0).id();
					}

					shaderManager.selectShader(idToLoad);
				}
				isInitialLoad = false;
			}
		};
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
			}

			@Override
			public void onMoved(@NonNull String key) {
				if (shaderListManager instanceof NavigationDrawer drawer) {
					drawer.reread(key);
				}
			}

			@Override
			public void onChanged() {
				showOpenTitle();
				ProjectFile file = files.file();
				if (file != null && file == open &&
						shaderListManager instanceof NavigationDrawer drawer) {
					OpenProject project = file.getProject();
					List<ProjectPath> used = new ArrayList<>();
					for (OpenProject.Used use : project.used()) {
						used.add(use.path());
					}
					drawer.setOpenFile(files.key(), file.getPath(),
							project.running(), used);
				}
			}

			@Override
			public void onSaved() {
				uiManager.showNotice(getText(R.string.shader_saved),
						SAVED_NOTICE_MILLIS);
				ProjectFile file = files.file();
				if (file != null) {
					looks.saved(files.key(),
							file.getProject().running());
				}
			}

			@Override
			public void onFailed(@NonNull String message) {
				Message.show(MainActivity.this, message);
			}

			@Override
			public void onReadOnly(@NonNull ProjectFile file) {
				String name = file.getProject().name();
				Message.make(MainActivity.this, getString(
								R.string.file_read_only, name != null
										? name
										: file.getTitle()),
						Snackbar.LENGTH_LONG)
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
				if (libraryAdd != null) {
					libraryAdd.saveCopy(project, paths.get(0), paths);
				}
			}

			// Long enough to be read after the drawer closed, and to be
			// acted on.
			@Override
			public void onNeedsNewEditor(@NonNull Runnable turnOn) {
				Message.make(MainActivity.this,
								getText(R.string.file_needs_new_editor),
								NEEDS_NEW_EDITOR_MILLIS)
						.setAction(R.string.turn_on, v -> turnOn.run())
						.show();
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private ShaderViewManager.Listener createShaderViewListener() {
		return new ShaderViewManager.Listener() {
			private final MaterialButton showErrors = findViewById(R.id.show_errors);
			private final View mainCoordinator = findViewById(R.id.main_coordinator);
			private final View toolbar = findViewById(R.id.toolbar);
			// Warnings show once, not again on every run.
			private List<ProjectDiagnostic> shownWarnings = List.of();

			@Override
			public void onFramesPerSecond(int fps) {
				if (fps > 0) {
					ShaderEditorApp.preferences.setPendingCrashShaderId(0);
					if (guard != null) {
						guard.ran();
					}
					uiManager.setFrameRate(fps + " fps");
				}
			}

			@Override
			public void onLastPicture(boolean shown) {
				runOnUiThread(() -> editorFragment.setShowsLastPicture(shown));
			}

			@Override
			public void onInfoLog(@NonNull List<ProjectDiagnostic> infoLog) {
				runOnUiThread(() -> {
					if (open instanceof ProjectFile file) {
						files.onDiagnostics(infoLog);
						editorFragment.setErrors(infoLog, file.getPath());
					} else {
						editorFragment.setErrors(infoLog);
					}
					showErrors.setVisibility(infoLog.isEmpty()
							? View.GONE
							: View.VISIBLE);
					showErrors.setIconResource(editorFragment.hasErrors()
							? R.drawable.ic_error
							: R.drawable.ic_warning);
					if (editorFragment.hasErrors()) {
						shownWarnings = List.of();
						for (var diagnostic : infoLog) {
							if (diagnostic.severity() ==
									ProjectDiagnostic.Severity.ERROR) {
								if (!editorFragment.showsErrorMessage(
										diagnostic)) {
									showError(diagnostic.toString());
								}
								break;
							}
						}
					} else if (!infoLog.equals(shownWarnings)) {
						shownWarnings = infoLog;
						if (!infoLog.isEmpty()) {
							showError(getString(R.string.warning_message,
									infoLog.get(0).toString()));
						}
					}
				});
			}

			@Override
			public void onQualityChanged(float quality) {
				open.setQuality(quality);
				if (open instanceof ProjectFile file) {
					looks.keepQuality(files.key(),
							file.getProject().running(), quality);
				}
			}

			private void showError(String error) {
				Snackbar snackbar = Snackbar.make(mainCoordinator,
								error,
								Snackbar.LENGTH_LONG)
						.setAction(R.string.details,
								v -> editorFragment.showErrors());
				moveSnackBarOverActionBar(snackbar.getView());
				snackbar.show();
			}

			private void moveSnackBarOverActionBar(View snackbarView) {
				if (snackbarView == null) {
					return;
				}

				ViewGroup.LayoutParams layoutParams =
						snackbarView.getLayoutParams();
				if (!(layoutParams instanceof
						CoordinatorLayout.LayoutParams params)) {
					return;
				}

				params.gravity = Gravity.TOP;

				int topInset = 0;
				WindowInsetsCompat rootInsets =
						ViewCompat.getRootWindowInsets(mainCoordinator);
				if (rootInsets != null) {
					topInset = rootInsets.getInsets(
							WindowInsetsCompat.Type.systemBars()).top;
				}
				if (topInset == 0 && toolbar != null) {
					topInset = toolbar.getPaddingTop();
				}
				params.topMargin = topInset;
				snackbarView.setLayoutParams(params);
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.EditorActions createEditorActions() {
		return new MainMenuManager.EditorActions() {
			@Override
			public void onUndo() {
				editorFragment.undo();
			}

			@Override
			public void onRedo() {
				editorFragment.redo();
			}

			@Override
			public boolean canUndo() {
				return open.hasText() && editorFragment != null &&
						editorFragment.canUndo();
			}

			@Override
			public boolean canRedo() {
				return open.hasText() && editorFragment != null &&
						editorFragment.canRedo();
			}
		};
	}

	@NonNull
	@Contract("_ -> new")
	private MainMenuManager.ShaderActions createShaderActions(ExtraKeysManager extraKeysManager) {
		return new MainMenuManager.ShaderActions() {
			@Override
			public void onAddShader() {
				if (shaderListManager instanceof NavigationDrawer drawer) {
					// A shader is a file: of the library, or beside the
					// file of another folder that is open.
					if (files.file() == open) {
						drawer.showNew(files.key(), files.file().getPath());
					} else {
						drawer.showNewShader();
					}
					return;
				}
				long defaultId = ShaderEditorApp.preferences.getDefaultNewShader();
				if (defaultId > 0 &&
						dataSource.shader.getShader(defaultId) != null) {
					shaderManager.selectCopy(defaultId);
				} else {
					shaderManager.createShaderFromResource(null,
							R.raw.new_shader, R.drawable.thumbnail_new_shader,
							1f);
				}
			}

			@Override
			public void onSaveShader() {
				open.save(true);
			}

			@Override
			public void onDuplicateShader() {
				// A shader that was not moved is copied into the library.
				if (libraryAdd != null && open == shaderManager &&
						open.can(OpenItem.Action.SAVE_COPY)) {
					libraryAdd.add(shaderManager.getTitle(),
							editorFragment.getText(),
							shaderManager.getQuality(), null);
					return;
				}
				open.saveCopy();
			}

			@Override
			public void onDeleteShader() {
				if (!open.can(OpenItem.Action.DELETE)) {
					return;
				}
				new MaterialAlertDialogBuilder(MainActivity.this)
						.setTitle(getString(R.string.delete_shader_title,
								open.getTitle()))
						.setPositiveButton(R.string.delete_shader,
								(dialog, which) -> {
									long id = open == shaderManager
											? shaderManager.getSelectedShaderId()
											: 0;
									open.delete();
									forgetNotMoved(id);
								})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}

			@Override
			public void onShareShader() {
				open.share();
			}

			@Override
			public void onUpdateWallpaper() {
				updateWallpaper();
			}

			@Override
			public void onRunThisFile() {
				open.runThisFile();
			}

			@Override
			public void onToggleExtraKeys() {
				extraKeysManager.setVisible(ShaderEditorApp.preferences.toggleShowExtraKeys());
			}


			@Override
			public boolean isWallpaper() {
				return open instanceof ProjectFile file
						? looks.isWallpaper(files.key(),
								file.getProject().running())
						: open.isWallpaper();
			}

			@Override
			public boolean offers(@NonNull OpenItem.Action action) {
				return open.offers(action);
			}

			@Override
			public boolean hasText() {
				return open.hasText();
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.NavigationActions createNavigationActions() {
		return new MainMenuManager.NavigationActions() {
			@Override
			public void onAddUniform() {
				if (open.hasText()) {
					navigationManager.goToAddUniform(
							shaderManager.addUniformLauncher);
				}
			}

			@Override
			public void onLoadSample() {
				if (shaderListManager instanceof NavigationDrawer drawer) {
					drawer.showExamples();
					return;
				}
				navigationManager.goToLoadSample(shaderManager.loadSampleLauncher);
			}

			@Override
			public void onShowSettings() {
				navigationManager.goToPreferences();
			}

			@Override
			public void onShowFaq() {
				navigationManager.goToFaq();
			}

			@Override
			public void onMoveLibraryAgain() {
				if (libraryStart != null) {
					moveLibraryAgain();
				}
			}
		};
	}

	private void showOpenTitle() {
		if (!open.hasText()) {
			uiManager.setToolbarTitle(open.getTitle(), moving && movingTold
					? getString(R.string.library_moving)
					: null, false);
			return;
		}
		// Where the shaders are files, one of the database was not moved.
		String subtitle = files != null && open == shaderManager &&
				shaderManager.getSelectedShaderId() > 0
				? getString(R.string.library_not_moved_line)
				: open.getSubtitle();
		uiManager.setToolbarTitle(open.getTitle(), subtitle,
				open.isModified());
	}

	private void updateWallpaper() {
		if (!open.can(OpenItem.Action.WALLPAPER)) {
			return;
		}
		if (open instanceof ProjectFile file) {
			// What the editor shows, as one build.
			looks.setWallpaper(files.key(), file.getProject(),
					file.getQuality(), problem -> {
						if (problem != null) {
							Message.show(this, getString(
									R.string.cannot_install_wallpaper,
									problem));
						} else {
							showWallpaper();
						}
					});
			return;
		}
		if (looks != null) {
			// A shader that was not moved; a file may be the wallpaper.
			OpenItem item = open;
			looks.setDatabaseWallpaper(item::setAsWallpaper, problem -> {
				if (problem != null) {
					Message.show(this, getString(
							R.string.cannot_install_wallpaper, problem));
				} else {
					showWallpaper();
				}
			});
			return;
		}
		open.setAsWallpaper();
		showWallpaper();
	}

	// What is open decides what stands where the code is: the editor,
	// or, with nothing open, a cover over it with a line that says so
	// (NAVIGATION 6.7). While the start has not shown a file yet the
	// cover is empty: nothing is typed into nowhere, and nothing runs.
	private void showOpen() {
		boolean text = open.hasText();
		TextView cover = findViewById(R.id.no_file);
		boolean covered = cover.getVisibility() == View.VISIBLE;
		cover.setText(moving ? "" : getString(R.string.no_file_open));
		cover.setVisibility(text ? View.GONE : View.VISIBLE);
		// The editor's view is made after this screen is: it must not
		// come to lie over the cover.
		View editor = editorFragment.getView();
		if (!text) {
			cover.bringToFront();
			if (editor != null) {
				SoftKeyboard.hide(this, editor);
				editor.setVisibility(View.GONE);
				// Tells the editor that nothing is reported, so not
				// before its view is there; nothing ran until then.
				shaderViewManager.showNothing();
			}
		} else if (covered && editor != null) {
			editor.setVisibility(View.VISIBLE);
			if (hideCodeOnShow) {
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
		if (id <= 0 || !(shaderListManager instanceof NavigationDrawer
				drawer)) {
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
		Message.show(this, moving
				? R.string.library_moving
				: R.string.no_file_open);
	}

	// Pick the live wallpaper if it is not, else say it is set.
	private void showWallpaper() {
		int messageId = R.string.wallpaper_set;
		if (!WallpaperManager.getInstance(this).isWallpaperSupported()) {
			messageId = R.string.cannot_set_wallpaper;
		} else if (!ShaderWallpaperService.isRunning()) {
			Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
					.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
							new ComponentName(this, ShaderWallpaperService.class));
			try {
				startActivity(intent);
				return;
			} catch (Exception e) {
				messageId = R.string.pick_live_wallpaper_manually;
			}
		}
		Message.show(this, messageId);
	}

	private void runShader() {
		if (open instanceof ProjectFile) {
			runFile();
			return;
		} else if (!open.hasText()) {
			sayNothingIsOpen();
			return;
		}
		String src = editorFragment.getText();
		clearErrors();
		if (ShaderEditorApp.preferences.doesSaveOnRun()) {
			PreviewActivity.renderStatus.reset();
			open.save(false);
		}
		if (ShaderEditorApp.preferences.doesRunInBackground()) {
			shaderViewManager.setShader(src, open.getQuality());
		} else {
			navigationManager.showPreview(src, open.getQuality(),
					shaderManager.previewShaderLauncher);
		}
	}

	// The project of a file runs only behind the code.
	private void runFile() {
		clearErrors();
		if (ShaderEditorApp.preferences.doesSaveOnRun()) {
			open.save(false);
		}
		if (ShaderEditorApp.preferences.doesRunInBackground()) {
			runHeld();
		} else {
			Message.show(this, R.string.file_runs_behind_code);
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
			if (getLifecycle().getCurrentState().isAtLeast(
					Lifecycle.State.RESUMED)) {
				guard.running(key, running.toString());
			}
			return true;
		}
		shaderViewManager.showNothing();
		if (heldMessage == null || !heldMessage.isShownOrQueued()) {
			heldMessage = Message.make(this, getString(
							R.string.file_not_run_after_crash,
							running.toString()),
					Snackbar.LENGTH_INDEFINITE)
					.setAction(R.string.run_code, v -> runHeld());
			heldMessage.show();
		}
		return false;
	}

	// What an earlier run reported is gone, also when this run reports
	// nothing back, as a preview in a new task does.
	private void clearErrors() {
		editorFragment.clearError();
		findViewById(R.id.show_errors).setVisibility(View.GONE);
	}

	private void recoverFromCrashIfNeeded() {
		var preferences = ShaderEditorApp.preferences;
		long shaderId = preferences.getPendingCrashShaderId();
		if (shaderId <= 0) {
			return;
		}
		var shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			preferences.setPendingCrashShaderId(0);
			return;
		}
		String recoveredSource = buildCrashRecoverySource(shader.fragmentShader());
		dataSource.shader.updateShader(shaderId, recoveredSource, null, shader.quality());
		preferences.setPendingCrashShaderId(0);
		Message.show(this, R.string.shader_disabled_after_crash);
	}

	@NonNull
	private static String buildCrashRecoverySource(@Nullable String source) {
		if (source != null && source.startsWith(CRASH_RECOVERY_COMMENT)) {
			return source;
		}
		StringBuilder builder = new StringBuilder();
		builder.append(CRASH_RECOVERY_COMMENT);
		builder.append("#if 0\n");
		if (source != null && !source.isEmpty()) {
			builder.append(source);
			if (!source.endsWith("\n")) {
				builder.append('\n');
			}
		}
		builder.append("#endif\n");
		return builder.toString();
	}
}
