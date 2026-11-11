package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class GravityListener extends AbstractListener {
	public GravityListener(SensorRegistry sensors) {
		super(sensors, 3);
	}

	public boolean register() {
		return register(Sensor.TYPE_GRAVITY);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		sample.publish(event.values);
	}
}
