package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

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
import de.markusfisch.android.shadereditor.widget.Message;

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
	private final ShaderInserts inserts;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable titleListener;
	private final Runnable savedListener;

	private long selectedShaderId = NO_SHADER;
	@NonNull
	private String title = "";
	private float quality = 1f;
	private boolean isModified = false;
	@Nullable
	private Runnable shownListener;
	@Nullable
	private Runnable goneListener;

	public ShaderManager(@NonNull AppCompatActivity activity,
			EditorFragment editorFragment,
			ShaderViewManager shaderViewManager,
			ShaderList shaderListManager,
			NavigationManager navigationManager,
			DataSource dataSource,
			boolean files,
			ShaderViewManager.Listener shaderViewManagerListener,
			@NonNull Runnable titleListener,
			@NonNull Runnable savedListener) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.shaderViewManager = shaderViewManager;
		this.shaderListManager = shaderListManager;
		this.navigationManager = navigationManager;
		this.dataSource = dataSource;
		inserts = new ShaderInserts(dataSource.shader, files);
		this.titleListener = titleListener;
		this.savedListener = savedListener;

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
						// Also when the text is saved already, for the
						// thumbnail; the user did not ask to save.
						saveShader(true, false);
					}
				});
	}

	/** Call listener before a shader or a new text is put in the editor. */
	public void setOnShownListener(@Nullable Runnable listener) {
		shownListener = listener;
	}

	/**
	 * Call listener when the shader in the editor is gone and no other
	 * shader of the database comes in its place: where the shaders are
	 * files, after it was deleted, or when one asked for is not there.
	 */
	public void setOnGoneListener(@Nullable Runnable listener) {
		goneListener = listener;
	}

	/** Another item is in the editor now: no shader is selected. */
	public void deselect() {
		selectedShaderId = NO_SHADER;
		title = "";
		isModified = false;
		shaderListManager.setSelectedShaderId(NO_SHADER);
		updatePendingCrashShaderId();
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
		if (!inserts.possible()) {
			return;
		}
		executor.execute(() -> {
			long newId = inserts.insertFromResource(
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

	// The title shows whether there are unsaved changes.
	public void setModified(boolean modified) {
		if (isModified != modified) {
			isModified = modified;
			titleListener.run();
		}
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
		long id = savedInstanceState.getLong(SELECTED_SHADER_ID, NO_SHADER);
		if (!editorFragment.isTextRestored()) {
			// The text is lost, so is any change.
			selectShader(id);
			return;
		}
		restore(id,
				savedInstanceState.getString(SELECTED_SHADER_TITLE, ""),
				savedInstanceState.getFloat(QUALITY, 1f),
				savedInstanceState.getBoolean(MODIFIED));
	}

	/**
	 * Take the shader with id as the one in the editor again, whose
	 * text the editor restored itself, after the screen was made
	 * again.
	 */
	public void restore(long id, @NonNull String title, float quality,
			boolean modified) {
		selectedShaderId = id;
		this.title = title;
		this.quality = quality;
		shaderListManager.setSelectedShaderId(selectedShaderId);
		shaderViewManager.setShader(editorFragment.getText(), quality);
		updatePendingCrashShaderId();
		setModified(modified);
	}

	public void selectShader(long id) {
		if (isModified()) {
			saveShader();
		}

		PreviewActivity.renderStatus.reset();
		var shader = dataSource.shader.getShader(id);
		if (shader == null && !inserts.possible()) {
			// No text without a row stands in for it: it could only
			// be saved as a new row.
			gone();
			return;
		} else if (shader == null) {
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

	// What is open is what runs.
	@Override
	public boolean offers(@NonNull Action action) {
		return action != Action.RUN_THIS_FILE;
	}

	@Override
	public void save(boolean explicit) {
		saveShader(explicit);
	}

	public boolean saveShader() {
		return saveShader(false);
	}

	public boolean saveShader(boolean force) {
		return saveShader(force, force);
	}

	private boolean saveShader(boolean force, boolean explicit) {
		if (!force && !isModified()) {
			return true;
		}

		String src = editorFragment.getText();
		if (selectedShaderId <= 0 && (src.trim().isEmpty() ||
				!inserts.possible())) {
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
			long newId = inserts.insert(src, null, thumbnail, quality);
			if (newId <= 0) {
				Message.show(activity, R.string.storage_not_writeable);
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
		if (explicit) {
			savedListener.run();
		}
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
		Shared shared = readShared(intent);
		if (shared == null) {
			return false;
		}
		if (isModified() && !saveShader()) {
			return false;
		}

		PreviewActivity.renderStatus.reset();
		applyShader(NO_SHADER, shared.text(), 1f,
				shared.name() != null ? shared.name() : DEFAULT_TITLE);
		setModified(true);
		return true;
	}

	/** Text another app shared or opened, and its name, or null. */
	public record Shared(@NonNull String text, @Nullable String name) {
	}

	/**
	 * Return the text of intent when another app shared or opened text
	 * with it, and mark the intent as handled; else return null.
	 */
	@Nullable
	public Shared readShared(@Nullable Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return null;
		}
		if (!Intent.ACTION_SEND.equals(intent.getAction()) &&
				!Intent.ACTION_VIEW.equals(intent.getAction())) {
			return null;
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
			return null;
		}
		intent.setAction(null);
		// Text without a file has no name.
		return new Shared(text, uri != null ? getDisplayName(uri) : null);
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
			Message.show(activity, R.string.unsuitable_text);
			return null;
		}
	}

	public long duplicateShader(long shaderId) {
		var shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			return NO_SHADER;
		}
		var thumbnail = dataSource.shader.getThumbnail(shaderId);
		return inserts.insert(
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
			setModified(false);
			updatePendingCrashShaderId();
		}
	}

	@Override
	public void delete() {
		if (!can(Action.DELETE)) {
			return;
		}
		deleteShader(selectedShaderId);
		if (inserts.possible()) {
			selectShader(dataSource.shader.getFirstShaderId());
		} else {
			// What comes next is not another row of the database.
			gone();
		}
		shaderListManager.loadShadersAsync();
	}

	private void gone() {
		deselect();
		if (goneListener != null) {
			goneListener.run();
		}
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
		if (shownListener != null) {
			shownListener.run();
		}
		selectedShaderId = shaderId;
		this.quality = quality;
		editorFragment.setText(text);
		title = toolbarTitle;
		titleListener.run();
		shaderListManager.setSelectedShaderId(selectedShaderId);
		// The editor converts a text in ShaderToy's form as it shows it.
		shaderViewManager.setShader(editorFragment.getText(), quality);
		updatePendingCrashShaderId();
		setModified(false);
	}

	// Only a shader stored in the database can be copied, deleted or be
	// the wallpaper.
	static boolean can(@NonNull Action action, long shaderId) {
		return switch (action) {
			case SAVE, SHARE -> true;
			case SAVE_COPY, DELETE, WALLPAPER -> shaderId > 0;
			case RUN_THIS_FILE -> false;
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
