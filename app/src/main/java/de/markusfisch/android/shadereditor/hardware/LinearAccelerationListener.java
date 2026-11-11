package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class LinearAccelerationListener extends AbstractListener {
	public LinearAccelerationListener(SensorRegistry sensors) {
		super(sensors, 3);
	}

	public boolean register() {
		return register(Sensor.TYPE_LINEAR_ACCELERATION);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		sample.publish(event.values);
	}
}
