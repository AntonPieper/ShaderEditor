package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class PressureListener extends AbstractListener {
	private float pressure = 0f;

	public PressureListener(SensorRegistry sensors) {
		super(sensors);
	}

	public boolean register() {
		return register(Sensor.TYPE_PRESSURE);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		pressure = event.values[0];
	}

	public float getPressure() {
		return pressure;
	}
}



