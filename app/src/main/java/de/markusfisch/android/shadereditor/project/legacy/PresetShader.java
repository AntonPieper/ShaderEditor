package de.markusfisch.android.shadereditor.project.legacy;

/**
 * The shader that reproduces an old backbuffer preset as the initial
 * content of a pass: the image is scaled to the width of the target and
 * repeated downwards, every other copy mirrored, from the top edge.
 */
final class PresetShader {
	private PresetShader() {
	}

	/**
	 * Return the shader text for the image at a path relative to the
	 * shader, of the given size in pixels.
	 */
	static String text(String image, int width, int height) {
		String w = constant(width);
		String h = constant(height);
		return String.join("\n",
				"#ifdef GL_FRAGMENT_PRECISION_HIGH",
				"precision highp float;",
				"#else",
				"precision mediump float;",
				"#endif",
				"",
				"#pragma se input \"" + image + "\"",
				"uniform sampler2D image;",
				"uniform vec2 resolution;",
				"",
				"// The image is " + width + "x" + height + " pixels.",
				"void main() {",
				"\tfloat h = floor(resolution.x / " + w + " * " + h +
						" + 0.5);",
				"\tif (h < 1.0) {",
				"\t\tgl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);",
				"\t\treturn;",
				"\t}",
				"\tfloat y = mod(resolution.y - 1.0 - floor(gl_FragCoord.y), " +
						"h * 2.0);",
				"\tif (y >= h) {",
				"\t\ty = h * 2.0 - y - 1.0;",
				"\t}",
				"\tgl_FragColor = texture2D(image, vec2(",
				"\t\tgl_FragCoord.x / resolution.x,",
				"\t\t1.0 - (y + 0.5) / h));",
				"}",
				"");
	}

	/**
	 * Where the shader of {@link #text} samples the image for a pixel
	 * of the target, computed statement for statement as the shader
	 * does, in 32-bit floats: texture coordinates with (0,0) at the
	 * image's lower left, or null where the shader writes black.
	 */
	static float[] position(int width, int height, float resolutionX,
			float resolutionY, float fragX, float fragY) {
		float h = (float) Math.floor(resolutionX / Float.parseFloat(
				constant(width)) * Float.parseFloat(constant(height)) + 0.5f);
		if (h < 1f) {
			return null;
		}
		float y = mod(resolutionY - 1f - (float) Math.floor(fragY), h * 2f);
		if (y >= h) {
			y = h * 2f - y - 1f;
		}
		return new float[]{fragX / resolutionX, 1f - (y + 0.5f) / h};
	}

	private static String constant(int value) {
		return value + ".0";
	}

	// GLSL's mod.
	private static float mod(float x, float y) {
		return x - y * (float) Math.floor(x / y);
	}
}
