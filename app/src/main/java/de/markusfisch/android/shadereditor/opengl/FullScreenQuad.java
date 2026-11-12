package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import java.nio.ByteBuffer;

final class FullScreenQuad {
	static final String POSITION = "position";
	// GlDevice binds POSITION to this location in every program it links.
	static final int POSITION_LOCATION = 0;
	static final String VERTEX_SHADER = """
			attribute vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	static final String VERTEX_SHADER_3 = """
			in vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";

	private final ByteBuffer vertices = ByteBuffer.allocateDirect(8);

	FullScreenQuad() {
		vertices.put(new byte[]{
				-1, 1,
				-1, -1,
				1, 1,
				1, -1
		}).position(0);
	}

	// Covers the viewport with the program in use.
	void draw() {
		GLES20.glEnableVertexAttribArray(POSITION_LOCATION);
		GLES20.glVertexAttribPointer(
				POSITION_LOCATION,
				2,
				GLES20.GL_BYTE,
				false,
				0,
				vertices);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
	}
}
