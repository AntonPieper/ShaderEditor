package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.AddUniformActivity;
import de.markusfisch.android.shadereditor.activity.LoadSampleActivity;
import de.markusfisch.android.shadereditor.activity.PreviewActivity;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;

public class ShaderManager {
	public final ActivityResultLauncher<Intent> addUniformLauncher;
	public final ActivityResultLauncher<Intent> loadSampleLauncher;
	public final ActivityResultLauncher<Intent> previewShaderLauncher;

	private static final String SELECTED_SHADER_ID = "selected_shader_id";
	private static final String DEFAULT_TITLE = "main.glsl";
	private static final long NO_SHADER = 0;

	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ShaderViewManager shaderViewManager;
	private final ShaderListManager shaderListManager;
	private final UIManager uiManager;
	private final ProjectManager projectManager;
	private final DataSource dataSource;

	private long selectedShaderId = NO_SHADER;
	private float quality = 1f;
	private boolean isModified = false;

	public ShaderManager(@NonNull AppCompatActivity activity,
			EditorFragment editorFragment,
			ShaderViewManager shaderViewManager,
			ShaderListManager shaderListManager,
			UIManager uiManager,
			ProjectManager projectManager,
			DataSource dataSource,
			ShaderViewManager.Listener shaderViewManagerListener) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.shaderViewManager = shaderViewManager;
		this.shaderListManager = shaderListManager;
		this.uiManager = uiManager;
		this.projectManager = projectManager;
		this.dataSource = dataSource;

		addUniformLauncher = activity.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(), result -> {
					if (result.getResultCode() == Activity.RESULT_OK &&
							result.getData() != null) {
						editorFragment.addUniform(
								result.getData().getStringExtra(
										AddUniformActivity.STATEMENT));
					}
				});

		loadSampleLauncher = activity.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(), result -> {
					if (result.getResultCode() == Activity.RESULT_OK &&
							result.getData() != null) {
						if (isModified()) {
							saveShader();
						}
						long newId = dataSource.shader.insertShaderFromResource(activity,
								Objects.requireNonNull(result.getData().getStringExtra(
										LoadSampleActivity.NAME)),
								result.getData().getIntExtra(LoadSampleActivity.RESOURCE_ID,
										R.raw.new_shader),
								result.getData().getIntExtra(LoadSampleActivity.THUMBNAIL_ID,
										R.drawable.thumbnail_new_shader),
								result.getData().getFloatExtra(LoadSampleActivity.QUALITY, 1f));
						selectShader(newId);
					}
				});

		previewShaderLauncher =
				activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
					if (result.getResultCode() != Activity.RESULT_OK) {
						return;
					}
					PreviewActivity.RenderStatus status =
							PreviewActivity.renderStatus;
					if (status.getFps() > 0) {
						shaderViewManagerListener.onFramesPerSecond(
								status.getFps());
					}
					if (status.getInfoLog() != null) {
						shaderViewManagerListener.onInfoLog(status.getInfoLog());
					}
					if (getSelectedShaderId() > 0 &&
							status.getThumbnail() != null &&
							ShaderEditorApp.preferences.doesSaveOnRun()) {
						saveShader();
					}
				});
	}

	public long getSelectedShaderId() {
		return selectedShaderId;
	}

	public float getQuality() {
		return quality;
	}

	public void setQuality(float quality) {
		this.quality = quality;
		setModified(true);
	}

	public boolean isModified() {
		return isModified;
	}

	public void setModified(boolean modified) {
		this.isModified = modified;
		// The EditorFragment's modified state is managed internally by its
		// UndoRedo helper. It cannot and should not be set from the outside.
		// When a shader is loaded via `selectShader`,
		// `editorFragment.setText()` is called, which clears the history
		// and resets the modified state.
	}

	public void saveState(@NonNull Bundle outState) {
		outState.putLong(SELECTED_SHADER_ID, selectedShaderId);
	}

	public void restoreState(@NonNull Bundle savedInstanceState) {
		selectedShaderId = savedInstanceState.getLong(
				SELECTED_SHADER_ID, NO_SHADER);
	}

	public void selectShader(long id) {
		if (isModified()) {
			saveShader();
		}

		PreviewActivity.renderStatus.reset();
		var shader = dataSource.shader.getShader(id);
		if (shader == null) {
			applyShader(
					NO_SHADER,
					activity.getString(R.string.new_shader_template),
					1f,
					activity.getString(R.string.add_shader));
			return;
		}

		ShaderEditorApp.preferences.setLastOpenedShader(id);
		applyShader(id,
				shader.fragmentShader(),
				shader.quality(),
				getTitle(shader));
	}

	public void saveShader() {
		saveShader(false);
	}

	public void saveShader(boolean force) {
		if (projectManager.isOpen()) {
			projectManager.save(force);
			return;
		} else if (!force && !isModified()) {
			return;
		}

		String src = editorFragment.getText();
		if (src.trim().isEmpty() && selectedShaderId <= 0) {
			return;
		}

		boolean runInBackground =
				ShaderEditorApp.preferences.doesRunInBackground();
		byte[] thumbnail = runInBackground
				? null
				: PreviewActivity.renderStatus.getThumbnail();
		if (selectedShaderId > 0) {
			dataSource.shader.updateShader(
					selectedShaderId, src, thumbnail, quality);
		} else {
			selectedShaderId = dataSource.shader.insertShader(
					src, null, thumbnail, quality);
			shaderListManager.setSelectedShaderId(selectedShaderId);
		}

		if (runInBackground) {
			saveThumbnailLater(selectedShaderId);
		}

		updatePendingCrashShaderId();
		setModified(false);
		shaderListManager.loadShadersAsync();
		Toast.makeText(activity, R.string.shader_saved,
				Toast.LENGTH_SHORT).show();
	}

	// The preview answers after its next complete frame, possibly when
	// another shader is selected already.
	private void saveThumbnailLater(long shaderId) {
		shaderViewManager.requestThumbnail(png -> {
			if (png == null) {
				return;
			}
			activity.runOnUiThread(() -> {
				dataSource.shader.updateThumbnail(shaderId, png);
				shaderListManager.loadShadersAsync();
			});
		});
	}

	public boolean handleSendText(@Nullable Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return false;
		}
		if (!Intent.ACTION_SEND.equals(intent.getAction()) &&
				!Intent.ACTION_VIEW.equals(intent.getAction())) {
			return false;
		}

		Uri uri = intent.getData();
		String text;
		if (uri != null) {
			text = readText(uri);
		} else {
			// Text shared from another app comes as an extra.
			text = intent.getStringExtra(Intent.EXTRA_TEXT);
		}
		if (text == null) {
			return false;
		}

		PreviewActivity.renderStatus.reset();
		intent.setAction(null);
		applyShader(NO_SHADER, text, 1f, getDisplayName(uri));
		setModified(true);
		return true;
	}

	@Nullable
	private String readText(@NonNull Uri uri) {
		try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
			if (in == null) {
				return null;
			}
			byte[] buffer = new byte[4096];
			int len;
			var sb = new StringBuilder();
			while ((len = in.read(buffer)) != -1) {
				sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
			}
			return sb.toString();
		} catch (IOException e) {
			Toast.makeText(activity, R.string.unsuitable_text,
					Toast.LENGTH_SHORT).show();
			return null;
		}
	}

	public long duplicateShader(long shaderId) {
		var shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			return NO_SHADER;
		}
		var thumbnail = dataSource.shader.getThumbnail(shaderId);
		return dataSource.shader.insertShader(
				shader.fragmentShader(),
				null,
				thumbnail,
				shader.quality());
	}

	// When a project replaced the shader shown.
	public void closeShader() {
		selectedShaderId = NO_SHADER;
		shaderListManager.setSelectedShaderId(NO_SHADER);
		updatePendingCrashShaderId();
		setModified(false);
	}

	public void deleteShader(long shaderId) {
		dataSource.shader.removeShader(shaderId);
		if (shaderId == selectedShaderId) {
			selectedShaderId = NO_SHADER;
			updatePendingCrashShaderId();
		}
	}

	private void applyShader(long shaderId,
			@NonNull String text,
			float quality,
			@NonNull String toolbarTitle) {
		projectManager.close();
		selectedShaderId = shaderId;
		this.quality = quality;
		editorFragment.setText(text);
		uiManager.setToolbarTitle(toolbarTitle);
		shaderListManager.setSelectedShaderId(selectedShaderId);
		shaderViewManager.setShader(text, quality);
		updatePendingCrashShaderId();
		setModified(false);
	}

	@NonNull
	private static String getTitle(@NonNull DataRecords.Shader shader) {
		String title = shader.getTitle();
		if (title == null || title.trim().isEmpty()) {
			return "Shader " + shader.id();
		}
		return title;
	}

	// The name of the shared file, without what comes before its last
	// slash or colon.
	@NonNull
	private static String getDisplayName(@Nullable Uri uri) {
		String name = uri != null ? uri.getLastPathSegment() : null;
		if (name == null) {
			return DEFAULT_TITLE;
		}
		int slash = name.lastIndexOf('/');
		if (slash >= 0 && slash + 1 < name.length()) {
			name = name.substring(slash + 1);
		}
		int colon = name.lastIndexOf(':');
		if (colon >= 0 && colon + 1 < name.length()) {
			name = name.substring(colon + 1);
		}
		name = name.trim();
		return name.isEmpty() ? DEFAULT_TITLE : name;
	}

	static long getPendingCrashShaderId(boolean runInBackground,
			long shaderId) {
		return runInBackground && shaderId > 0 ? shaderId : 0;
	}

	private void updatePendingCrashShaderId() {
		ShaderEditorApp.preferences.setPendingCrashShaderId(
				getPendingCrashShaderId(
						ShaderEditorApp.preferences.doesRunInBackground(),
						selectedShaderId));
	}
}
