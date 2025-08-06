package de.markusfisch.android.shadereditor.engine.model;

import android.opengl.GLES32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class Geometry {
	private final int vao;
	private final int vertexCount;
	private final FloatBuffer vertexBuffer;

	// A fullscreen quad
	private static final float[] QUAD_VERTICES = {
			// X,    Y,   U,   V
			-1.0f, 1.0f, 0.0f, 1.0f, // Top-left
			-1.0f, -1.0f, 0.0f, 0.0f, // Bottom-left
			1.0f, 1.0f, 1.0f, 1.0f, // Top-right
			1.0f, -1.0f, 1.0f, 0.0f  // Bottom-right
	};

	public Geometry() {
		// Allocate a direct buffer to hold the vertex data on the native heap
		vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		vertexBuffer.put(QUAD_VERTICES).position(0);

		// --- GPU Setup ---
		final int[] vbo = new int[1];
		GLES32.glGenBuffers(1, vbo, 0);

		final int[] vaoHandle = new int[1];
		GLES32.glGenVertexArrays(1, vaoHandle, 0);
		this.vao = vaoHandle[0];

		GLES32.glBindVertexArray(this.vao);
		GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vbo[0]);
		GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, QUAD_VERTICES.length * 4, vertexBuffer,
				GLES32.GL_STATIC_DRAW);

		// Position attribute (x, y)
		GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 4 * 4, 0);
		GLES32.glEnableVertexAttribArray(0);

		// UV attribute (u, v)
		GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 4 * 4, 2 * 4);
		GLES32.glEnableVertexAttribArray(1);

		GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0);
		GLES32.glBindVertexArray(0);

		this.vertexCount = QUAD_VERTICES.length / 4;
	}

	public int getVao() {
		return vao;
	}

	public int getVertexCount() {
		return vertexCount;
	}
}