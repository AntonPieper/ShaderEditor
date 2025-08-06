package de.markusfisch.android.shadereditor.runner.provider;

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataKey;
import de.markusfisch.android.shadereditor.engine.data.DataProvider;

public class NightModeProvider implements DataProvider<Boolean> {
	public static final DataKey<Boolean> IS_NIGHT_MODE = DataKey.create("system.isNightMode", Boolean.class);

	private Boolean isNightMode = false;

	@NonNull
	@Override
	public DataKey<Boolean> getKey() {
		return IS_NIGHT_MODE;
	}

	@Override
	public void start(@NonNull Context context) {
		// When we start, get the initial value.
		// A more complex provider might register a ContentObserver or BroadcastReceiver here.
		// For night mode, the value is easily accessible via the context's resources.
		int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
		this.isNightMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
	}

	@Override
	public void stop(@NonNull Context context) {
		// No listener was registered, so nothing to do here.
	}

	@NonNull
	@Override
	public Boolean getValue() {
		// Note: This value is only updated on start(). For real-time updates,
		// you would need to listen to onConfigurationChanged in an Activity/Service
		// and push the update to the provider.
		return isNightMode;
	}
}