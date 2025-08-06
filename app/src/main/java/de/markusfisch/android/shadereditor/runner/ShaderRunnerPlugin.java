package de.markusfisch.android.shadereditor.runner;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.function.Function;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.data.DataKey;
import de.markusfisch.android.shadereditor.engine.model.Framebuffer;
import de.markusfisch.android.shadereditor.engine.model.Geometry;
import de.markusfisch.android.shadereditor.engine.model.Material;
import de.markusfisch.android.shadereditor.engine.model.RenderPass;
import de.markusfisch.android.shadereditor.engine.model.Uniform;
import de.markusfisch.android.shadereditor.runner.provider.NightModeProvider;
import de.markusfisch.android.shadereditor.runner.provider.TimeProvider;
import de.markusfisch.android.shadereditor.runner.provider.sensor.GeomagneticProvider;
import de.markusfisch.android.shadereditor.runner.provider.sensor.GravityProvider;
import de.markusfisch.android.shadereditor.runner.provider.sensor.InclinationMatrixProvider;
import de.markusfisch.android.shadereditor.runner.provider.sensor.RotationMatrixProvider;
import de.markusfisch.android.shadereditor.runner.provider.sensor.RotationVectorProvider;
import de.markusfisch.android.shadereditor.runner.provider.sensor.SensorDataKeys;

public class ShaderRunnerPlugin implements Plugin {

	// A simple default vertex shader
	private static final String DEFAULT_VERTEX_SHADER = """
			#version 320 es
			precision mediump float;
			layout (location = 0) in vec4 a_Position;
			layout (location = 1) in vec2 a_TexCoord;
			out vec2 v_TexCoord;
			void main() {
			    gl_Position = a_Position;
			    v_TexCoord = a_TexCoord;
			}
			""";

	// A simple default fragment shader that uses time and resolution
	private static final String DEFAULT_FRAGMENT_SHADER = """
			#version 320 es
			precision mediump float;
			in vec2 v_TexCoord;
			out vec4 fragColor;
			uniform vec2 u_resolution;
			uniform float u_time;
			
			void main() {
			    vec2 st = gl_FragCoord.xy/u_resolution.xy;
			    fragColor = vec4(st.x, st.y, 0.5 + 0.5 * sin(u_time), 1.0);
			}
			""";

	private Material shaderMaterial;
	private Geometry screenQuad;

	@Override
	public void onSetup(@NonNull Engine engine) {
		engine.registerDataProvider(new TimeProvider())
				.registerDataProvider(new NightModeProvider());

		// Register all sensor providers
		engine.registerDataProvider(new RotationMatrixProvider())
				.registerDataProvider(new GravityProvider())
				.registerDataProvider(new GeomagneticProvider())
				.registerDataProvider(new InclinationMatrixProvider())
				.registerDataProvider(new RotationVectorProvider());

		// Create all necessary resources once
		this.screenQuad = new Geometry();
		this.shaderMaterial = new Material(
				DEFAULT_VERTEX_SHADER,
				DEFAULT_FRAGMENT_SHADER);
	}

	@Override
	public void onRender(@NonNull Engine engine) {
		// Each frame, update dynamic data and submit the work
		var currentTime = engine.getData(TimeProvider.TIME);
		if (currentTime != null) {
			shaderMaterial.setUniform("u_time", Uniform.floatScalar(currentTime));
		}
		var nightMode = engine.getData(NightModeProvider.IS_NIGHT_MODE);
		if (nightMode != null) {
			shaderMaterial.setUniform("u_nightMode", Uniform.intScalar(nightMode ? 1 : 0));
		}

		// Update all sensor uniforms
		updateSensorUniform(engine,
				SensorDataKeys.ROTATION_MATRIX, "u_rotationMatrix", Uniform::floatMat4);
		updateSensorUniform(engine,
				SensorDataKeys.GRAVITY, "u_gravity", Uniform::floatVec3);
		updateSensorUniform(engine,
				SensorDataKeys.GEOMAGNETIC, "u_geomagnetic", Uniform::floatVec3);
		updateSensorUniform(engine,
				SensorDataKeys.INCLINATION_MATRIX, "u_inclinationMatrix", Uniform::floatMat4);

		engine.submit(new RenderPass(
				screenQuad,
				shaderMaterial,
				Framebuffer.defaultFramebuffer()));
	}

	private <T> void updateSensorUniform(@NonNull Engine engine,
			@NonNull DataKey<T> key,
			@NonNull String uniformName,
			@NonNull Function<T, Uniform> factory) {
		var data = engine.getData(key);
		if (data != null) {
			shaderMaterial.setUniform(uniformName, factory.apply(data));
		}
	}

	@Override
	public void onTeardown(@NonNull Engine engine) {
		Log.d("ShaderRunnerPlugin", "Teardown");
		// Clean up resources if necessary
		// (For this simple case, GPU resources are cleaned up on context loss)
	}
}