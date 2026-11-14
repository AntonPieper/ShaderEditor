package de.markusfisch.android.shadereditor.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.widget.ShaderView;

public class PreviewActivity extends AppCompatActivity {
	public static class RenderStatus {
		private volatile int fps;
		private volatile List<ProjectDiagnostic> infoLog;
		private volatile byte[] thumbnail;

		RenderStatus() {
			reset();
		}

		public void reset() {
			fps = 0;
			infoLog = null;
			thumbnail = null;
		}

		public int getFps() {
			return fps;
		}

		public List<ProjectDiagnostic> getInfoLog() {
			return infoLog;
		}

		public byte[] getThumbnail() {
			return thumbnail;
		}

		public void setThumbnail(byte[] thumbnail) {
			this.thumbnail = thumbnail;
		}
	}

	public static final String FRAGMENT_SHADER = "fragment_shader";
	public static final String QUALITY = "quality";
	public static volatile RenderStatus renderStatus = new RenderStatus();
	private final RenderStatus status = new RenderStatus();

	private final Runnable finishRunnable = this::finish;
	private final Runnable thumbnailRunnable = new Runnable() {
		@Override
		public void run() {
			if (shaderView == null) {
				return;
			}

			shaderView.getEngine().requestThumbnail(
					status::setThumbnail);
		}
	};

	private ShaderView shaderView;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);

		renderStatus = status;
		shaderView = new ShaderView(this);

		if (!setShaderFromIntent(getIntent())) {
			finish();
			return;
		}
		setResult(RESULT_OK);

		shaderView.getEngine().setListener(
				new ShaderEngine.Listener() {
					@Override
					public void onFramesPerSecond(int fps) {
						// Invoked from the GL thread.
						status.fps = fps;
					}

					@Override
					public void onDiagnostics(@NonNull List<ProjectDiagnostic> infoLog) {
						// Invoked from the GL thread.
						status.infoLog = infoLog;
						if (ProjectDiagnostic.anyError(infoLog)) {
							runOnUiThread(finishRunnable);
						}
					}
				});

		setContentView(shaderView);

		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
						View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
						View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
						View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_FULLSCREEN);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		status.reset();
		renderStatus = status;

		if (!setShaderFromIntent(intent)) {
			finish();
			return;
		}
		shaderView.removeCallbacks(thumbnailRunnable);
		shaderView.postDelayed(thumbnailRunnable, 500);
	}

	@Override
	protected void onStart() {
		// Don't use onResume()/onPause() because in multi window mode
		// an activity may be paused but should still show animations.
		super.onStart();

		shaderView.onResume();
		shaderView.postDelayed(thumbnailRunnable, 500);
	}

	@Override
	protected void onStop() {
		super.onStop();

		shaderView.removeCallbacks(thumbnailRunnable);
		shaderView.onPause();
	}

	protected ShaderView getShaderView() {
		return shaderView;
	}

	protected boolean setShaderFromIntent(Intent intent) {
		String fragmentShader = intent != null
				? intent.getStringExtra(FRAGMENT_SHADER)
				: null;
		if (shaderView == null || fragmentShader == null) {
			return false;
		}

		shaderView.setShader(fragmentShader, intent.getFloatExtra(QUALITY, 1f));
		return true;
	}
}
