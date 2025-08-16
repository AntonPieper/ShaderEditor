package de.markusfisch.android.shadereditor.engine.data;

public class SystemDataKeys {
	public static final DataKey<Float> TIME = DataKey.of("system.time", Float.class);
	public static final DataKey<Boolean> IS_NIGHT_MODE = DataKey.of(
			"system.isNightMode", Boolean.class);
	public static final DataKey<Float> BATTERY_LEVEL = DataKey.of(
			"system.batteryLevel", Float.class);
	public static final DataKey<Boolean> POWER_CONNECTED = DataKey.of(
			"system.powerConnected", Boolean.class);

	public static final DataKey<Boolean> BATTERY_IS_CHARGING = DataKey.of(
			"system.batteryIsCharging", Boolean.class);

	private SystemDataKeys() {
		throw new UnsupportedOperationException(
				"This is a utility class and cannot be instantiated");
	}
}