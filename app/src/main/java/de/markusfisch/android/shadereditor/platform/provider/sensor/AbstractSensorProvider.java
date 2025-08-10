package de.markusfisch.android.shadereditor.platform.provider.sensor;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataProvider;

public abstract class AbstractSensorProvider<T> implements DataProvider<T> {
	protected final RotationSensorManager sensorManager;

	/**
	 * Constructs a provider that depends on a given RotationSensorManager.
	 *
	 * @param sensorManager The sensor manager instance.
	 */
	protected AbstractSensorProvider(@NonNull RotationSensorManager sensorManager) {
		this.sensorManager = sensorManager;
	}

	@Override
	public void start() {
		sensorManager.start();
	}

	@Override
	public void stop() {
		sensorManager.stop();
	}
}