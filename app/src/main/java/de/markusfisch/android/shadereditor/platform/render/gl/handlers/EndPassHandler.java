package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;

public final class EndPassHandler implements GlesCommandHandler<GpuCommand.EndPass> {
	@NonNull
	@Override
	public Class<GpuCommand.EndPass> type() {
		return GpuCommand.EndPass.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.EndPass cmd, @NonNull GlesRenderContext ctx) {
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0);
	}
}