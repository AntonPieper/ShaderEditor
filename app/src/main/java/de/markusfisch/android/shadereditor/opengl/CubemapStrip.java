package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.nio.IntBuffer;

// An image read by a samplerCube: a horizontal strip of six square faces
// in the order +X, -X, +Y, -Y, +Z, -Z, each uploaded with its rows from
// the top, the orientation GL expects for cube faces.
final class CubemapStrip {
	static final int FACES = 6;

	private CubemapStrip() {
	}

	static boolean isStrip(int width, int height) {
		return height > 0 && width == (long) FACES * height;
	}

	// Copies face 0 to 5 of a strip whose rows are given from the bottom,
	// as DecodedImages has them, into out with its rows from the top.
	static void cut(@NonNull IntBuffer strip, int side, int face,
			@NonNull IntBuffer out) {
		int width = side * FACES;
		for (int row = 0; row < side; ++row) {
			int from = (side - 1 - row) * width + face * side;
			for (int x = 0; x < side; ++x) {
				out.put(row * side + x, strip.get(from + x));
			}
		}
	}
}
