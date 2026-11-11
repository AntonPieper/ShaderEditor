package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

public abstract class AbstractListener implements SensorEventListener {
	long last = 0;

	private final SensorRegistry sensors;

	private boolean listening = false;

	AbstractListener(SensorRegistry sensors) {
		this.sensors = sensors;
	}

	public void unregister() {
		if (!listening) {
			return;
		}

		sensors.unregister(this);
		listening = false;
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		last = event.timestamp;
	}

	boolean register(int type) {
		if (listening) {
			return false;
		}

		last = 0;
		listening = sensors.register(this, type);

		return listening;
	}
}
