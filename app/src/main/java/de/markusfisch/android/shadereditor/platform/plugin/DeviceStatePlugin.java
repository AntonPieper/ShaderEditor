package de.markusfisch.android.shadereditor.platform.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.data.ObservableDataProvider;
import de.markusfisch.android.shadereditor.engine.data.SystemDataKeys;
import de.markusfisch.android.shadereditor.engine.util.observer.ObservableValue;

public class DeviceStatePlugin implements Plugin {
	// Renamed the existing one to be more specific
	private final ObservableValue<Boolean> isPluggedIn = ObservableValue.of(false);
	// Added a new observable for the charging state
	private final ObservableValue<Boolean> isCharging = ObservableValue.of(false);
	private final ObservableValue<Float> batteryLevel = ObservableValue.of(1f);

	private final Context context;
	private BatteryLevelReceiver receiver;

	public DeviceStatePlugin(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	@Override
	public void onSetup(@NonNull Engine engine) {
		// Register your providers. You might need to add a new SystemDataKey
		// for BATTERY_IS_CHARGING.
		engine.registerProviderFactory(
				SystemDataKeys.POWER_CONNECTED, // This will now represent "isPluggedIn"
				() -> new ObservableDataProvider<>(isPluggedIn)
		).registerProviderFactory(
				SystemDataKeys.BATTERY_IS_CHARGING, // Example for the new state
				() -> new ObservableDataProvider<>(isCharging)
		).registerProviderFactory(
				SystemDataKeys.BATTERY_LEVEL,
				() -> new ObservableDataProvider<>(batteryLevel)
		);

		// Get the initial sticky intent to set the current power state correctly on startup
		IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		Intent batteryStatus = context.registerReceiver(null, intentFilter);

		if (batteryStatus != null) {
			updateStates(batteryStatus);
		}

		// Register a broadcast receiver to listen for FUTURE battery changes.
		receiver = new BatteryLevelReceiver();
		context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
	}

	@Override
	public void onTeardown(@NonNull Engine engine) {
		if (receiver != null) {
			context.unregisterReceiver(receiver);
			receiver = null;
		}
	}

	/**
	 * A helper method to update all battery-related states from a single intent.
	 * This avoids code duplication.
	 */
	private void updateStates(@NonNull Intent intent) {
		// 1. Determine if CONNECTED / PLUGGED IN
		int chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		boolean plugged = chargePlug == BatteryManager.BATTERY_PLUGGED_AC ||
				chargePlug == BatteryManager.BATTERY_PLUGGED_USB ||
				chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS;
		isPluggedIn.set(plugged);

		// 2. Determine if the battery is actively CHARGING
		int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
		isCharging.set(charging);

		// 3. Determine battery LEVEL
		int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		if (level != -1 && scale != -1) {
			batteryLevel.set((float) level / (float) scale);
		}
	}

	private class BatteryLevelReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent != null && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
				updateStates(intent);
			}
		}
	}
}