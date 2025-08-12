package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;
import de.markusfisch.android.shadereditor.platform.render.gl.core.ShaderCache;

public final class BindProgramHandler implements GlesCommandHandler<GpuCommand.BindProgram> {
	private final ShaderCache shaders;

	public BindProgramHandler(@NonNull ShaderCache shaders) {
		this.shaders = shaders;
	}

	@NonNull
	@Override
	public Class<GpuCommand.BindProgram> type() {
		return GpuCommand.BindProgram.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.BindProgram cmd, @NonNull GlesRenderContext ctx) {
		var program = shaders.get(cmd.material().shader());
		GLES32.glUseProgram(program.programId());
		ctx.setCurrentProgram(program);
	}
}