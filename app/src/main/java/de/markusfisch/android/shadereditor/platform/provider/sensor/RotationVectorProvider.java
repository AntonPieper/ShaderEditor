package de.markusfisch.android.shadereditor.platform.provider.sensor;

import androidx.annotation.NonNull;

public class RotationVectorProvider extends AbstractSensorProvider<float[]> {

	/**
	 * Constructs a provider that depends on a given RotationSensorManager.
	 *
	 * @param sensorManager The sensor manager instance.
	 */
	public RotationVectorProvider(@NonNull RotationSensorManager sensorManager) {
		super(sensorManager);
	}

	@NonNull
	@Override
	public float[] getValue() {
		return sensorManager.getRotationVector();
	}
}