package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.IntBuffer;

public class CubemapStripTest {
	@Test
	public void aStripIsSixTimesAsWideAsHigh() {
		assertTrue(CubemapStrip.isStrip(6, 1));
		assertTrue(CubemapStrip.isStrip(1536, 256));
		assertFalse(CubemapStrip.isStrip(600, 200));
		assertFalse(CubemapStrip.isStrip(256, 1536));
		assertFalse(CubemapStrip.isStrip(0, 0));
	}

	@Test
	public void cutsFaceIFromTheLeftWithItsRowsFromTheTop() {
		// A 12x2 strip of 2x2 faces as the file stores it, rows from the
		// top: each pixel says face, column and row in that face.
		int side = 2;
		int width = side * CubemapStrip.FACES;
		int[][] image = new int[side][width];
		for (int y = 0; y < side; ++y) {
			for (int x = 0; x < width; ++x) {
				image[y][x] = pixel(x / side, x % side, y);
			}
		}
		// Decoded with the bottom row first.
		var strip = IntBuffer.allocate(width * side);
		for (int row = 0; row < side; ++row) {
			strip.put(image[side - 1 - row]);
		}
		var face = IntBuffer.allocate(side * side);
		for (int i = 0; i < CubemapStrip.FACES; ++i) {
			CubemapStrip.cut(strip, side, i, face);
			assertArrayEquals("face " + i, new int[]{
					pixel(i, 0, 0), pixel(i, 1, 0),
					pixel(i, 0, 1), pixel(i, 1, 1)}, face.array());
		}
	}

	@Test
	public void cutsAOnePixelFacePerColumn() {
		var strip = IntBuffer.wrap(new int[]{10, 11, 12, 13, 14, 15});
		var face = IntBuffer.allocate(1);
		for (int i = 0; i < CubemapStrip.FACES; ++i) {
			CubemapStrip.cut(strip, 1, i, face);
			assertArrayEquals(new int[]{10 + i}, face.array());
		}
	}

	private static int pixel(int face, int x, int yFromTop) {
		return face * 100 + yFromTop * 10 + x;
	}
}
