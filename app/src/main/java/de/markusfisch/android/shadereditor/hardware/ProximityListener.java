package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class ProximityListener extends AbstractListener {
	private float centimeters = 0f;

	public ProximityListener(SensorRegistry sensors) {
		super(sensors);
	}

	public boolean register() {
		return register(Sensor.TYPE_PROXIMITY);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		centimeters = event.values[0];
	}

	public float getCentimeters() {
		return centimeters;
	}
}




