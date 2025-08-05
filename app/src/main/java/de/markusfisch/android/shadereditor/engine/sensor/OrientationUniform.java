package de.markusfisch.android.shadereditor.engine.sensor;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.opengl.GLES20;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.engine.UniformBag;
import de.markusfisch.android.shadereditor.engine.UniformPlugin;
import de.markusfisch.android.shadereditor.engine.uniform.ArrayBinders;

public class OrientationUniform implements UniformPlugin {

	@NonNull
	@Override
	public Set<Integer> getRequiredSensors() {
		return Set.of(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_ACCELEROMETER);
	}

	@NonNull
	@Override
	public UniformBag update(@NonNull Map<Integer, float[]> sensorData) {
		boolean haveInclination = false;
		var gravityValues = sensorData.get(Sensor.TYPE_GRAVITY);
		var magneticFieldValues = sensorData.get(Sensor.TYPE_MAGNETIC_FIELD);
		var rotationVectorListener = sensorData.get(Sensor.TYPE_ROTATION_VECTOR);
		var gravityListener = sensorData.get(Sensor.TYPE_ACCELEROMETER);
		var magneticFieldListener = sensorData.get(Sensor.TYPE_MAGNETIC_FIELD);
		float[] rotationMatrix = new float[3*3];
		float[] inclinationMatrix = new float[3*3];
		if (gravityListener != null && magneticFieldListener != null &&
				SensorManager.getRotationMatrix(
						rotationMatrix,
						inclinationMatrix,
						gravityValues,
						magneticFieldListener.filtered)) {
			haveInclination = true;
		} else if (rotationVectorListener != null) {
			SensorManager.getRotationMatrixFromVector(
					rotationMatrix,
					rotationVectorListener.values);
		} else {
			return;
		}
		if (deviceRotation != Surface.ROTATION_0) {
			// Suppress the warning for this block, because the logic is intentional.
			record AxisPair(int x, int y) {
			}
			@SuppressWarnings("SuspiciousNameCombination")
			var rotation = switch (deviceRotation) {
				case Surface.ROTATION_90 ->
						new AxisPair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X);
				case Surface.ROTATION_180 ->
						new AxisPair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y);
				case Surface.ROTATION_270 ->
						new AxisPair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X);
				default -> new AxisPair(SensorManager.AXIS_X, SensorManager.AXIS_Y);
			};
			SensorManager.remapCoordinateSystem(
					rotationMatrix,
					rotation.x(),
					rotation.y(),
					rotationMatrix);
		}
		return new UniformBag().put("u_quaternion", quaternion, ArrayBinders.F4);
	}
}
