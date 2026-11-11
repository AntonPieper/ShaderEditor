package de.markusfisch.android.shadereditor.hardware;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

public class MagneticFieldListener extends AbstractListener {
	// The field, then the filtered field, both from the same event.
	private final float[] values = new float[6];

	public MagneticFieldListener(SensorRegistry sensors) {
		super(sensors, 6);
	}

	public boolean register() {
		return register(Sensor.TYPE_MAGNETIC_FIELD);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		final float a = .8f;
		final float b = 1f - a;

		values[3] = a * values[3] + b * event.values[0];
		values[4] = a * values[4] + b * event.values[1];
		values[5] = a * values[5] + b * event.values[2];

		values[0] = event.values[0];
		values[1] = event.values[1];
		values[2] = event.values[2];
		sample.publish(values);
	}
}

