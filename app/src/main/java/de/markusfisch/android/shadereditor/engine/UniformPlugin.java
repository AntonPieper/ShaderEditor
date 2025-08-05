package de.markusfisch.android.shadereditor.engine;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.Set;

public interface UniformPlugin {
	@NonNull
	Set<Integer> getRequiredSensors();

	@NonNull
	UniformBag update(@NonNull Map<Integer, float[]> data);
}

// --- CONCRETE IMPLEMENTATIONS (NOW MORE CONCISE) ---

// OrientationUniform.java
import android.hardware.Sensor;

// LightSensorUniform.java
import android.hardware.Sensor;

public class LightSensorUniform extends UniformPlugin {
	public LightSensorUniform(String uniformName) {
		super(uniformName);
	}

	@Override
	public Set<Integer> getRequiredSensors() {
		return Set.of(Sensor.TYPE_LIGHT);
	}

	@Override
	public void update(int shaderProgramId, @NonNull Map<Integer, float[]> sensorData) {
		float defaultLight = 0.5f;
		float lightLevel =
				sensorData.getOrDefault(Sensor.TYPE_LIGHT, new float[]{defaultLight})[0];
		GLES20.glUniform1f(getUniformLocation(shaderProgramId), lightLevel);
	}
}