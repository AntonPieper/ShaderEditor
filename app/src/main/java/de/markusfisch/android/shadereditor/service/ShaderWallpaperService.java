package de.markusfisch.android.shadereditor.service;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.LegacyShaderProjectSource;
import de.markusfisch.android.shadereditor.project.ShaderProjectSession;
import de.markusfisch.android.shadereditor.receiver.BatteryLevelReceiver;
import de.markusfisch.android.shadereditor.widget.ShaderView;

public class ShaderWallpaperService extends WallpaperService {
	private static ShaderWallpaperEngine engine;

	private ComponentName batteryLevelComponent;

	public static boolean isRunning() {
		return engine != null;
	}

	public static void setRenderMode(int renderMode) {
		if (engine != null) {
			engine.setRenderMode(renderMode);
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		batteryLevelComponent = new ComponentName(this,
				BatteryLevelReceiver.class);
		enableComponent(batteryLevelComponent, true);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		enableComponent(batteryLevelComponent, false);
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
		private final Handler mainHandler = new Handler(Looper.getMainLooper());
		private ShaderWallpaperView view;
		private long runtimeShaderId;
		@Nullable
		private ShaderProjectSession runtimeSession;
		// GL thread only.
		private String shownWarnings = "";

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
			view.getEngine().setListener(new ShaderEngine.Listener() {
				@Override
				public void onDiagnostics(
						@NonNull List<ProjectDiagnostic> diagnostics) {
					showWarnings(diagnostics);
				}

				@Override
				public void onFramesPerSecond(int fps) {
				}
			});
			// Draws once onVisibilityChanged() resumes it.
			view.onPause();
			setShader();
		}

		@Override
		public void onDestroy() {
			super.onDestroy();
			// Unregister listener to prevent memory leaks.
			ShaderEditorApp.preferences.getSharedPreferences()
					.unregisterOnSharedPreferenceChangeListener(this);
			if (view != null) {
				view.onPause();
				saveRuntime();
				view.destroy();
				view = null;
			}
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			super.onVisibilityChanged(visible);
			if (view == null) {
				return;
			}
			if (visible) {
				view.onResume();
			} else {
				view.onPause();
				saveRuntime();
			}
		}

		@Override
		public void onTouchEvent(MotionEvent e) {
			super.onTouchEvent(e);
			if (view != null) {
				view.getEngine().pointer(e);
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
			if (view != null) {
				view.getEngine().offset(xOffset, yOffset);
			}
		}

		private ShaderWallpaperEngine() {
			super();
			ShaderEditorApp.preferences.getSharedPreferences()
					.registerOnSharedPreferenceChangeListener(this);
			setTouchEventsEnabled(true);
		}

		private void setRenderMode(int renderMode) {
			if (view != null) {
				view.setRenderMode(renderMode);
			}
		}

		// Saves the runtime of the shader set last while it is shown.
		private void saveRuntime() {
			if (view == null || runtimeSession == null || isPreview()) {
				return;
			}
			long nanos = view.getEngine().getRuntimeNanos(runtimeSession);
			if (nanos >= 0) {
				ShaderEditorApp.preferences.setWallpaperRuntimeNanos(
						runtimeShaderId, nanos);
			}
		}

		private void setShader() {
			ShaderProjectSession projectSession = openWallpaperProjectSession();
			if (view != null && projectSession != null) {
				saveRuntime();
				runtimeShaderId = ShaderEditorApp.preferences.getWallpaperShader();
				runtimeSession = projectSession;
				view.setShader(projectSession, isPreview()
						? 0
						: ShaderEditorApp.preferences.getWallpaperRuntimeNanos(
								runtimeShaderId));
			}
		}

		// A wallpaper has no place to list diagnostics, so it shows what
		// it cannot provide in a toast, once until that changes.
		private void showWarnings(@NonNull List<ProjectDiagnostic> diagnostics) {
			var warnings = new StringBuilder();
			for (var diagnostic : diagnostics) {
				if (diagnostic.severity() == ProjectDiagnostic.Severity.WARNING) {
					if (warnings.length() > 0) {
						warnings.append('\n');
					}
					warnings.append(diagnostic.message());
				}
			}
			String text = warnings.toString();
			if (text.equals(shownWarnings)) {
				return;
			}
			shownWarnings = text;
			if (!text.isEmpty()) {
				mainHandler.post(() -> Toast.makeText(ShaderWallpaperService.this,
						text, Toast.LENGTH_LONG).show());
			}
		}

		@Nullable
		private ShaderProjectSession openWallpaperProjectSession() {
			DataSource dataSource = Database.getInstance(
					ShaderWallpaperService.this).getDataSource();

			long shaderId = ShaderEditorApp.preferences.getWallpaperShader();
			DataRecords.Shader shader = dataSource.shader.getShader(shaderId);

			// If the saved shader doesn't exist, pick a random one.
			if (shader == null) {
				shader = dataSource.shader.getRandomShader();

				// If there are no shaders at all, we can't do anything.
				if (shader == null) {
					return null;
				}

				// Update the preferences to store the new random shader ID.
				ShaderEditorApp.preferences.setWallpaperShader(shader.id());
			}

			return new LegacyShaderProjectSource(shader).openSession();
		}

		private class ShaderWallpaperView extends ShaderView {
			public ShaderWallpaperView() {
				super(ShaderWallpaperService.this,
						ShaderEditorApp.preferences.isBatteryLow()
								? GLSurfaceView.RENDERMODE_WHEN_DIRTY
								: GLSurfaceView.RENDERMODE_CONTINUOUSLY);
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

	private void enableComponent(ComponentName name, boolean enable) {
		getPackageManager().setComponentEnabledSetting(name,
				enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
						PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP);
	}
}