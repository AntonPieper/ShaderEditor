package de.markusfisch.android.shadereditor.opengl;

import java.nio.ByteBuffer;

// Two triangles that cover the viewport; GlDevice.draw() draws them.
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

	final ByteBuffer vertices = ByteBuffer.allocateDirect(8);

	FullScreenQuad() {
		vertices.put(new byte[]{
				-1, 1,
				-1, -1,
				1, 1,
				1, -1
		}).position(0);
	}
}
