package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class AccelerometerListener extends AbstractListener {
	// Gravity, then linear acceleration, both from the same event.
	private final float[] values = new float[6];

	public AccelerometerListener(SensorRegistry sensors) {
		super(sensors, 6);
	}

	public boolean register() {
		return register(Sensor.TYPE_ACCELEROMETER);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		if (last > 0) {
			final float a = .8f;
			final float b = 1f - a;

			values[0] = a * values[0] + b * event.values[0];
			values[1] = a * values[1] + b * event.values[1];
			values[2] = a * values[2] + b * event.values[2];

			values[3] = event.values[0] - values[0];
			values[4] = event.values[1] - values[1];
			values[5] = event.values[2] - values[2];
			sample.publish(values);
		}

		last = event.timestamp;
	}
}
