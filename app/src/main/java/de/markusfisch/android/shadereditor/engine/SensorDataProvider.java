package de.markusfisch.android.shadereditor.engine;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A simplified, declarative provider for sensor data.
 * It synchronizes its active listeners with a given "desired state" set of sensors.
 */
public class SensorDataProvider implements DefaultLifecycleObserver, SensorEventListener {
	private static final String TAG = "SensorDataProvider";

	@NonNull
	private final SensorManager sensorManager;
	private final Map<Integer, Sensor> activeSensors = new HashMap<>();
	private final Map<Integer, float[]> sensorValues = new HashMap<>();

	public SensorDataProvider(@NonNull Context context, @NonNull LifecycleOwner owner) {
		this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		owner.getLifecycle().addObserver(this);
	}

	/**
	 * Synchronizes the active sensor listeners with the required set.
	 * It starts listening to new sensors and stops listening to unneeded ones.
	 *
	 * @param requiredSensors The complete Set of sensor types that should be active.
	 */
	public void syncRequiredSensors(@NonNull Set<Integer> requiredSensors) {
		Set<Integer> currentSensorTypes = activeSensors.keySet();

		// Determine which sensors to stop
		Set<Integer> sensorsToStop = new HashSet<>(currentSensorTypes);
		sensorsToStop.removeAll(requiredSensors);

		// Determine which sensors to start
		Set<Integer> sensorsToStart = new HashSet<>(requiredSensors);
		sensorsToStart.removeAll(currentSensorTypes);

		sensorsToStop.forEach(this::stopListening);
		sensorsToStart.forEach(this::startListening);
	}

	private void startListening(int sensorType) {
		if (activeSensors.containsKey(sensorType)) return; // Already listening

		Sensor sensor = sensorManager.getDefaultSensor(sensorType);
		if (sensor != null) {
			sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
			activeSensors.put(sensorType, sensor);
			Log.d(TAG, "Started listening to sensor: " + sensor.getName());
		} else {
			Log.w(TAG, "Could not find sensor of type: " + sensorType);
		}
	}

	private void stopListening(int sensorType) {
		Sensor sensor = activeSensors.remove(sensorType);
		if (sensor != null) {
			sensorManager.unregisterListener(this, sensor);
			sensorValues.remove(sensorType);
			Log.d(TAG, "Stopped listening to sensor: " + sensor.getName());
		}
	}

	public Map<Integer, float[]> getLatestSensorValues() {
		return Collections.unmodifiableMap(this.sensorValues);
	}

	@Override
	public void onPause(@NonNull LifecycleOwner owner) {
		Log.d(TAG, "onPause: Unregistering all listeners.");
		sensorManager.unregisterListener(this);
	}

	@Override
	public void onResume(@NonNull LifecycleOwner owner) {
		Log.d(TAG, "onResume: Re-registering active listeners.");
		// Re-register all sensors that were active before pausing.
		activeSensors.values().forEach(sensor ->
				sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
		);
	}

	@Override
	public void onSensorChanged(@NonNull SensorEvent event) {
		sensorValues.put(event.sensor.getType(), event.values.clone());
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}
}