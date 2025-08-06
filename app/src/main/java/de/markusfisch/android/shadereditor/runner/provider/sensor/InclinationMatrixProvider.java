package de.markusfisch.android.shadereditor.runner.provider.sensor;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataKey;

public class InclinationMatrixProvider extends AbstractSensorProvider<float[]> {
	@NonNull
	@Override
	public DataKey<float[]> getKey() {
		return SensorDataKeys.INCLINATION_MATRIX;
	}

	@NonNull
	@Override
	public float[] getValue() {
		return RotationSensorManager.getInstance().getInclinationMatrix();
	}
}