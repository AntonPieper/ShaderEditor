package de.markusfisch.android.shadereditor.runner.plugin;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.asset.AssetRef;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;
import de.markusfisch.android.shadereditor.engine.data.BackbufferDataKeys;
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
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Image2D;
import de.markusfisch.android.shadereditor.engine.scene.Material;
import de.markusfisch.android.shadereditor.engine.scene.RenderTarget;
import de.markusfisch.android.shadereditor.engine.scene.TextureSource;
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

		// The binder is configured with all default platform bindings, which now
		// includes the backbuffer.
		this.binder = new UniformBinder(engine.getDefaultBindings());

		var activeUniforms = shaderMetadata.getActiveUniformNames();

		// The SamplerCommentParser will load all texture uniforms *except* for "backbuffer",
		// which is now a reserved, platform-provided uniform.
		var samplers = SamplerCommentParser.parse(
				shader.fragmentSource(),
				activeUniforms.stream()
						.filter(v -> !v.equals("backbuffer"))
						.collect(Collectors.toSet()));

		for (var entry : samplers.entrySet()) {
			var binding = entry.getValue();
			var texAsset = engine.loadAsset(binding.assetIdentifier(), TextureAsset.class);
			var image = new Image2D.FromAsset(
					texAsset,
					binding.parameters().sRGB()
							? TextureInternalFormat.SRGB8_ALPHA8
							: TextureInternalFormat.RGBA8,
					binding.parameters());
			shaderMaterial.setUniform(
					entry.getKey(),
					new Uniform.Sampler2D(new TextureSource.FromImage(image))
			);
		}

		this.screenQuad = Geometry.fullscreenQuad();
	}

	@Override
	public void onRender(@NonNull Engine engine) {
		Viewport renderTargetViewport = engine.getData(EngineDataKeys.RENDER_TARGET_RESOLUTION);
		if (renderTargetViewport == null || renderTargetViewport.width() <= 0 ||
				renderTargetViewport.height() <= 0) {
			return; // Not ready yet, skip this frame.
		}

		final Set<String> activeUniforms = shaderMetadata.getActiveUniformNames();

		// This will automatically bind time, resolution, touch, sensors, and now
		// also the backbuffer texture if the "backbuffer" uniform is active in the shader.
		binder.apply(engine, shaderMaterial, activeUniforms);

		final RenderTarget offscreenTarget;
		final TextureSource blitSource;
		final boolean wantsBackbuffer = activeUniforms.contains("backbuffer");

		if (wantsBackbuffer) {
			// If the shader wants a backbuffer, we request the platform-provided
			// backbuffer render target. The binder has already set the input texture.
			offscreenTarget = engine.getData(BackbufferDataKeys.BACKBUFFER_TARGET);

			// The source for our final blit must be the same swapchain. We can
			// derive the TextureSource from the RenderTarget.
			if (offscreenTarget instanceof RenderTarget.ToSwapchain(var swapchain)) {
				blitSource = new TextureSource.FromSwapchain(swapchain);
			} else {
				// This case should not be reached if the BackbufferPlugin is correctly
				// registered. It indicates a configuration error.
				return;
			}
		} else {
			// If no backbuffer is needed, we render to a transient offscreen texture
			// that matches the quality-scaled viewport.
			var transientImage = new Image2D.RenderTarget(
					"offscreen", // A consistent name for caching
					renderTargetViewport.width(),
					renderTargetViewport.height(),
					TextureInternalFormat.RGBA8,
					TextureParameters.DEFAULT
			);
			offscreenTarget = new RenderTarget.ToImage(transientImage);
			blitSource = new TextureSource.FromImage(transientImage);
		}

		// --- Unified Rendering Path ---
		// This logic is now identical for all rendering scenarios.
		// 1. Define the main draw call.
		final DrawCall mainDrawCall = new DrawCall(
				screenQuad,
				shaderMaterial,
				Primitive.TRIANGLE_STRIP);

		// 2. Define the main rendering pass to the chosen offscreen target.
		final ViewportRect renderViewportRect = new ViewportRect(
				0, 0,
				renderTargetViewport.width(), renderTargetViewport.height());
		final Pass mainPass = new Pass(
				offscreenTarget,
				null,
				renderViewportRect,
				List.of(mainDrawCall));

		// 3. Compile the pass and add a final blit command to present the result.
		var commands = new ArrayList<>(PassCompiler.compile(mainPass).cmds());
		commands.add(new GpuCommand.Blit(blitSource, new RenderTarget.ToScreen()));

		// 4. Submit the complete command buffer for this frame.
		engine.submitCommands(new CommandBuffer(commands));
	}

	@Override
	public void onTeardown(@NonNull Engine engine) {
		// All GPU resources are managed by the renderer, so there's nothing to clean up here.
		// We can nullify our references to allow for garbage collection.
		this.shaderMaterial = null;
		this.shaderMetadata = null;
		this.screenQuad = null;
		this.binder = null;
	}
}