package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class LightListener extends AbstractListener {
	public LightListener(SensorRegistry sensors) {
		super(sensors, 1);
	}

	public boolean register() {
		return register(Sensor.TYPE_LIGHT);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		sample.publish(event.values);
	}
}


