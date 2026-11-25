package de.markusfisch.android.shadereditor.activity;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
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
import de.markusfisch.android.shadereditor.activity.managers.DatabaseScreen;
import de.markusfisch.android.shadereditor.activity.managers.ExtraKeysManager;
import de.markusfisch.android.shadereditor.activity.managers.LibraryScreen;
import de.markusfisch.android.shadereditor.activity.managers.MainMenuManager;
import de.markusfisch.android.shadereditor.activity.managers.NavigationManager;
import de.markusfisch.android.shadereditor.activity.managers.OpenItem;
import de.markusfisch.android.shadereditor.activity.managers.ShaderScreen;
import de.markusfisch.android.shadereditor.activity.managers.ShaderViewManager;
import de.markusfisch.android.shadereditor.activity.managers.UIManager;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.service.ShaderWallpaperService;
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.FindBar;
import de.markusfisch.android.shadereditor.widget.Message;
import de.markusfisch.android.shadereditor.widget.TuningBar;

public class MainActivity extends AppCompatActivity {
	private static final String TOOLBAR_TITLE = "toolbar_title";
	private static final String CRASH_RECOVERY_COMMENT = """
			// Shader Editor disabled this shader because it caused a crash while loading.
			// Fix the shader and remove the #if 0 wrapper to re-enable it.
			""";

	private EditorFragment editorFragment;
	private UIManager uiManager;
	private ShaderViewManager shaderViewManager;
	private ExtraKeysManager extraKeysManager;
	private NavigationManager navigationManager;
	// The world this screen is in: rows of the database, or files.
	private ShaderScreen screen;
	// Find in the toolbar's place, made the first time it opens.
	@Nullable
	private FindBar findBar;
	private final OnBackPressedCallback findBack =
			new OnBackPressedCallback(false) {
				@Override
				public void handleOnBackPressed() {
					if (findBar != null) {
						findBar.close();
					}
				}
			};
	private DataSource dataSource;

	@Override
	protected void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_main);
		SystemBarMetrics.initMainLayout(this, null);

		dataSource = Database.getInstance(this).getDataSource();
		recoverFromCrashIfNeeded();

		// Before the drawer's and the sheets' own, so that whatever stands
		// over Find goes first on Back.
		getOnBackPressedDispatcher().addCallback(this, findBack);

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
				new ExtraKeysManager.Editor() {
					@Override
					public void complete(@NonNull String word, int typed) {
						editorFragment.complete(word, typed);
					}

					@Override
					public void tab() {
						editorFragment.tab();
					}

					@Override
					public boolean hasDocs(@NonNull String word) {
						return editorFragment.hasDocs(word);
					}

					@Override
					public void lookUp(@NonNull String word) {
						editorFragment.lookUp(word);
					}
				});
		uiManager = new UIManager(this,
				editorFragment,
				extraKeysManager,
				shaderViewManager);
		MainMenuManager.ShaderActions shaderActions =
				createShaderActions(extraKeysManager);
		// The one place that decides which world this screen is in.
		screen = getResources().getBoolean(R.bool.project_mode)
				? new LibraryScreen(this, editorFragment, uiManager,
						shaderViewManager, extraKeysManager,
						navigationManager, dataSource, shaderViewListener,
						createShaderViewListener(), createScreenHost())
				: new DatabaseScreen(this, editorFragment, uiManager,
						shaderViewManager, navigationManager, dataSource,
						createShaderViewListener(), createScreenHost());

		MainMenuManager mainMenuManager = new MainMenuManager(
				this,
				createEditorActions(),
				shaderActions,
				createNavigationActions());

		uiManager.setupToolbar(mainMenuManager::show,
				v -> screen.run(),
				v -> screen.toggleCode(),
				v -> editorFragment.showErrors());

		screen.start(state, getIntent());

		editorFragment.setOnEditPausedListener(text ->
				screen.onEditPaused(text));
		editorFragment.setOnTextModifiedListener(() -> {
			screen.onTextModified();
			screen.open().onTextModified();
		});
		editorFragment.setCodeCompletionListener(extraKeysManager::setCompletions);
		editorFragment.setOnSignatureListener(extraKeysManager::setSignature);
		// Only the new editor in project mode offers it. The tuning bar
		// and Find never stand together: tuning closes Find.
		editorFragment.setOnTunableListener(number ->
				extraKeysManager.setTunable(number, () -> {
					if (findBar != null) {
						findBar.close();
					}
					screen.tune();
				}));
	}

	// Ctrl+F finds, Ctrl+H replaces, Ctrl+G goes to, as the menu does.
	@Override
	public boolean onKeyShortcut(int keyCode, KeyEvent event) {
		if (event.isCtrlPressed() && !event.isAltPressed()) {
			MainMenuManager.EditorActions actions = createEditorActions();
			if ((keyCode == KeyEvent.KEYCODE_F ||
					keyCode == KeyEvent.KEYCODE_H) && actions.canFind()) {
				find();
				if (keyCode == KeyEvent.KEYCODE_H && findBar != null) {
					findBar.showReplace();
				}
				return true;
			} else if (keyCode == KeyEvent.KEYCODE_G && actions.canGoTo()) {
				actions.onGoTo();
				return true;
			}
		}
		return super.onKeyShortcut(keyCode, event);
	}

	// Escape closes Find from the code too.
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_ESCAPE && findBar != null &&
				findBar.isOpen()) {
			findBar.close();
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	@Override
	protected void onRestoreInstanceState(@NonNull Bundle state) {
		super.onRestoreInstanceState(state);
		screen.onRestoreInstanceState(state);
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		screen.onSaveInstanceState(state);
		state.putCharSequence(TOOLBAR_TITLE, uiManager.getToolbarTitle());
		if (findBar != null) {
			findBar.save(state);
		}
		super.onSaveInstanceState(state);
	}

	@Override
	protected void onStart() {
		super.onStart();
		screen.onStart();
	}

	@Override
	protected void onStop() {
		screen.onStop();
		super.onStop();
	}

	@Override
	protected void onResume() {
		super.onResume();
		uiManager.updateUiToPreferences();
		screen.onResume();
		shaderViewManager.onResume();
	}

	@Override
	protected void onPause() {
		// A dialog over the app pauses it too: tuning goes on, and the
		// screen saves once it stops.
		if (ShaderEditorApp.preferences.autoSave() && !screen.isTuning()) {
			screen.open().save(false);
		}
		ShaderEditorApp.preferences.setPendingCrashShaderId(0);
		screen.onPause();
		super.onPause();
		shaderViewManager.onPause();
	}

	@Override
	protected void onDestroy() {
		if (screen != null) {
			screen.onDestroy(isFinishing());
		}
		if (extraKeysManager != null) {
			extraKeysManager.destroy();
		}
		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		screen.onNewIntent(intent);
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
		uiManager.setToolbarTitle(title, screen.open().getSubtitle(),
				screen.open().isModified());
		screen.onPostCreate();
		if (uiManager.drawerToggle != null) {
			uiManager.drawerToggle.syncState();
		}
		// What was typed into Find stays over a recreation, once the
		// file is open again.
		if (FindBar.wasOpen(state)) {
			editorFragment.whenFindable(code -> findBar().restore(state,
					code, findViewById(R.id.toolbar)));
		}
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
			// The first error of the last result. While a number is tuned
			// the code is hidden and every step is built again: the same
			// error is not shown again then.
			private ProjectDiagnostic lastError;

			@Override
			public void onFramesPerSecond(int fps) {
				if (fps > 0) {
					ShaderEditorApp.preferences.setPendingCrashShaderId(0);
					screen.onFrame();
					uiManager.setFrameRate(fps + " fps");
				} else {
					// No rate without a picture that runs.
					uiManager.setFrameRate(null);
				}
			}

			@Override
			public void onLastPicture(boolean shown) {
				runOnUiThread(() -> editorFragment.setShowsLastPicture(shown));
			}

			@Override
			public void onNothingReported() {
				runOnUiThread(() -> {
					screen.forgetErrors();
					showErrors.setVisibility(View.GONE);
					shownWarnings = List.of();
					lastError = null;
				});
			}

			@Override
			public void onInfoLog(@NonNull List<ProjectDiagnostic> reported) {
				runOnUiThread(() -> {
					// A report of a build of what was open before is
					// not told, nor does it clear what is open now.
					List<ProjectDiagnostic> infoLog = new ArrayList<>();
					for (var diagnostic : reported) {
						if (screen.isAbout(diagnostic)) {
							infoLog.add(diagnostic);
						}
					}
					if (infoLog.isEmpty() && !reported.isEmpty()) {
						return;
					}
					for (var diagnostic : infoLog) {
						if (diagnostic.severity() ==
								ProjectDiagnostic.Severity.ERROR) {
							// A build that failed draws nothing, so it
							// cannot end the app.
							ShaderEditorApp.preferences
									.setPendingCrashShaderId(0);
							screen.onFailed();
							break;
						}
					}
					screen.showErrors(infoLog);
					ProjectDiagnostic before = lastError;
					lastError = null;
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
										diagnostic) && !(screen.isTuning() &&
										diagnostic.equals(before))) {
									showError(diagnostic.toString());
								}
								lastError = diagnostic;
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
				screen.onQualityChanged(quality);
			}

			private void showError(String error) {
				// Not over the few lines of code left while the toolbar
				// steps aside: the code marks the error, and the error
				// button is back with the toolbar.
				if (uiManager.areTopBarsAside()) {
					return;
				}
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
			// While a number is tuned, Undo and Redo walk the values
			// tried, and the steps before the bar opened stay.
			@Override
			public void onUndo() {
				TuningBar tuning = findViewById(R.id.tuning_bar);
				if (tuning.isOpen()) {
					tuning.undo();
				} else {
					editorFragment.undo();
				}
			}

			@Override
			public void onRedo() {
				TuningBar tuning = findViewById(R.id.tuning_bar);
				if (tuning.isOpen()) {
					tuning.redo();
				} else {
					editorFragment.redo();
				}
			}

			@Override
			public boolean canUndo() {
				TuningBar tuning = findViewById(R.id.tuning_bar);
				if (tuning.isOpen()) {
					return tuning.canUndo();
				}
				return screen.open().hasText() && editorFragment != null &&
						editorFragment.canUndo();
			}

			@Override
			public boolean canRedo() {
				TuningBar tuning = findViewById(R.id.tuning_bar);
				if (tuning.isOpen()) {
					return tuning.canRedo();
				}
				return screen.open().hasText() && editorFragment != null &&
						editorFragment.canRedo();
			}

			@Override
			public boolean canFind() {
				return screen.open().hasText() &&
						editorFragment.findableCode() != null;
			}

			@Override
			public void onFind() {
				find();
			}

			@Override
			public boolean canGoTo() {
				return screen.open().hasText() &&
						editorFragment.findableCode() != null &&
						editorFragment.canGoTo();
			}

			@Override
			public void onGoTo() {
				showCode();
				editorFragment.goTo();
			}
		};
	}

	// Brings the code back for Find and Go to: tuning ends as Done ends
	// it, and code the code button hid shows again.
	private void showCode() {
		TuningBar tuning = findViewById(R.id.tuning_bar);
		if (tuning.isOpen()) {
			tuning.done();
		}
		if (!editorFragment.isCodeVisible()) {
			screen.toggleCode();
		}
	}

	// Opens Find over the code.
	private void find() {
		CodeView code = editorFragment.findableCode();
		if (code == null) {
			return;
		}
		showCode();
		findBar().open(code, findViewById(R.id.toolbar));
	}

	@NonNull
	private FindBar findBar() {
		if (findBar == null) {
			findBar = (FindBar) ((ViewStub) findViewById(
					R.id.find_bar_stub)).inflate();
			findBar.setOnOpenChanged(() ->
					findBack.setEnabled(findBar.isOpen()));
		}
		return findBar;
	}

	@NonNull
	@Contract("_ -> new")
	private MainMenuManager.ShaderActions createShaderActions(ExtraKeysManager extraKeysManager) {
		return new MainMenuManager.ShaderActions() {
			@Override
			public void onAddShader() {
				screen.newShader();
			}

			@Override
			public void onSaveShader() {
				screen.open().save(true);
			}

			@Override
			public void onDuplicateShader() {
				screen.saveCopy();
			}

			@Override
			public void onDeleteShader() {
				if (!screen.open().can(OpenItem.Action.DELETE)) {
					return;
				}
				new MaterialAlertDialogBuilder(MainActivity.this)
						.setTitle(getString(R.string.delete_shader_title,
								screen.open().getTitle()))
						.setPositiveButton(R.string.delete_shader,
								(dialog, which) -> screen.delete())
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}

			@Override
			public void onShareShader() {
				screen.open().share();
			}

			@Override
			public void onUpdateWallpaper() {
				if (screen.open().can(OpenItem.Action.WALLPAPER)) {
					screen.updateWallpaper();
				}
			}

			@Override
			public void onRunThisFile() {
				screen.open().runThisFile();
			}

			@Override
			public void onToggleExtraKeys() {
				extraKeysManager.setVisible(ShaderEditorApp.preferences.toggleShowExtraKeys());
			}


			@Override
			public boolean isWallpaper() {
				return screen.isWallpaper();
			}

			@Override
			public boolean offers(@NonNull OpenItem.Action action) {
				return screen.open().offers(action);
			}

			@Override
			public boolean hasText() {
				return screen.open().hasText();
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.NavigationActions createNavigationActions() {
		return new MainMenuManager.NavigationActions() {
			@Override
			public void onAddUniform() {
				screen.addUniform();
			}

			@Override
			public void onLoadSample() {
				screen.showExamples();
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
				screen.moveLibraryAgain();
			}
		};
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

	// What a screen asks the activity for, whichever world it is.
	@NonNull
	private ShaderScreen.Host createScreenHost() {
		return new ShaderScreen.Host() {
			@Override
			public void showWallpaper() {
				MainActivity.this.showWallpaper();
			}

			@Override
			public void clearErrors() {
				MainActivity.this.clearErrors();
			}

			@Override
			public void runText(@NonNull OpenItem open,
					@NonNull ActivityResultLauncher<Intent> preview) {
				MainActivity.this.runText(open, preview);
			}
		};
	}

	// Run the text of a shader of the database.
	private void runText(@NonNull OpenItem open,
			@NonNull ActivityResultLauncher<Intent> preview) {
		String src = editorFragment.getText();
		clearErrors();
		if (ShaderEditorApp.preferences.doesSaveOnRun()) {
			PreviewActivity.renderStatus.reset();
			open.save(false);
		}
		if (ShaderEditorApp.preferences.doesRunInBackground()) {
			shaderViewManager.setShader(src, open.getQuality());
		} else {
			navigationManager.showPreview(src, open.getQuality(), preview);
		}
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
