package de.markusfisch.android.shadereditor.runner.provider.sensor;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataKey;

public class GeomagneticProvider extends AbstractSensorProvider<float[]> {
	@NonNull
	@Override
	public DataKey<float[]> getKey() {
		return SensorDataKeys.GEOMAGNETIC;
	}

	@NonNull
	@Override
	public float[] getValue() {
		return RotationSensorManager.getInstance().getGeomagnetic();
	}
}