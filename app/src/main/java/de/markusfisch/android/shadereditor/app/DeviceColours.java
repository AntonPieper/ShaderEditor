package de.markusfisch.android.shadereditor.app;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.WeakHashMap;

import de.markusfisch.android.shadereditor.R;

/**
 * Gives every activity the device's accent when the setting is on, and
 * recreates an activity that comes back after the setting changed.
 */
@RequiresApi(Build.VERSION_CODES.S)
final class DeviceColours implements Application.ActivityLifecycleCallbacks {
	// Whether each living activity was created with the device's accent.
	private final WeakHashMap<Activity, Boolean> created = new WeakHashMap<>();

	@Override
	public void onActivityPreCreated(@NonNull Activity activity,
			@Nullable Bundle savedInstanceState) {
		boolean on = ShaderEditorApp.preferences.useDeviceColours();
		if (on) {
			activity.getTheme().applyStyle(
					R.style.ThemeOverlay_ShaderEditor_DeviceAccent, true);
		}
		created.put(activity, on);
	}

	@Override
	public void onActivityResumed(@NonNull Activity activity) {
		Boolean on = created.get(activity);
		if (on != null &&
				on != ShaderEditorApp.preferences.useDeviceColours()) {
			activity.recreate();
		}
	}

	@Override
	public void onActivityCreated(@NonNull Activity activity,
			@Nullable Bundle savedInstanceState) {
	}

	@Override
	public void onActivityStarted(@NonNull Activity activity) {
	}

	@Override
	public void onActivityPaused(@NonNull Activity activity) {
	}

	@Override
	public void onActivityStopped(@NonNull Activity activity) {
	}

	@Override
	public void onActivitySaveInstanceState(@NonNull Activity activity,
			@NonNull Bundle outState) {
	}

	@Override
	public void onActivityDestroyed(@NonNull Activity activity) {
	}
}
