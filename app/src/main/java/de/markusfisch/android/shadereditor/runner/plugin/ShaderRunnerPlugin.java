package de.markusfisch.android.shadereditor.runner.plugin;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.asset.AssetRef;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureAsset;
import de.markusfisch.android.shadereditor.engine.data.EngineDataKeys;
import de.markusfisch.android.shadereditor.engine.data.SensorDataKeys;
import de.markusfisch.android.shadereditor.engine.data.SystemDataKeys;
import de.markusfisch.android.shadereditor.engine.graphics.SamplerCommentParser;
import de.markusfisch.android.shadereditor.engine.pipeline.DrawCall;
import de.markusfisch.android.shadereditor.engine.pipeline.Pass;
import de.markusfisch.android.shadereditor.engine.pipeline.PassCompiler;
import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Image2D;
import de.markusfisch.android.shadereditor.engine.scene.Material;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;
import de.markusfisch.android.shadereditor.engine.scene.UniformBinder;

public class ShaderRunnerPlugin implements Plugin {

	private UniformBinder binder;
	private Material shaderMaterial;
	private ShaderIntrospector.ShaderMetadata shaderMetadata;
	private Geometry screenQuad;

	@Override
	public void onSetup(@NonNull Engine engine) {
		var shader = engine.loadAsset(AssetRef.uri("./main.frag"), ShaderAsset.class);
		this.shaderMaterial = new Material(shader);
		this.shaderMetadata = engine.getShaderIntrospector().introspect(shader);
		binder = UniformBinder.builder()
				.bind(
						"resolution", EngineDataKeys.VIEWPORT_RESOLUTION,
						v -> new Uniform.FloatVec2(Viewport.toVec2(v)))
				.bind(
						"time", SystemDataKeys.TIME,
						v -> new Uniform.FloatScalar(new float[]{v}))
				.bind(
						"nightMode", SystemDataKeys.IS_NIGHT_MODE,
						v -> new Uniform.IntScalar(new int[]{v ? 1 : 0}))
				.bind(
						"rotationMatrix", SensorDataKeys.ROTATION_MATRIX,
						Uniform.FloatMat4::new)
				.bind(
						"gravity", SensorDataKeys.GRAVITY,
						Uniform.FloatVec3::new)
				.bind(
						"geomagnetic", SensorDataKeys.GEOMAGNETIC,
						Uniform.FloatVec3::new)
				.bind(
						"inclinationMatrix", SensorDataKeys.INCLINATION_MATRIX,
						Uniform.FloatMat4::new)
				.build();
		var samplers = SamplerCommentParser.parse(
				shader.fragmentSource(),
				shaderMetadata.getActiveUniformNames());
		for (var entry : samplers.entrySet()) {
			var binding = entry.getValue();
			var tex = engine.loadAsset(binding.assetIdentifier(), TextureAsset.class);
			shaderMaterial.setUniform(
					entry.getKey(), new Uniform.Sampler2D(new Image2D.FromAsset(tex,
							GLES30.GL_RGBA8,
							binding.parameters())));
		}

		// Create all necessary resources once
		this.screenQuad = Geometry.fullscreenQuad();
	}

	@Override
	public void onRender(@NonNull Engine engine) {
		// Each frame, update dynamic data and submit the work
		binder.apply(engine, shaderMaterial, shaderMetadata.getActiveUniformNames());
		var commands = PassCompiler.compile(List.of(new Pass(
				Framebuffer.defaultFramebuffer(),
				null,
				null,
				List.of(new DrawCall(screenQuad, shaderMaterial, GLES20.GL_TRIANGLE_STRIP))
		)));
		engine.submitCommands(commands);
	}

	@Override
	public void onTeardown(@NonNull Engine engine) {
		Log.d("ShaderRunnerPlugin", "Teardown");
		// Clean up resources if necessary
		// (For this simple case, GPU resources are cleaned up on context loss)
	}
}