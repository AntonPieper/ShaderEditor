package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public final class PassCompiler {
	private PassCompiler() {
		throw new UnsupportedOperationException(
				"This class cannot be instantiated");
	}

	@NonNull
	public static CommandBuffer compile(@NonNull List<Pass> passes) {
		var out = new ArrayList<GpuCommand>(passes.size() * 4);
		for (var pass : passes) {
			out.add(
					new GpuCommand.BeginPass(
							pass.target(),
							pass.clearColorOrNull(),
							pass.viewportXYWHOrNull()));
			for (var drawCall : pass.draws()) {
				out.add(new GpuCommand.BindProgram(drawCall.material()));
				out.add(new GpuCommand.SetUniforms(drawCall.material()));
				out.add(new GpuCommand.BindGeometry(drawCall.geometry()));
				out.add(new GpuCommand.Draw(drawCall.geometry().vertexCount(), 0,
						drawCall.mode()));
			}
			out.add(new GpuCommand.EndPass());
		}
		return new CommandBuffer(List.copyOf(out));
	}
}
