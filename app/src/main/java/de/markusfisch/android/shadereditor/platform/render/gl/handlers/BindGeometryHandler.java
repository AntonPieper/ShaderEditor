package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesGeometryManager;

public final class BindGeometryHandler implements GlesCommandHandler<GpuCommand.BindGeometry> {
	@NonNull
	private final GlesGeometryManager geometries;

	public BindGeometryHandler(@NonNull GlesGeometryManager geometries) {
		this.geometries = geometries;
	}

	@NonNull
	@Override
	public Class<GpuCommand.BindGeometry> type() {
		return GpuCommand.BindGeometry.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.BindGeometry cmd, @NonNull GlesRenderContext ctx) {
		int vao = geometries.getGeometryHandle(cmd.geometry());
		GLES32.glBindVertexArray(vao);
	}
}