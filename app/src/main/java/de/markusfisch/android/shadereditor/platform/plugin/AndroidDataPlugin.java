package de.markusfisch.android.shadereditor.platform.plugin;

import android.content.Context;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.data.SensorDataKeys;
import de.markusfisch.android.shadereditor.engine.data.SystemDataKeys;
import de.markusfisch.android.shadereditor.platform.provider.NightModeProvider;
import de.markusfisch.android.shadereditor.platform.provider.TimeProvider;
import de.markusfisch.android.shadereditor.platform.provider.sensor.GeomagneticProvider;
import de.markusfisch.android.shadereditor.platform.provider.sensor.GravityProvider;
import de.markusfisch.android.shadereditor.platform.provider.sensor.InclinationMatrixProvider;
import de.markusfisch.android.shadereditor.platform.provider.sensor.RotationMatrixProvider;
import de.markusfisch.android.shadereditor.platform.provider.sensor.RotationSensorManager;
import de.markusfisch.android.shadereditor.platform.provider.sensor.RotationVectorProvider;

public class AndroidDataPlugin implements Plugin {
	private final Context context;

	public AndroidDataPlugin(@NonNull Context context) {
		this.context = context;
	}

	@Override
	public void onSetup(@NonNull Engine engine) {
		var sensorManager = new RotationSensorManager(context);
		engine
				.registerProviderFactory(
						SystemDataKeys.TIME,
						TimeProvider::new)
				.registerProviderFactory(
						SystemDataKeys.IS_NIGHT_MODE,
						() -> new NightModeProvider(context))
				.registerProviderFactory(
						SensorDataKeys.ROTATION_MATRIX,
						() -> new RotationMatrixProvider(sensorManager))
				.registerProviderFactory(
						SensorDataKeys.GRAVITY,
						() -> new GravityProvider(sensorManager))
				.registerProviderFactory(
						SensorDataKeys.GEOMAGNETIC,
						() -> new GeomagneticProvider(sensorManager))
				.registerProviderFactory(
						SensorDataKeys.INCLINATION_MATRIX,
						() -> new InclinationMatrixProvider(sensorManager))
				.registerProviderFactory(
						SensorDataKeys.ROTATION_VECTOR,
						() -> new RotationVectorProvider(sensorManager));
	}
}
