package de.markusfisch.android.shadereditor.activity;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

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

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.managers.ExtraKeysManager;
import de.markusfisch.android.shadereditor.activity.managers.MainMenuManager;
import de.markusfisch.android.shadereditor.activity.managers.NavigationDrawer;
import de.markusfisch.android.shadereditor.activity.managers.NavigationManager;
import de.markusfisch.android.shadereditor.activity.managers.OpenItem;
import de.markusfisch.android.shadereditor.activity.managers.ShaderList;
import de.markusfisch.android.shadereditor.activity.managers.ShaderListManager;
import de.markusfisch.android.shadereditor.activity.managers.ShaderManager;
import de.markusfisch.android.shadereditor.activity.managers.ShaderViewManager;
import de.markusfisch.android.shadereditor.activity.managers.UIManager;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.service.ShaderWallpaperService;
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;

public class MainActivity extends AppCompatActivity {
	private static final String CODE_VISIBLE = "code_visible";
	private static final String TOOLBAR_TITLE = "toolbar_title";
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
	private NavigationManager navigationManager;
	private DataSource dataSource;
	private boolean isInitialLoad = false;

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
		shaderViewManager = new ShaderViewManager(this,
				findViewById(R.id.preview),
				findViewById(R.id.quality),
				createShaderViewListener());
		ExtraKeysManager extraKeysManager = new ExtraKeysManager(this,
				findViewById(android.R.id.content),
				editorFragment::insert);
		uiManager = new UIManager(this,
				editorFragment,
				extraKeysManager,
				shaderViewManager);
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
				this::showOpenTitle);
		open = shaderManager;

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

		editorFragment.setOnEditPausedListener(text -> {
			if (ShaderEditorApp.preferences.doesRunOnChange()) {
				if (editorFragment.hasErrors()) {
					editorFragment.clearError();
					editorFragment.highlightErrors();
				}
				shaderViewManager.setShader(text, open.getQuality());
			}
		});
		editorFragment.setOnTextModifiedListener(() -> open.onTextModified());
		editorFragment.setCodeCompletionListener(extraKeysManager::setCompletions);
	}

	@Override
	protected void onRestoreInstanceState(@NonNull Bundle state) {
		super.onRestoreInstanceState(state);
		shaderManager.restoreState(state);
		if (!state.getBoolean(CODE_VISIBLE, true)) {
			uiManager.toggleCodeVisibility();
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		shaderManager.saveState(state);
		state.putBoolean(CODE_VISIBLE, editorFragment.isCodeVisible());
		state.putCharSequence(TOOLBAR_TITLE, uiManager.getToolbarTitle());
		super.onSaveInstanceState(state);
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
		if (shaderManager != null) {
			shaderManager.destroy();
		}
		if (shaderListManager != null) {
			shaderListManager.destroy();
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
		uiManager.setToolbarTitle(title, open.getSubtitle());
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
				new NavigationDrawer.Host() {
					@Override
					public void onAddShader() {
						shaderActions.onAddShader();
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
					uiManager.setToolbarSubtitle(fps + " fps");
				}
			}

			@Override
			public void onInfoLog(@NonNull List<ProjectDiagnostic> infoLog) {
				runOnUiThread(() -> {
					editorFragment.setErrors(infoLog);
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
								showError(diagnostic.toString());
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
				long defaultId = ShaderEditorApp.preferences.getDefaultNewShader();
				if (defaultId > 0 && dataSource.shader.getShader(defaultId) != null) {
					shaderManager.selectCopy(defaultId);
				} else {
					shaderManager.createShaderFromResource(null,
							R.raw.new_shader, R.drawable.thumbnail_new_shader, 1f);
				}
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
						.setMessage(R.string.sure_remove_shader)
						.setPositiveButton(android.R.string.ok,
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
			public void onToggleExtraKeys() {
				extraKeysManager.setVisible(ShaderEditorApp.preferences.toggleShowExtraKeys());
			}


			@Override
			public boolean isWallpaper() {
				return open.isWallpaper();
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

	private void showOpenTitle() {
		uiManager.setToolbarTitle(open.getTitle(), open.getSubtitle());
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
		Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show();
	}

	private void runShader() {
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
		Toast.makeText(this, R.string.shader_disabled_after_crash,
				Toast.LENGTH_LONG).show();
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
