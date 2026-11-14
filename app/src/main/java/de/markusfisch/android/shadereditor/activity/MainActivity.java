package de.markusfisch.android.shadereditor.activity;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.managers.ExtraKeysManager;
import de.markusfisch.android.shadereditor.activity.managers.MainMenuManager;
import de.markusfisch.android.shadereditor.activity.managers.NavigationDrawer;
import de.markusfisch.android.shadereditor.activity.managers.NavigationManager;
import de.markusfisch.android.shadereditor.activity.managers.OpenItem;
import de.markusfisch.android.shadereditor.activity.managers.ProjectFile;
import de.markusfisch.android.shadereditor.activity.managers.ProjectFileManager;
import de.markusfisch.android.shadereditor.activity.managers.ProjectHolds;
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
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.service.ShaderWallpaperService;
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;
import de.markusfisch.android.shadereditor.widget.Message;

public class MainActivity extends AppCompatActivity {
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
	// The editor was given back the text of a file of a folder that the
	// old editor cannot show; it is not a shader to run.
	private boolean holdsUnshownFile = false;

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
			files = new ProjectFileManager(this, editorFragment, holds,
					createProjectFileListener(),
					(preparation, nothingRuns, quality) -> {
						if (preparation != null) {
							shaderViewManager.setPlan(preparation, quality);
						} else {
							// No build reports, so only the manifest
							// can be wrong.
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
				createShaderViewListener(),
				this::showOpenTitle,
				() -> uiManager.showNotice(getText(R.string.shader_saved),
						SAVED_NOTICE_MILLIS));
		open = shaderManager;
		shaderManager.setOnShownListener(() -> {
			if (files != null) {
				files.leave();
			}
			open = shaderManager;
		});

		MainMenuManager mainMenuManager = new MainMenuManager(
				this,
				createEditorActions(),
				shaderActions,
				createNavigationActions());

		uiManager.setupToolbar(mainMenuManager::show,
				v -> this.runShader(),
				v -> uiManager.toggleCodeVisibility(),
				v -> editorFragment.showErrors());

		// Only load the last shader when no incoming text was opened,
		// or it would replace that text.
		boolean openedText = shaderManager.handleSendText(getIntent());
		isInitialLoad = state == null && !openedText;
		holdsUnshownFile = state != null && files != null &&
				files.hadFile(state) &&
				!ShaderEditorApp.preferences.useNewEditor();

		editorFragment.setOnEditPausedListener(text -> {
			if (ShaderEditorApp.preferences.doesRunOnChange() &&
					open instanceof ProjectFile) {
				editorFragment.runningEdit();
				files.run();
			} else if (ShaderEditorApp.preferences.doesRunOnChange() &&
					!holdsUnshownFile) {
				editorFragment.runningEdit();
				shaderViewManager.setShader(text, open.getQuality());
			}
		});
		editorFragment.setOnTextModifiedListener(() -> open.onTextModified());
		editorFragment.setCodeCompletionListener(extraKeysManager::setCompletions);
	}

	@Override
	protected void onRestoreInstanceState(@NonNull Bundle state) {
		super.onRestoreInstanceState(state);
		// The library's shader first: a file asked for opens over it.
		if (files == null || !files.hadFile(state)) {
			shaderManager.restoreState(state);
		}
		if (files != null) {
			files.restoreState(state);
		}
		if (!state.getBoolean(CODE_VISIBLE, true)) {
			uiManager.toggleCodeVisibility();
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		shaderManager.saveState(state);
		if (files != null) {
			files.saveState(state);
		}
		state.putBoolean(CODE_VISIBLE, editorFragment.isCodeVisible());
		state.putCharSequence(TOOLBAR_TITLE, uiManager.getToolbarTitle());
		super.onSaveInstanceState(state);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (files != null) {
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
		shaderManager.handleSendText(intent);
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
		return new NavigationDrawer(this, dataSource,
				createShaderListListener(),
				holds,
				new NavigationDrawer.Host() {
					@Override
					public void onAddShader(@NonNull String name) {
						addShader(name.isEmpty() ? null : name);
					}

					@Override
					public void onDuplicateShader(long id) {
						if (id == shaderManager.getSelectedShaderId() &&
								shaderManager.isModified()) {
							shaderManager.saveShader();
						}
						shaderManager.duplicateShader(id);
						shaderListManager.loadShadersAsync();
					}

					@Override
					public void onDeleteShader(long id) {
						boolean wasOpen = id == shaderManager.getSelectedShaderId();
						shaderManager.deleteShader(id);
						if (wasOpen) {
							shaderManager.selectShader(
									dataSource.shader.getFirstShaderId());
						}
						shaderListManager.loadShadersAsync();
					}

					@Override
					public boolean onOpenFile(@NonNull String key,
							@NonNull ProjectPath path, boolean single) {
						if (ShaderEditorApp.preferences.autoSave()) {
							open.save(false);
						}
						return files.open(key, path, single);
					}

					@Override
					public void onOpenExample(@NonNull Samples.Sample sample) {
						if (shaderManager.isModified()) {
							shaderManager.saveShader();
						}
						shaderManager.createShaderFromResource(
								getString(sample.nameId()),
								sample.resId(),
								sample.thumbId(),
								sample.quality());
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
				shaderManager.selectShader(
						ShaderEditorApp.preferences.getLastOpenedShader());
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
			}

			@Override
			public void onFailed(@NonNull String message) {
				Message.show(MainActivity.this, message);
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
					uiManager.setFrameRate(fps + " fps");
				}
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
				return editorFragment != null && editorFragment.canUndo();
			}

			@Override
			public boolean canRedo() {
				return editorFragment != null && editorFragment.canRedo();
			}
		};
	}

	@NonNull
	@Contract("_ -> new")
	private MainMenuManager.ShaderActions createShaderActions(ExtraKeysManager extraKeysManager) {
		return new MainMenuManager.ShaderActions() {
			@Override
			public void onAddShader() {
				// A file of a folder gets a new one beside it.
				if (files != null && files.file() == open &&
						shaderListManager instanceof NavigationDrawer drawer) {
					drawer.showNew(files.key(), files.file().getPath());
					return;
				}
				addShader(null);
			}

			@Override
			public void onSaveShader() {
				open.save(true);
			}

			@Override
			public void onDuplicateShader() {
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
								(dialog, which) -> open.delete())
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
				return open.isWallpaper();
			}

			@Override
			public boolean offers(@NonNull OpenItem.Action action) {
				return open.offers(action);
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.NavigationActions createNavigationActions() {
		return new MainMenuManager.NavigationActions() {
			@Override
			public void onAddUniform() {
				navigationManager.goToAddUniform(shaderManager.addUniformLauncher);
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
		};
	}

	// A copy of the default shader, else the template, named name if
	// not null.
	private void addShader(@Nullable String name) {
		long defaultId = ShaderEditorApp.preferences.getDefaultNewShader();
		if (defaultId > 0 && dataSource.shader.getShader(defaultId) != null) {
			shaderManager.selectCopy(defaultId);
			long id = shaderManager.getSelectedShaderId();
			if (name != null && id > 0) {
				dataSource.shader.updateShaderName(id, name);
				shaderManager.shaderRenamed(id, name);
				shaderListManager.loadShadersAsync();
			}
		} else {
			shaderManager.createShaderFromResource(name,
					R.raw.new_shader, R.drawable.thumbnail_new_shader, 1f);
		}
	}

	private void showOpenTitle() {
		uiManager.setToolbarTitle(open.getTitle(), open.getSubtitle(),
				open.isModified());
	}

	private void updateWallpaper() {
		if (!open.can(OpenItem.Action.WALLPAPER)) {
			return;
		}
		open.setAsWallpaper();

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
		}
		String src = editorFragment.getText();
		editorFragment.clearError();
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
		editorFragment.clearError();
		if (ShaderEditorApp.preferences.doesSaveOnRun()) {
			open.save(false);
		}
		if (ShaderEditorApp.preferences.doesRunInBackground()) {
			files.run();
		} else {
			Message.show(this, R.string.file_runs_behind_code);
		}
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
