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
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

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
import de.markusfisch.android.shadereditor.io.ShaderSourceReader;
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
	private static final String PENDING_SHADER_URI = "pending_shader_uri";
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
	// The shader that drew a frame last; a save does not arm the crash
	// guard for it again, as nothing new starts.
	private long drewShaderId = NO_SHADER;
	@NonNull
	private String title = "";
	private float quality = 1f;
	private boolean isModified = false;
	private long importRequest;
	@Nullable
	private Uri pendingShaderUri;
	@Nullable
	private Runnable pendingImportResult;
	private final SharedListener openInEditor = this::openShared;
	@NonNull
	private SharedListener sharedListener = openInEditor;
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
		activity.getLifecycle().addObserver(new DefaultLifecycleObserver() {
			@Override
			public void onResume(@NonNull LifecycleOwner owner) {
				Runnable result = pendingImportResult;
				pendingImportResult = null;
				if (result != null) {
					result.run();
				}
			}
		});

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
						if (isModified() && !saveShader()) {
							return;
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
	 * Give text another app shares or opens to listener instead of
	 * opening it in the editor as a new shader.
	 */
	public void setOnSharedListener(@NonNull SharedListener listener) {
		sharedListener = listener;
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
		cancelShaderImport();
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
	}

	public void createShaderFromResource(@Nullable String name,
			int sourceId, int thumbId, float quality) {
		if (!inserts.possible()) {
			return;
		}
		cancelImportIntoEditor();
		long request = importRequest;
		executor.execute(() -> {
			long newId = inserts.insertFromResource(
					activity.getApplicationContext(), name, sourceId, thumbId, quality);
			handler.post(() -> {
				if (activity.isFinishing() || activity.isDestroyed()) {
					return;
				}
				if (request == importRequest) {
					selectShader(newId);
				}
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
		savePendingImport(outState);
	}

	// A file that is still being read is read again after a recreate.
	public void savePendingImport(@NonNull Bundle outState) {
		outState.putParcelable(PENDING_SHADER_URI, pendingShaderUri);
	}

	// The editor restores its text itself, keeping the undo history.
	public void restoreState(@NonNull Bundle savedInstanceState) {
		long id = savedInstanceState.getLong(SELECTED_SHADER_ID, NO_SHADER);
		if (!editorFragment.isTextRestored()) {
			// The text is lost, so is any change.
			selectShader(id);
			restorePendingImport(savedInstanceState);
			return;
		}
		restore(id,
				savedInstanceState.getString(SELECTED_SHADER_TITLE, ""),
				savedInstanceState.getFloat(QUALITY, 1f),
				savedInstanceState.getBoolean(MODIFIED));
		restorePendingImport(savedInstanceState);
	}

	@SuppressWarnings("deprecation")
	public void restorePendingImport(@NonNull Bundle state) {
		Uri uri = state.getParcelable(PENDING_SHADER_URI);
		if (uri != null) {
			handleSendText(new Intent(Intent.ACTION_VIEW, uri));
		}
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
		if (isModified() && !saveShader()) {
			return;
		}
		cancelImportIntoEditor();

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

	/**
	 * Take the text another app shared or opened with intent, a file
	 * read off the UI thread, to the shared listener once the activity
	 * is resumed, and mark the intent as handled. Return whether such
	 * text comes.
	 */
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

		cancelShaderImport();
		if (uri != null) {
			pendingShaderUri = uri;
			long request = importRequest;
			Uri sourceUri = uri;
			intent.setAction(null);
			executor.execute(() -> {
				try {
					String source = readShaderSource(sourceUri);
					handler.post(() -> deliverImportResult(request, () -> {
						pendingShaderUri = null;
						if (source != null) {
							sharedListener.onShared(new Shared(source,
									getDisplayName(sourceUri)));
						} else {
							showImportError();
						}
					}));
				} catch (IOException | SecurityException e) {
					handler.post(() -> deliverImportResult(request, () -> {
						pendingShaderUri = null;
						showImportError();
					}));
				}
			});
			return true;
		} else if (Intent.ACTION_SEND.equals(intent.getAction())) {
			// Text shared from another app comes as an extra.
			CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
			if (text != null) {
				if (text.length() > ShaderSourceReader.MAX_SOURCE_LENGTH) {
					showImportError();
					return false;
				}
				intent.setAction(null);
				// Text without a file has no name.
				sharedListener.onShared(new Shared(text.toString(), null));
				return true;
			}
		}
		return false;
	}

	/** Text another app shared or opened, and its name, or null. */
	public record Shared(@NonNull String text, @Nullable String name) {
	}

	/** Takes text another app shared or opened. */
	public interface SharedListener {
		void onShared(@NonNull Shared shared);
	}

	private void cancelShaderImport() {
		++importRequest;
		pendingShaderUri = null;
		pendingImportResult = null;
	}

	// A shader chosen for the editor replaces the text read for it.
	private void cancelImportIntoEditor() {
		if (sharedListener == openInEditor) {
			cancelShaderImport();
		}
	}

	public void onAllShadersDeleted() {
		// Empty-list refreshes must not cancel an incoming shader.
		if (pendingShaderUri == null) {
			selectShader(NO_SHADER);
		}
	}

	private void deliverImportResult(long request, @NonNull Runnable result) {
		if (!isCurrentImport(request)) {
			return;
		}
		if (activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
			result.run();
		} else {
			pendingImportResult = () -> deliverImportResult(request, result);
		}
	}

	private boolean isCurrentImport(long request) {
		return request == importRequest &&
				!activity.isFinishing() && !activity.isDestroyed();
	}

	private void openShared(@NonNull Shared shared) {
		if (isModified() && !saveShader()) {
			return;
		}
		PreviewActivity.renderStatus.reset();
		applyShader(NO_SHADER, shared.text(), 1f,
				shared.name() != null ? shared.name() : DEFAULT_TITLE);
		setModified(true);
	}

	private void showImportError() {
		Message.show(activity, R.string.unsuitable_text);
	}

	@Nullable
	private String readShaderSource(@NonNull Uri uri) throws IOException {
		try (var in = activity.getContentResolver().openInputStream(uri)) {
			if (in == null) {
				return null;
			}
			// A reader keeps characters that span two buffers intact.
			try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return ShaderSourceReader.read(reader);
			}
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
			cancelImportIntoEditor();
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

	/** The shader selected drew a frame. */
	public void drew() {
		drewShaderId = selectedShaderId;
	}

	static long getPendingCrashShaderId(boolean runInBackground,
			long shaderId, long drewShaderId) {
		return runInBackground && shaderId > 0 && shaderId != drewShaderId
				? shaderId
				: 0;
	}

	private void updatePendingCrashShaderId() {
		ShaderEditorApp.preferences.setPendingCrashShaderId(
				getPendingCrashShaderId(
						ShaderEditorApp.preferences.doesRunInBackground(),
						selectedShaderId, drewShaderId));
	}
}
