package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.jetbrains.annotations.Contract;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

/**
 * The main screen where the shaders are rows of the database, as in
 * release builds: one shader is open at all times, the drawer lists
 * the rows, and a start shows the shader that was open last.
 */
public final class DatabaseScreen implements ShaderScreen {
	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final UIManager uiManager;
	private final ShaderViewManager shaderViewManager;
	private final NavigationManager navigationManager;
	private final DataSource dataSource;
	private final Host host;
	private final ShaderManager shaderManager;
	private final ShaderList shaderListManager;
	// What the editor has open: the shader, always.
	private final OpenItem open;
	private boolean isInitialLoad = false;

	public DatabaseScreen(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editorFragment,
			@NonNull UIManager uiManager,
			@NonNull ShaderViewManager shaderViewManager,
			@NonNull NavigationManager navigationManager,
			@NonNull DataSource dataSource,
			@NonNull ShaderViewManager.Listener shaderViewListener,
			@NonNull Host host) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.uiManager = uiManager;
		this.shaderViewManager = shaderViewManager;
		this.navigationManager = navigationManager;
		this.dataSource = dataSource;
		this.host = host;
		shaderListManager = new ShaderListManager(activity,
				activity.findViewById(R.id.shaders),
				dataSource,
				createShaderListListener());
		shaderManager = new ShaderManager(activity,
				editorFragment,
				shaderViewManager,
				shaderListManager,
				navigationManager,
				dataSource,
				false,
				shaderViewListener,
				this::showTitle,
				() -> uiManager.showNotice(activity.getText(R.string.shader_saved),
						SAVED_NOTICE_MILLIS));
		open = shaderManager;
	}

	@Override
	public void start(@Nullable Bundle state, @Nullable Intent intent) {
		// Only load the last shader when no incoming text was opened,
		// or it would replace that text. A file still being read comes
		// back with the state.
		boolean openedText = state == null &&
				shaderManager.handleSendText(intent);
		isInitialLoad = state == null && !openedText;
	}

	@Override
	public void onRestoreInstanceState(@NonNull Bundle state) {
		shaderManager.restoreState(state);
		// A shader's errors are not kept; its build reports them again
		// where the picture runs behind the code.
		editorFragment.keepNoErrors(
				ShaderEditorApp.preferences.doesRunInBackground());
		if (!state.getBoolean(CODE_VISIBLE, true)) {
			uiManager.toggleCodeVisibility();
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle state) {
		shaderManager.saveState(state);
		state.putBoolean(CODE_VISIBLE, editorFragment.isCodeVisible());
	}

	@Override
	public void onPostCreate() {
	}

	@Override
	public void onStart() {
	}

	@Override
	public void onStop() {
	}

	@Override
	public void onResume() {
		shaderListManager.loadShadersAsync();
	}

	@Override
	public void onPause() {
	}

	@Override
	public void onDestroy(boolean finishing) {
		shaderManager.destroy();
		shaderListManager.destroy();
	}

	@Override
	public void onNewIntent(@Nullable Intent intent) {
		shaderManager.handleSendText(intent);
	}

	@NonNull
	@Override
	public OpenItem open() {
		return open;
	}

	@Override
	public void newShader() {
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
	public void saveCopy() {
		open.saveCopy();
	}

	@Override
	public void delete() {
		open.delete();
	}

	@Override
	public void updateWallpaper() {
		open.setAsWallpaper();
		host.showWallpaper();
	}

	@Override
	public boolean isWallpaper() {
		return open.isWallpaper();
	}

	@Override
	public void showExamples() {
		navigationManager.goToLoadSample(shaderManager.loadSampleLauncher);
	}

	@Override
	public void moveLibraryAgain() {
	}

	@Override
	public void run() {
		host.runText(open, shaderManager.previewShaderLauncher);
	}

	@Override
	public void toggleCode() {
		uiManager.toggleCodeVisibility();
	}

	@Override
	public void addUniform() {
		navigationManager.goToAddUniform(
				shaderManager.addUniformLauncher);
	}

	@Override
	public void onEditPaused(@NonNull CharSequence text) {
		if (ShaderEditorApp.preferences.doesRunOnChange()) {
			editorFragment.runningEdit();
			shaderViewManager.setShader(text.toString(), open.getQuality());
		}
	}

	@Override
	public void onQualityChanged(float quality) {
		open.setQuality(quality);
	}

	@Override
	public void onFrame() {
		shaderManager.drew();
	}

	@Override
	public void showErrors(@NonNull List<ProjectDiagnostic> infoLog) {
		editorFragment.setErrors(infoLog);
	}

	@Override
	public void forgetErrors() {
		editorFragment.clearError();
	}

	// A shader of the database is one text: what it reports is of it.
	@Override
	public boolean isAbout(@NonNull ProjectDiagnostic diagnostic) {
		return true;
	}

	private void showTitle() {
		uiManager.setToolbarTitle(open.getTitle(), open.getSubtitle(),
				open.isModified());
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
				shaderManager.onAllShadersDeleted();
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
}
