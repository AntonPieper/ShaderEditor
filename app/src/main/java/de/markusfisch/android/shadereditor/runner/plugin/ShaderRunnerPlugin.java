package de.markusfisch.android.shadereditor.runner.plugin;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

	// === State for Ping-Pong Buffering ===
	private boolean backbufferIsActive = false;
	private Framebuffer fboA, fboB;
	private Image2D.RenderTarget textureA, textureB;

	// Pointers to the current source (for reading) and destination (for writing)
	private Framebuffer sourceFbo, destFbo;
	private Image2D sourceTexture;

	@Override
	public void onSetup(@NonNull Engine engine) {
		var shader = engine.loadAsset(AssetRef.uri("./main.frag"), ShaderAsset.class);
		this.shaderMaterial = new Material(shader);
		this.shaderMetadata = engine.getShaderIntrospector().introspect(shader);
		this.binder = new UniformBinder(engine.getDefaultBindings());

		var activeUniforms = shaderMetadata.getActiveUniformNames();

		// Check if the shader actually uses a backbuffer uniform.
		this.backbufferIsActive = activeUniforms.contains("backbuffer");

		// Load all other texture uniforms defined in shader comments.
		var samplers = SamplerCommentParser.parse(
				shader.fragmentSource(),
				activeUniforms.stream()
						.filter(v -> !v.equals("backbuffer"))
						.collect(Collectors.toSet()));
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

		// Create the fullscreen quad geometry once.
		this.screenQuad = Geometry.fullscreenQuad();
	}

	@Override
	public void onRender(@NonNull Engine engine) {
		// 1. Get data from engine
		Viewport physicalViewport = engine.getData(EngineDataKeys.PHYSICAL_VIEWPORT_RESOLUTION);
		Viewport renderTargetViewport = engine.getData(EngineDataKeys.RENDER_TARGET_RESOLUTION);

		if (physicalViewport == null || renderTargetViewport == null ||
				renderTargetViewport.width() <= 0 || renderTargetViewport.height() <= 0) {
			return; // Not ready yet, skip this frame.
		}

		// Update standard uniforms (time, resolution, etc.) for the main shader pass
		binder.apply(engine, shaderMaterial, shaderMetadata.getActiveUniformNames());

		if (backbufferIsActive) {
			renderWithBackbuffer(engine, renderTargetViewport);
		} else {
			renderSimple(engine, physicalViewport, renderTargetViewport);
		}
	}

	@Override
	public void onTeardown(@NonNull Engine engine) {
		Log.d("ShaderRunnerPlugin", "Teardown");
		// Nullify resources to allow garbage collection
		fboA = fboB = sourceFbo = destFbo = null;
		textureA = textureB = null;
		sourceTexture = null;
	}

	private void renderWithBackbuffer(
			@NonNull Engine engine,
			@NonNull Viewport renderTargetViewport) {
		// Lazily create or recreate FBOs if the viewport size has changed
		if (textureA == null || textureA.width() != renderTargetViewport.width() ||
				textureA.height() != renderTargetViewport.height()) {
			Log.d("ShaderRunnerPlugin", "Creating ping-pong buffers for size: " +
					renderTargetViewport.width() + "x" + renderTargetViewport.height());
			recreatePingPongBuffers(renderTargetViewport);
		}

		// 1. Set the backbuffer uniform to the texture from the *previous* frame
		shaderMaterial.setUniform("backbuffer", new Uniform.Sampler2D(sourceTexture));

		// 2. Define the main pass to render from the source to the destination
		var mainPass = new Pass(
				destFbo,
				null, // No clear
				new ViewportRect(0, 0, renderTargetViewport.width(),
						renderTargetViewport.height()),
				List.of(new DrawCall(screenQuad, shaderMaterial, Primitive.TRIANGLE_STRIP))
		);

		// 3. Compile the main pass and add a final command to blit the result to the screen
		var commands = new ArrayList<>(PassCompiler.compile(mainPass).cmds());
		commands.add(new GpuCommand.Blit(
				destFbo.colorAttachments().get(0).image(),
				Framebuffer.defaultFramebuffer()
		));
		engine.submitCommands(new CommandBuffer(commands));

		// 4. SWAP the buffers for the next frame
		Framebuffer tempFbo = destFbo;
		destFbo = sourceFbo;
		sourceFbo = tempFbo;
		sourceTexture = sourceFbo.colorAttachments().get(0).image();
	}

	private void renderSimple(
			@NonNull Engine engine,
			@NonNull Viewport physicalViewport,
			@NonNull Viewport renderTargetViewport) {
		// This is the original logic for when no backbuffer is needed.
		boolean useQualityScaling = renderTargetViewport.width() != physicalViewport.width() ||
				renderTargetViewport.height() != physicalViewport.height();

		if (useQualityScaling) {
			// --- Quality scaling path ---
			var offscreenTexture = new Image2D.RenderTarget(
					"offscreen", // A consistent name for caching
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
			var mainPass = new Pass(
					offscreenFbo,
					null, // No clear
					new ViewportRect(0, 0, renderTargetViewport.width(),
							renderTargetViewport.height()),
					List.of(new DrawCall(screenQuad, shaderMaterial, Primitive.TRIANGLE_STRIP))
			);

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

	private void recreatePingPongBuffers(@NonNull Viewport viewport) {
		textureA = new Image2D.RenderTarget(
				"ping", // Unique name
				viewport.width(),
				viewport.height(),
				TextureInternalFormat.RGBA8,
				TextureParameters.DEFAULT
		);
		textureB = new Image2D.RenderTarget(
				"pong", // Unique name
				viewport.width(),
				viewport.height(),
				TextureInternalFormat.RGBA8,
				TextureParameters.DEFAULT
		);

		fboA = new Framebuffer(viewport.width(), viewport.height(),
				List.of(new ColorAttachment(textureA)));
		fboB = new Framebuffer(viewport.width(), viewport.height(),
				List.of(new ColorAttachment(textureB)));

		// Set initial state
		destFbo = fboA;
		sourceFbo = fboB;
		sourceTexture = textureB;
	}
}