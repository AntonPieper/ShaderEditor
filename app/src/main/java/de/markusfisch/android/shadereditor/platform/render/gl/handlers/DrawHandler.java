package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import de.markusfisch.android.shadereditor.engine.graphics.Primitive;
import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;

public final class DrawHandler implements GlesCommandHandler<GpuCommand.Draw> {
	@NonNull
	@Override
	public Class<GpuCommand.Draw> type() {
		return GpuCommand.Draw.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.Draw cmd, @NonNull GlesRenderContext ctx) {
		GLES32.glDrawArrays(toGL(cmd.primitive()), cmd.first(), cmd.vertexCount());
	}

	@Contract(pure = true)
	private static int toGL(@NonNull Primitive p) {
		return switch (p) {
			case TRIANGLES -> GLES32.GL_TRIANGLES;
			case TRIANGLE_STRIP -> GLES32.GL_TRIANGLE_STRIP;
			case TRIANGLE_FAN -> GLES32.GL_TRIANGLE_FAN;
			case LINES -> GLES32.GL_LINES;
			case LINE_STRIP -> GLES32.GL_LINE_STRIP;
			case POINTS -> GLES32.GL_POINTS;
		};
	}
}