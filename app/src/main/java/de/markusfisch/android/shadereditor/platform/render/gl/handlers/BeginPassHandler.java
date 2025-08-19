package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.engine.scene.RenderTarget;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesBinder;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesFramebufferManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesSwapchainManager;

public final class BeginPassHandler implements GlesCommandHandler<GpuCommand.BeginPass> {
	@NonNull
	private final GlesFramebufferManager framebuffers;
	@NonNull
	private final GlesSwapchainManager swapchains;
	@NonNull
	private final GlesBinder binder;

	public BeginPassHandler(
			@NonNull GlesFramebufferManager framebuffers,
			@NonNull GlesSwapchainManager swapchains,
			@NonNull GlesBinder binder) {
		this.framebuffers = framebuffers;
		this.swapchains = swapchains;
		this.binder = binder;
	}

	@NonNull
	@Override
	public Class<GpuCommand.BeginPass> type() {
		return GpuCommand.BeginPass.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.BeginPass cmd, @NonNull GlesRenderContext ctx) {
		int fbo;
		Viewport viewport = ctx.getViewport();

		RenderTarget target = cmd.target();
		fbo = switch (target) {
			case RenderTarget.ToScreen ignored -> 0;
			case RenderTarget.ToImage(var image) -> framebuffers.getFramebufferHandle(image);
			case RenderTarget.ToSwapchain(var swapchain) ->
					swapchains.getWriteFramebufferHandle(swapchain, viewport);
		};

		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fbo);

		// Set the viewport for the pass
		if (cmd.viewport() != null) {
			var v = cmd.viewport();
			GLES32.glViewport(v.x(), v.y(), v.width(), v.height());
		} else if (target instanceof RenderTarget.ToImage(var image)) {
			// Default for offscreen is to match the image size
			GLES32.glViewport(0, 0, image.width(), image.height());
		} else {
			// Default for screen or swapchain is to match the render target viewport size
			GLES32.glViewport(0, 0, viewport.width(), viewport.height());
		}


		if (cmd.clearColor() != null) {
			var c = cmd.clearColor();
			GLES32.glClearColor(c.r(), c.g(), c.b(), c.a());
			GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT);
		}
		binder.resetTextureUnits();
	}
}