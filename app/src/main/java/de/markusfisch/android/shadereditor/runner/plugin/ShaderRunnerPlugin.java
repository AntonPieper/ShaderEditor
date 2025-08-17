package de.markusfisch.android.shadereditor.runner.plugin;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.asset.AssetRef;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;
import de.markusfisch.android.shadereditor.engine.data.EngineDataKeys;
import de.markusfisch.android.shadereditor.engine.graphics.Primitive;
import de.markusfisch.android.shadereditor.engine.graphics.SamplerCommentParser;
import de.markusfisch.android.shadereditor.engine.graphics.TextureInternalFormat;
import de.markusfisch.android.shadereditor.engine.pipeline.CommandBuffer;
import de.markusfisch.android.shadereditor.engine.pipeline.DrawCall;
import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.engine.pipeline.Pass;
import de.markusfisch.android.shadereditor.engine.pipeline.PassCompiler;
import de.markusfisch.android.shadereditor.engine.pipeline.ViewportRect;
import de.markusfisch.android.shadereditor.engine.scene.ColorAttachment;
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
		binder = new UniformBinder(engine.getDefaultBindings());
		var samplers = SamplerCommentParser.parse(
				shader.fragmentSource(),
				shaderMetadata.getActiveUniformNames());
		for (var entry : samplers.entrySet()) {
			var binding = entry.getValue();
			var tex = engine.loadAsset(binding.assetIdentifier(), TextureAsset.class);
			shaderMaterial.setUniform(
					entry.getKey(), new Uniform.Sampler2D(new Image2D.FromAsset(tex,
							binding.parameters().sRGB()
									? TextureInternalFormat.SRGB8_ALPHA8
									: TextureInternalFormat.RGBA8,
							binding.parameters())));
		}

		// Create all necessary resources once
		this.screenQuad = Geometry.fullscreenQuad();
	}

	@Override
	public void onRender(@NonNull Engine engine) {
		// 1. Get data from engine
		Viewport physicalViewport = engine.getData(EngineDataKeys.PHYSICAL_VIEWPORT_RESOLUTION);
		Viewport renderTargetViewport = engine.getData(EngineDataKeys.RENDER_TARGET_RESOLUTION);

		// Ensure we have all data needed to proceed
		if (physicalViewport == null || renderTargetViewport == null ||
				physicalViewport.width() <= 0 || physicalViewport.height() <= 0) {
			return; // Not ready yet, skip this frame.
		}

		// Update uniforms for the main shader pass
		binder.apply(engine, shaderMaterial, shaderMetadata.getActiveUniformNames());

		// If render target is smaller than physical, use an offscreen buffer.
		if (renderTargetViewport.width() != physicalViewport.width() ||
				renderTargetViewport.height() != physicalViewport.height()) {
			// --- Quality scaling path ---
			// Recreate FBO if resolution has changed

			var offscreenTexture = new Image2D.RenderTarget(
					renderTargetViewport.width(),
					renderTargetViewport.height(),
					TextureInternalFormat.RGBA8,
					TextureParameters.DEFAULT
			);
			var offscreenFbo = new Framebuffer(
					renderTargetViewport.width(),
					renderTargetViewport.height(),
					List.of(new ColorAttachment(offscreenTexture))
			);

			// Define the render-to-texture pass
			var mainPass = new Pass(
					offscreenFbo,
					null, // No clear, just draw over
					new ViewportRect(0, 0, renderTargetViewport.width(),
							renderTargetViewport.height()),
					List.of(new DrawCall(screenQuad, shaderMaterial, Primitive.TRIANGLE_STRIP))
			);

			// Compile the main pass and add the blit command
			var mainCommands = PassCompiler.compile(mainPass);
			var allCommands = new ArrayList<>(mainCommands.cmds());
			allCommands.add(new GpuCommand.Blit(offscreenTexture,
					Framebuffer.defaultFramebuffer()));

			engine.submitCommands(new CommandBuffer(allCommands));
		} else {
			// --- Full quality fast path ---
			var commands = PassCompiler.compile(Pass.single(Framebuffer.defaultFramebuffer(),
					new DrawCall(screenQuad, shaderMaterial, Primitive.TRIANGLE_STRIP)));
			engine.submitCommands(commands);
		}
	}

	@Override
	public void onTeardown(@NonNull Engine engine) {
		Log.d("ShaderRunnerPlugin", "Teardown");
	}
}