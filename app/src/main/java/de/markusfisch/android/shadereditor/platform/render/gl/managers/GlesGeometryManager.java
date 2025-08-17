package de.markusfisch.android.shadereditor.platform.render.gl.managers;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.scene.Geometry;

public class GlesGeometryManager implements AutoCloseable {
	private final Map<Geometry, Integer> vaoCache = new HashMap<>();

	public int getGeometryHandle(@NonNull Geometry geometry) {
		return vaoCache.computeIfAbsent(geometry, this::createVao);
	}

	public void close() {
		// Delete all VAOs
		var vaos = vaoCache.values().stream().mapToInt(Integer::intValue).toArray();
		GLES30.glDeleteVertexArrays(vaos.length, vaos, 0);
		vaoCache.clear();
	}

	private int createVao(@NonNull Geometry geometry) {
		FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(geometry.vertices().length * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		vertexBuffer.put(geometry.vertices()).position(0);

		final int[] vbo = new int[1];
		GLES30.glGenBuffers(1, vbo, 0);

		final int[] vaoHandle = new int[1];
		GLES30.glGenVertexArrays(1, vaoHandle, 0);

		GLES30.glBindVertexArray(vaoHandle[0]);
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0]);
		GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, geometry.vertices().length * 4, vertexBuffer,
				GLES30.GL_STATIC_DRAW);

		// Position attribute (x, y)
		GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0);
		GLES30.glEnableVertexAttribArray(0);

		// UV attribute (u, v)
		GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4);
		GLES30.glEnableVertexAttribArray(1);

		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
		GLES30.glBindVertexArray(0);

		return vaoHandle[0];
	}
}