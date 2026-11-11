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
				"\tfloat h = floor(resolution.x / " + width + ".0 * " +
						height + ".0 + 0.5);",
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
}
