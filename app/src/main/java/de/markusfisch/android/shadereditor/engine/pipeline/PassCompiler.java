package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public final class PassCompiler {
	private PassCompiler() {
		throw new UnsupportedOperationException();
	}

	@NonNull
	public static CommandBuffer compile(@NonNull List<Pass> passes) {
		var out = new ArrayList<GpuCommand>(passes.size() * 4);
		for (var pass : passes) {
			out.add(new GpuCommand.BeginPass(pass.target(), pass.clearColor(), pass.viewport()));
			for (var draw : pass.draws()) {
				out.add(new GpuCommand.BindProgram(draw.material()));
				out.add(new GpuCommand.SetUniforms(draw.material()));
				out.add(new GpuCommand.BindGeometry(draw.geometry()));
				out.add(new GpuCommand.Draw(draw.geometry().vertexCount(), 0, draw.primitive()));
			}
			out.add(new GpuCommand.EndPass());
		}
		return new CommandBuffer(List.copyOf(out));
	}

	@NonNull
	public static CommandBuffer compile(@NonNull Pass... passes) {
		return compile(List.of(passes));
	}
}