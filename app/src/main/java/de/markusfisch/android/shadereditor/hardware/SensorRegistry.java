package de.markusfisch.android.shadereditor.hardware;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

// Registers sensor listeners, so a test can decide which sensors exist.
public interface SensorRegistry {
	// False when the device has no sensor of this type.
	boolean register(@NonNull SensorEventListener listener, int sensorType);

	void unregister(@NonNull SensorEventListener listener);

	@NonNull
	static SensorRegistry of(@NonNull Context context) {
		SensorManager sensorManager = (SensorManager) context.getSystemService(
				Context.SENSOR_SERVICE);
		return new SensorRegistry() {
			@Override
			public boolean register(@NonNull SensorEventListener listener, int sensorType) {
				Sensor sensor;
				return sensorManager != null &&
						(sensor = sensorManager.getDefaultSensor(sensorType)) != null &&
						sensorManager.registerListener(listener, sensor,
								ShaderEditorApp.preferences.getSensorDelay());
			}

			@Override
			public void unregister(@NonNull SensorEventListener listener) {
				sensorManager.unregisterListener(listener);
			}
		};
	}
}
