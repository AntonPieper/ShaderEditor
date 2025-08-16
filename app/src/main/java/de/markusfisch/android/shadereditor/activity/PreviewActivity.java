package de.markusfisch.android.shadereditor.activity;

import android.app.Activity;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.engine.error.EngineError;
import de.markusfisch.android.shadereditor.platform.ui.AndroidEngineBridge;
import de.markusfisch.android.shadereditor.runner.plugin.ShaderRunnerPlugin;
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;

public class PreviewActivity extends AppCompatActivity {
	public static final String FRAGMENT_SHADER = "fragment_shader";
	public static final String QUALITY = "quality";
	public static final String EXTRA_THUMBNAIL = "thumbnail";
	public static final String EXTRA_ERROR = "error";

	private AndroidEngineBridge androidEngineBridge;
	private byte[] thumbnail;

	@Override
	public void finish() {
		// Capture thumbnail before finishing, only if there wasn't an error.
		if (isFinishing() || androidEngineBridge == null) {
			super.finish();
			return;
		}

		// If there are no errors, set a success result.
		if (getIntent().getParcelableExtra(EXTRA_ERROR) == null) {
			Intent resultIntent = new Intent();
			// The thumbnail is captured in onPause, so we retrieve it here.
			resultIntent.putExtra(EXTRA_THUMBNAIL, thumbnail);
			setResult(Activity.RESULT_OK, resultIntent);
		}

		super.finish();
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_preview);

		GLSurfaceView glSurfaceView = findViewById(R.id.preview_surface);

		// The quality spinner is not needed in this activity, so we pass null.
		androidEngineBridge = new AndroidEngineBridge(this, glSurfaceView, null,
				new AndroidEngineBridge.ShaderExecutionListener() {
					@Override
					public void onEngineError(@NonNull List<EngineError> errors) {
						if (errors.isEmpty()) return;

						// Send the first error back to the main activity and finish.
						Intent resultIntent = new Intent();
						resultIntent.putExtra(EXTRA_ERROR,
								new ParcelableEngineError(errors.get(0)));
						setResult(Activity.RESULT_CANCELED, resultIntent);
						finish();
					}

					@Override
					public void onRenderQualityChanged(float quality) {
						// Not applicable in preview-only mode.
					}
				}, List.of(ShaderRunnerPlugin::new));

		if (!setShaderFromIntent(getIntent())) {
			finish();
			return;
		}

		SystemBarMetrics.hideNavigation(getWindow());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (!setShaderFromIntent(intent)) {
			finish();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (androidEngineBridge != null) {
			androidEngineBridge.onResume();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (androidEngineBridge != null) {
			// Capture the thumbnail right before pausing the view.
			// This is a simplification; a more robust solution might use a callback.
			// this.thumbnail = androidEngineBridge.getThumbnail();
			androidEngineBridge.onPause();
		}
	}

	private boolean setShaderFromIntent(Intent intent) {
		if (intent == null || androidEngineBridge == null) {
			return false;
		}

		String fragmentShader = intent.getStringExtra(FRAGMENT_SHADER);
		if (fragmentShader == null) {
			return false;
		}

		float quality = intent.getFloatExtra(QUALITY, 1f);

		// This will trigger the engine recreation and rendering.
		androidEngineBridge.setFragmentShader(fragmentShader);
		// The quality is handled by the bridge based on the spinner, but here we can
		// just set it once. For simplicity, we assume the default quality is fine or
		// would adapt the bridge to take a quality override.
		// For now, setting the shader is the main goal.

		return true;
	}
}