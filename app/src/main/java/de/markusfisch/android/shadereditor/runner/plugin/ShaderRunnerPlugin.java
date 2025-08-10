package de.markusfisch.android.shadereditor.runner.plugin;

import android.util.Log;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.data.EngineDataKeys;
import de.markusfisch.android.shadereditor.engine.data.SensorDataKeys;
import de.markusfisch.android.shadereditor.engine.data.SystemDataKeys;
import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Material;
import de.markusfisch.android.shadereditor.engine.scene.RenderPass;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;
import de.markusfisch.android.shadereditor.engine.scene.UniformBinder;

public class ShaderRunnerPlugin implements Plugin {

	private UniformBinder binder;
	private Material shaderMaterial;
	private ShaderIntrospector.ShaderMetadata shaderMetadata;
	private Geometry screenQuad;

	@Override
	public void onSetup(@NonNull Engine engine) {
		var shader = engine.loadAsset("./main.frag", ShaderAsset.class);
		this.shaderMaterial = new Material(shader);
		this.shaderMetadata = engine.getShaderIntrospector().introspect(shader);
		binder = UniformBinder.builder()
				.bind(
						"resolution", EngineDataKeys.VIEWPORT_RESOLUTION,
						v -> Uniform.floatVec2(Viewport.toVec2(v)))
				.bind(
						"time", SystemDataKeys.TIME,
						Uniform::floatScalar)
				.bind(
						"nightMode", SystemDataKeys.IS_NIGHT_MODE,
						Uniform::intScalar)
				.bind(
						"rotationMatrix", SensorDataKeys.ROTATION_MATRIX,
						Uniform::floatMat4)
				.bind(
						"gravity", SensorDataKeys.GRAVITY,
						Uniform::floatVec3)
				.bind(
						"geomagnetic", SensorDataKeys.GEOMAGNETIC,
						Uniform::floatVec3)
				.bind(
						"inclinationMatrix", SensorDataKeys.INCLINATION_MATRIX,
						Uniform::floatMat4)
				.build();

		// Create all necessary resources once
		this.screenQuad = Geometry.fullscreenQuad();
	}

	@Override
	public void onRender(@NonNull Engine engine) {
		// Each frame, update dynamic data and submit the work
		binder.apply(engine, shaderMaterial, shaderMetadata.getActiveUniformNames());
		engine.submit(new RenderPass(
				screenQuad,
				shaderMaterial,
				Framebuffer.defaultFramebuffer()));
	}

	@Override
	public void onTeardown(@NonNull Engine engine) {
		Log.d("ShaderRunnerPlugin", "Teardown");
		// Clean up resources if necessary
		// (For this simple case, GPU resources are cleaned up on context loss)
	}
}