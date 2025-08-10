package de.markusfisch.android.shadereditor.platform.provider.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

public class RotationSensorManager implements SensorEventListener {
	private static final String TAG = "RotationSensorManager";
	private static final float SMOOTHING_FACTOR = 0.1f; // Adjust for more/less smoothing

	private final float[] rotationMatrix = new float[16];
	private final float[] inclinationMatrix = new float[16];
	private final float[] gravity = new float[3];
	private final float[] geomagnetic = new float[3];
	private final float[] rotationVector = new float[5];

	// Temporary matrices for calculations
	private final float[] tempRotationMatrix9 = new float[9];
	private final float[] tempInclinationMatrix9 = new float[9];
	private final float[] tempRemappedRotationMatrix9 = new float[9];

	@NonNull
	private final Context context;
	private SensorManager sensorManager;
	@Nullable
	private Display display;

	private boolean useGravMagSensors = false;
	private boolean hasGravity = false;
	private boolean hasGeomagnetic = false;

	private volatile boolean isRunning = false;
	private int refCount = 0;

	public RotationSensorManager(@NonNull Context context) {
		this.context = context.getApplicationContext();
		resetMatrices();
	}

	public synchronized void start() {
		refCount++;
		if (isRunning) {
			return;
		}

		Log.d(TAG, "Starting sensor manager.");
		sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		if (sensorManager == null) {
			Log.e(TAG, "Cannot get SensorManager.");
			return;
		}

		// Set display for coordinate remapping
		WindowManager windowManager =
				(WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
		if (windowManager != null) {
			display = windowManager.getDefaultDisplay();
		}

		// 1. Try to use Gravity and Magnetic Field sensors
		Sensor gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
		Sensor magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

		if (gravitySensor != null && magneticSensor != null) {
			Log.d(TAG, "Using Gravity and Magnetic Field sensors.");
			sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME);
			sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_GAME);
			useGravMagSensors = true;
		} else {
			// 2. Fallback to Rotation Vector sensor
			Log.d(TAG, "Gravity/Magnetic sensors not available. Falling back to Rotation Vector.");
			Sensor rotationVectorSensor =
					sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
			if (rotationVectorSensor != null) {
				sensorManager.registerListener(this, rotationVectorSensor,
						SensorManager.SENSOR_DELAY_GAME);
				useGravMagSensors = false;
			} else {
				Log.e(TAG, "No suitable orientation sensors found.");
				return;
			}
		}

		isRunning = true;
	}

	public synchronized void stop() {
		refCount--;
		if (refCount > 0 || !isRunning) {
			return;
		}

		Log.d(TAG, "Stopping sensor manager.");
		if (sensorManager != null) {
			sensorManager.unregisterListener(this);
		}
		sensorManager = null;
		display = null;
		resetMatrices();
		hasGravity = false;
		hasGeomagnetic = false;
		isRunning = false;
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		synchronized (this) {
			if (!isRunning) return;

			if (useGravMagSensors) {
				handleGravityMagnetic(event);
			} else {
				handleRotationVector(event);
			}
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// Can be used to inform the user about sensor calibration status
	}

	// --- Getters for Providers ---
	public synchronized float[] getRotationMatrix() {
		return rotationMatrix.clone();
	}

	public synchronized float[] getInclinationMatrix() {
		return inclinationMatrix.clone();
	}

	public synchronized float[] getGravity() {
		return gravity.clone();
	}

	public synchronized float[] getGeomagnetic() {
		return geomagnetic.clone();
	}

	public synchronized float[] getRotationVector() {
		return rotationVector.clone();
	}

	private void resetMatrices() {
		Matrix.setIdentityM(rotationMatrix, 0);
		Matrix.setIdentityM(inclinationMatrix, 0);
		Arrays.fill(gravity, 0f);
		Arrays.fill(geomagnetic, 0f);
		Arrays.fill(rotationVector, 0f);
	}

	private void handleGravityMagnetic(@NonNull SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
			smoothSensorData(event.values, gravity, 3);
			hasGravity = true;
		} else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
			smoothSensorData(event.values, geomagnetic, 3);
			hasGeomagnetic = true;
		}

		if (hasGravity && hasGeomagnetic) {
			// Calculate rotation and inclination matrices
			boolean success = SensorManager.getRotationMatrix(
					tempRotationMatrix9,
					tempInclinationMatrix9,
					gravity,
					geomagnetic
			);

			if (success) {
				// Remap coordinates based on screen orientation
				remapCoordinates(tempRotationMatrix9, tempRemappedRotationMatrix9);

				// Convert 3x3 matrices to 4x4 OpenGL matrices
				to4x4Matrix(tempRemappedRotationMatrix9, rotationMatrix);
				to4x4Matrix(tempInclinationMatrix9, inclinationMatrix);
			}
		}
	}

	private void remapCoordinates(float[] inR, float[] outR) {
		if (display == null) {
			System.arraycopy(inR, 0, outR, 0, 9);
			return;
		}
		int axisX = SensorManager.AXIS_X;
		int axisY = SensorManager.AXIS_Y;

		switch (display.getRotation()) {
			case Surface.ROTATION_90:
				axisX = SensorManager.AXIS_Y;
				axisY = SensorManager.AXIS_MINUS_X;
				break;
			case Surface.ROTATION_180:
				axisX = SensorManager.AXIS_MINUS_X;
				axisY = SensorManager.AXIS_MINUS_Y;
				break;
			case Surface.ROTATION_270:
				axisX = SensorManager.AXIS_MINUS_Y;
				axisY = SensorManager.AXIS_X;
				break;
			case Surface.ROTATION_0:
			default:
				// No change needed
				break;
		}
		SensorManager.remapCoordinateSystem(inR, axisX, axisY, outR);
	}

	private void handleRotationVector(@NonNull SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
			// Copy up to 5 values for completeness
			int len = Math.min(event.values.length, rotationVector.length);
			smoothSensorData(event.values, rotationVector, len);

			// Directly get the 4x4 rotation matrix
			SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);

			// In this mode, there is no inclination matrix, so we keep it as identity
			Matrix.setIdentityM(inclinationMatrix, 0);
		}
	}

	// Simple low-pass filter for smoothing sensor data
	private void smoothSensorData(float[] newValues, float[] oldValues, int length) {
		for (int i = 0; i < length; i++) {
			oldValues[i] = oldValues[i] + SMOOTHING_FACTOR * (newValues[i] - oldValues[i]);
		}

	}

	// Converts a 3x3 row-major matrix to a 4x4 column-major matrix for OpenGL
	private void to4x4Matrix(@NonNull float[] matrix3x3, @NonNull float[] matrix4x4) {
		matrix4x4[0] = matrix3x3[0];
		matrix4x4[1] = matrix3x3[3];
		matrix4x4[2] = matrix3x3[6];
		matrix4x4[3] = 0f;
		matrix4x4[4] = matrix3x3[1];
		matrix4x4[5] = matrix3x3[4];
		matrix4x4[6] = matrix3x3[7];
		matrix4x4[7] = 0f;
		matrix4x4[8] = matrix3x3[2];
		matrix4x4[9] = matrix3x3[5];
		matrix4x4[10] = matrix3x3[8];
		matrix4x4[11] = 0f;
		matrix4x4[12] = 0f;
		matrix4x4[13] = 0f;
		matrix4x4[14] = 0f;
		matrix4x4[15] = 1f;
	}
}