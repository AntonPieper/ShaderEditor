package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class LightListener extends AbstractListener {
	private float ambient = 0f;

	public LightListener(SensorRegistry sensors) {
		super(sensors);
	}

	public boolean register() {
		return register(Sensor.TYPE_LIGHT);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		ambient = event.values[0];
	}

	public float getAmbient() {
		return ambient;
	}
}


