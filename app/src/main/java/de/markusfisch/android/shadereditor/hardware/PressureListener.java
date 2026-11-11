package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class PressureListener extends AbstractListener {
	public PressureListener(SensorRegistry sensors) {
		super(sensors, 1);
	}

	public boolean register() {
		return register(Sensor.TYPE_PRESSURE);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		sample.publish(event.values);
	}
}



