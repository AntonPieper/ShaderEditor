package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.util.Log;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesBinder;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;

public final class SetUniformsHandler implements GlesCommandHandler<GpuCommand.SetUniforms> {
	private static final String TAG = "SetUniformsHandler";
	@NonNull
	private final GlesBinder binder;

	public SetUniformsHandler(@NonNull GlesBinder binder) {
		this.binder = binder;
	}

	@NonNull
	@Override
	public Class<GpuCommand.SetUniforms> type() {
		return GpuCommand.SetUniforms.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.SetUniforms cmd, @NonNull GlesRenderContext ctx) {
		var program = ctx.currentProgram();
		if (program == null) {
			Log.w(TAG, "SetUniforms skipped: no program bound yet.");
			return;
		}
		for (var e : cmd.uniforms().entrySet()) {
			int loc = program.locate(e.getKey());
			if (loc >= 0) binder.bind(loc, e.getValue());
		}
	}
}