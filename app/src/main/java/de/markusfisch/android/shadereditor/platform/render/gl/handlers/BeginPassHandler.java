package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesBinder;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesFramebufferManager;

public final class BeginPassHandler implements GlesCommandHandler<GpuCommand.BeginPass> {
	@NonNull
	private final GlesFramebufferManager framebuffers;
	@NonNull
	private final GlesBinder binder;

	public BeginPassHandler(
			@NonNull GlesFramebufferManager framebuffers,
			@NonNull GlesBinder binder) {
		this.framebuffers = framebuffers;
		this.binder = binder;
	}

	@NonNull
	@Override
	public Class<GpuCommand.BeginPass> type() {
		return GpuCommand.BeginPass.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.BeginPass cmd, @NonNull GlesRenderContext ctx) {
		int fbo = framebuffers.getFramebufferHandle(cmd.target());
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fbo);
		if (cmd.viewport() != null) {
			var v = cmd.viewport();
			GLES32.glViewport(v.x(), v.y(), v.width(), v.height());
		}
		if (cmd.clearColor() != null) {
			var c = cmd.clearColor();
			GLES32.glClearColor(c.r(), c.g(), c.b(), c.a());
			GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT);
		}
		binder.resetTextureUnits();
	}
}