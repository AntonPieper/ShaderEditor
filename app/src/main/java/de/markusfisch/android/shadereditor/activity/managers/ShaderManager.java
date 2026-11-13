package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
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
import androidx.core.content.IntentCompat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.AddUniformActivity;
import de.markusfisch.android.shadereditor.activity.LoadSampleActivity;
import de.markusfisch.android.shadereditor.activity.PreviewActivity;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;

// A database shader open in the editor, or text that becomes one when
// it is saved.
public class ShaderManager implements OpenItem {
	public final ActivityResultLauncher<Intent> addUniformLauncher;
	public final ActivityResultLauncher<Intent> loadSampleLauncher;
	public final ActivityResultLauncher<Intent> previewShaderLauncher;

	private static final String SELECTED_SHADER_ID = "selected_shader_id";
	private static final String SELECTED_SHADER_TITLE = "selected_shader_title";
	private static final String DEFAULT_TITLE = "main.glsl";
	private static final String QUALITY = "quality";
	private static final String MODIFIED = "modified";
	private static final long NO_SHADER = 0;

	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ShaderViewManager shaderViewManager;
	private final ShaderList shaderListManager;
	private final NavigationManager navigationManager;
	private final DataSource dataSource;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable titleListener;

	private long selectedShaderId = NO_SHADER;
	@NonNull
	private String title = "";
	private float quality = 1f;
	private boolean isModified = false;

	public ShaderManager(@NonNull AppCompatActivity activity,
			EditorFragment editorFragment,
			ShaderViewManager shaderViewManager,
			ShaderList shaderListManager,
			NavigationManager navigationManager,
			DataSource dataSource,
			ShaderViewManager.Listener shaderViewManagerListener,
			@NonNull Runnable titleListener) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.shaderViewManager = shaderViewManager;
		this.shaderListManager = shaderListManager;
		this.navigationManager = navigationManager;
		this.dataSource = dataSource;
		this.titleListener = titleListener;

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
						createShaderFromResource(
								Objects.requireNonNull(result.getData().getStringExtra(
										LoadSampleActivity.NAME)),
								result.getData().getIntExtra(LoadSampleActivity.RESOURCE_ID,
										R.raw.new_shader),
								result.getData().getIntExtra(LoadSampleActivity.THUMBNAIL_ID,
										R.drawable.thumbnail_new_shader),
								result.getData().getFloatExtra(LoadSampleActivity.QUALITY, 1f));
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

	public void destroy() {
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
	}

	public void createShaderFromResource(@Nullable String name,
			int sourceId, int thumbId, float quality) {
		executor.execute(() -> {
			long newId = dataSource.shader.insertShaderFromResource(
					activity.getApplicationContext(), name, sourceId, thumbId, quality);
			handler.post(() -> {
				if (activity.isFinishing() || activity.isDestroyed()) {
					return;
				}
				selectShader(newId);
				shaderListManager.loadShadersAsync();
			});
		});
	}

	@NonNull
	@Override
	public String getTitle() {
		return title;
	}

	@Nullable
	@Override
	public String getSubtitle() {
		return null;
	}

	@Override
	public float getQuality() {
		return quality;
	}

	@Override
	public void setQuality(float quality) {
		this.quality = quality;
		setModified(true);
	}

	@Override
	public boolean isModified() {
		return isModified;
	}

	@Override
	public void onTextModified() {
		setModified(true);
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
		outState.putString(SELECTED_SHADER_TITLE, title);
		outState.putFloat(QUALITY, quality);
		outState.putBoolean(MODIFIED, isModified);
	}

	// The editor restores its text itself, keeping the undo history.
	public void restoreState(@NonNull Bundle savedInstanceState) {
		selectedShaderId = savedInstanceState.getLong(
				SELECTED_SHADER_ID, NO_SHADER);
		title = savedInstanceState.getString(SELECTED_SHADER_TITLE, "");
		quality = savedInstanceState.getFloat(QUALITY, 1f);
		shaderListManager.setSelectedShaderId(selectedShaderId);
		shaderViewManager.setShader(editorFragment.getText(), quality);
		updatePendingCrashShaderId();
		setModified(savedInstanceState.getBoolean(MODIFIED));
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

	@Override
	public boolean can(@NonNull Action action) {
		return can(action, selectedShaderId);
	}

	@Override
	public void save(boolean explicit) {
		saveShader(explicit);
	}

	public boolean saveShader() {
		return saveShader(false);
	}

	public boolean saveShader(boolean force) {
		if (!force && !isModified()) {
			return true;
		}

		String src = editorFragment.getText();
		if (src.trim().isEmpty() && selectedShaderId <= 0) {
			return true;
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
			long newId = dataSource.shader.insertShader(
					src, null, thumbnail, quality);
			if (newId <= 0) {
				Toast.makeText(activity, R.string.storage_not_writeable,
						Toast.LENGTH_LONG).show();
				return false;
			}
			selectedShaderId = newId;
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
		return true;
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
		if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
			uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
		}
		String text;
		if (uri != null) {
			text = readText(uri);
		} else if (Intent.ACTION_SEND.equals(intent.getAction())) {
			// Text shared from another app comes as an extra.
			CharSequence extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
			text = extra != null ? extra.toString() : null;
		} else {
			text = null;
		}
		if (text == null) {
			return false;
		}
		if (isModified() && !saveShader()) {
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
		try (var in = activity.getContentResolver().openInputStream(uri)) {
			if (in == null) {
				return null;
			}
			// A reader keeps characters that span two buffers intact.
			try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				char[] buffer = new char[4096];
				int len;
				var sb = new StringBuilder();
				while ((len = reader.read(buffer)) != -1) {
					sb.append(buffer, 0, len);
				}
				return sb.toString();
			}
		} catch (IOException | SecurityException e) {
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

	// Select a new copy of the shader with this id.
	public void selectCopy(long shaderId) {
		long newId = duplicateShader(shaderId);
		if (newId > 0) {
			selectShader(newId);
			shaderListManager.loadShadersAsync();
		}
	}

	@Override
	public void saveCopy() {
		if (!can(Action.SAVE_COPY)) {
			return;
		}
		if (isModified()) {
			saveShader();
		}
		selectCopy(selectedShaderId);
	}

	public void deleteShader(long shaderId) {
		dataSource.shader.removeShader(shaderId);
		if (shaderId == selectedShaderId) {
			selectedShaderId = NO_SHADER;
			updatePendingCrashShaderId();
		}
	}

	@Override
	public void delete() {
		if (!can(Action.DELETE)) {
			return;
		}
		deleteShader(selectedShaderId);
		selectShader(dataSource.shader.getFirstShaderId());
		shaderListManager.loadShadersAsync();
	}

	// The shader with this id was given this name in the list.
	public void shaderRenamed(long shaderId, @NonNull String name) {
		if (shaderId == selectedShaderId) {
			title = name;
			titleListener.run();
		}
	}

	@Override
	public void share() {
		navigationManager.shareShader(editorFragment.getText());
	}

	@Override
	public boolean isWallpaper() {
		return ShaderEditorApp.preferences.getWallpaperShader() ==
				selectedShaderId;
	}

	@Override
	public void setAsWallpaper() {
		if (!can(Action.WALLPAPER)) {
			return;
		}
		if (isModified()) {
			saveShader();
		}
		ShaderEditorApp.preferences.setWallpaperShader(0); // Force change
		ShaderEditorApp.preferences.setWallpaperShader(selectedShaderId);
	}

	private void applyShader(long shaderId,
			@NonNull String text,
			float quality,
			@NonNull String toolbarTitle) {
		selectedShaderId = shaderId;
		this.quality = quality;
		editorFragment.setText(text);
		title = toolbarTitle;
		titleListener.run();
		shaderListManager.setSelectedShaderId(selectedShaderId);
		shaderViewManager.setShader(text, quality);
		updatePendingCrashShaderId();
		setModified(false);
	}

	// Only a shader stored in the database can be copied, deleted or be
	// the wallpaper.
	static boolean can(@NonNull Action action, long shaderId) {
		return switch (action) {
			case SAVE, SHARE -> true;
			case SAVE_COPY, DELETE, WALLPAPER -> shaderId > 0;
		};
	}

	@NonNull
	static String getTitle(@NonNull DataRecords.Shader shader) {
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
