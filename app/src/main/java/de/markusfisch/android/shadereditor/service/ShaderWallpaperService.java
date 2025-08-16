package de.markusfisch.android.shadereditor.service;

import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.engine.error.EngineError;
import de.markusfisch.android.shadereditor.platform.ui.AndroidEngineBridge;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.runner.plugin.ShaderRunnerPlugin;

public class ShaderWallpaperService extends WallpaperService {
	private static final String TAG = "ShaderWallpaperService";
	private static ShaderWallpaperEngine engine;

	public static boolean isRunning() {
		return engine != null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		engine = null;
	}

	@Override
	public Engine onCreateEngine() {
		engine = new ShaderWallpaperEngine();
		return engine;
	}

	private class ShaderWallpaperEngine
			extends Engine
			implements SharedPreferences.OnSharedPreferenceChangeListener {
		private ShaderWallpaperView view;
		private AndroidEngineBridge androidEngineBridge;

		private ShaderWallpaperEngine() {
			super();
			ShaderEditorApp.preferences.getSharedPreferences()
					.registerOnSharedPreferenceChangeListener(this);
			setTouchEventsEnabled(true);
		}

		@Override
		public void onSharedPreferenceChanged(
				SharedPreferences preferences,
				String key) {
			if (Preferences.WALLPAPER_SHADER.equals(key)) {
				setShader();
			}
		}

		@Override
		public void onCreate(SurfaceHolder holder) {
			super.onCreate(holder);
			view = new ShaderWallpaperView();
			androidEngineBridge = new AndroidEngineBridge(
					ShaderWallpaperService.this,
					view,
					null,
					new AndroidEngineBridge.ShaderExecutionListener() {
						@Override
						public void onEngineError(@NonNull List<EngineError> errors) {
							if (errors.isEmpty()) return;
							Log.e(TAG, "Shader engine error: " + errors.get(0).message());
						}

						@Override
						public void onRenderQualityChanged(float quality) {
							// Not used in wallpaper.
						}
					},
					Collections.singletonList(ShaderRunnerPlugin::new)
			);
			setShader();
		}

		@Override
		public void onDestroy() {
			super.onDestroy();
			// Unregister listener to prevent memory leaks.
			ShaderEditorApp.preferences.getSharedPreferences()
					.unregisterOnSharedPreferenceChangeListener(this);
			if (androidEngineBridge != null) {
				androidEngineBridge.onPause();
				androidEngineBridge = null;
			}
			if (view != null) {
				view.destroy();
				view = null;
			}
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			super.onVisibilityChanged(visible);
			if (androidEngineBridge == null) return;
			if (visible) {
				androidEngineBridge.onResume();
			} else {
				androidEngineBridge.onPause();
			}
		}

		@Override
		public void onOffsetsChanged(
				float xOffset,
				float yOffset,
				float xStep,
				float yStep,
				int xPixels,
				int yPixels) {
			super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels);
			if (androidEngineBridge != null) {
				androidEngineBridge.onOffsetsChanged(xOffset, yOffset);
			}
		}

		private void setShader() {
			DataSource dataSource = Database.getInstance(
					ShaderWallpaperService.this).getDataSource();

			long shaderId = ShaderEditorApp.preferences.getWallpaperShader();
			DataRecords.Shader shader = dataSource.shader.getShader(shaderId);

			// If the saved shader doesn't exist, pick a random one.
			if (shader == null) {
				shader = dataSource.shader.getRandomShader();

				// If there are no shaders at all, we can't do anything.
				if (shader == null) {
					return;
				}

				// Update the preferences to store the new random shader ID.
				ShaderEditorApp.preferences.setWallpaperShader(shader.id());
			}

			if (androidEngineBridge != null) {
				// Note: The provided AndroidEngineBridge does not seem to apply
				// the quality setting to the render resolution. This call is
				// made for API consistency, but may not have a visual effect.
				androidEngineBridge.setQuality(shader.quality());
				androidEngineBridge.setFragmentShader(shader.fragmentShader());
			}
		}

		private class ShaderWallpaperView extends GLSurfaceView {
			public ShaderWallpaperView() {
				super(ShaderWallpaperService.this);
			}

			@Override
			public final SurfaceHolder getHolder() {
				return ShaderWallpaperEngine.this.getSurfaceHolder();
			}

			public void destroy() {
				super.onDetachedFromWindow();
			}
		}
	}
}