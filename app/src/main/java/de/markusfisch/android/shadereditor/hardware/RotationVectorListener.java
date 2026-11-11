package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class RotationVectorListener extends AbstractListener {
	public RotationVectorListener(SensorRegistry sensors) {
		super(sensors, 3);
	}

	public boolean register() {
		// Prefer TYPE_GAME_ROTATION_VECTOR if possible because it doesn't
		// depend from a geomagnetic sensor.
		return register(Sensor.TYPE_GAME_ROTATION_VECTOR) ||
				register(Sensor.TYPE_ROTATION_VECTOR);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		sample.publish(event.values);
	}
}
