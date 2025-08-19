package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.RenderTarget;
import de.markusfisch.android.shadereditor.engine.scene.TextureSource;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesBinder;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;
import de.markusfisch.android.shadereditor.platform.render.gl.core.ShaderCache;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesFramebufferManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesGeometryManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesSwapchainManager;

public final class BlitHandler implements GlesCommandHandler<GpuCommand.Blit> {
	private static final String TAG = "BlitHandler";
	private static final ShaderAsset BLIT_SHADER = new ShaderAsset(
			"""
					attribute vec4 a_Position;
					attribute vec2 a_TexCoord;
					varying vec2 v_TexCoord;
					void main(){
						gl_Position=a_Position;
						v_TexCoord=a_TexCoord;
					}
					""",
			"""
					precision mediump float;
					varying vec2 v_TexCoord;
					uniform sampler2D uTex;
					void main(){
						gl_FragColor=texture2D(uTex,v_TexCoord);
					}
					"""
	);
	@NonNull
	private final ShaderCache shaders;
	@NonNull
	private final GlesGeometryManager geometries;
	@NonNull
	private final GlesFramebufferManager framebuffers;
	@NonNull
	private final GlesSwapchainManager swapchains;
	@NonNull
	private final GlesBinder binder;
	private final Geometry fsq = Geometry.fullscreenQuad();

	public BlitHandler(
			@NonNull ShaderCache shaders,
			@NonNull GlesGeometryManager geometries,
			@NonNull GlesFramebufferManager framebuffers,
			@NonNull GlesSwapchainManager swapchains,
			@NonNull GlesBinder binder) {
		this.shaders = shaders;
		this.geometries = geometries;
		this.framebuffers = framebuffers;
		this.swapchains = swapchains;
		this.binder = binder;
	}

	@NonNull
	@Override
	public Class<GpuCommand.Blit> type() {
		return GpuCommand.Blit.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.Blit cmd, @NonNull GlesRenderContext ctx) {
		Viewport viewport = ctx.getViewport();

		var dst = cmd.dst();
		int fboHandle = switch (dst) {
			case RenderTarget.ToScreen ignored -> {
				GLES32.glViewport(0, 0, viewport.width(), viewport.height());
				yield 0;
			}
			case RenderTarget.ToImage(var image) -> {
				GLES32.glViewport(0, 0, image.width(), image.height());
				yield framebuffers.getFramebufferHandle(image);
			}
			case RenderTarget.ToSwapchain(var swapchain) -> {
				GLES32.glViewport(0, 0, viewport.width(), viewport.height());
				yield swapchains.getWriteFramebufferHandle(swapchain, viewport);
			}
		};

		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboHandle);

		var blitProgram = shaders.get(BLIT_SHADER);
		GLES32.glUseProgram(blitProgram.programId());
		ctx.setCurrentProgram(blitProgram);

		binder.resetTextureUnits();

		int uTexLocation = blitProgram.locate("uTex");
		var source = cmd.src();

		if (source instanceof TextureSource.FromSwapchain(var swapchain)) {
			// When blitting from a swapchain, we want to blit the content that was
			// just written, not the content from the previous frame.
			// So we must get the "write" texture handle instead of the "read" one.
			int writeTexture = swapchains.getWriteTextureHandle(
					swapchain,
					viewport);
			binder.bindTexture(uTexLocation, writeTexture);
		} else {
			// For other sources, use the standard binder logic.
			binder.bind(uTexLocation, new Uniform.Sampler2D(source), ctx.getViewport());
		}


		int vao = geometries.getGeometryHandle(fsq);
		GLES32.glBindVertexArray(vao);
		GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, fsq.vertexCount());
	}
}