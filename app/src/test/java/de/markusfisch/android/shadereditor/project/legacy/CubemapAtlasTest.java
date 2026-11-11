package de.markusfisch.android.shadereditor.project.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CubemapAtlasTest {
	@Test
	public void pinsEveryFaceToItsPlaceInTheStrip() {
		// Squares of the 2x3 atlas, left to right and top to bottom, and
		// the face the old upload made of each.
		int[][] squares = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {0, 2}, {1, 2}};
		String[] atlasFaces = {"-X", "-Z", "+Y", "-Y", "+Z", "+X"};
		String[] stripFaces = {"+X", "-X", "+Y", "-Y", "+Z", "-Z"};
		int side = 3;
		int width = side * 2;
		int height = side * 3;
		int[] atlas = pixels(width, height);
		int[] strip = CubemapAtlas.toStrip(atlas, width, height);
		assertNotNull(strip);
		assertEquals(side * 6 * side, strip.length);
		for (int square = 0; square < 6; ++square) {
			int face = indexOf(stripFaces, atlasFaces[square]);
			for (int y = 0; y < side; ++y) {
				for (int x = 0; x < side; ++x) {
					assertEquals(atlasFaces[square] + " " + x + "," + y,
							atlas[(squares[square][1] * side + y) * width +
									squares[square][0] * side + x],
							strip[y * side * 6 + face * side + x]);
				}
			}
		}
	}

	@Test
	public void cutsOddSizesLikeTheOldUpload() {
		// 7 wide gives columns at 0 and 4, 10 high rows at 0, 3 and 6;
		// faces are 3 pixels.
		int width = 7;
		int height = 10;
		assertEquals(3, CubemapAtlas.faceSize(width, height));
		int[] atlas = pixels(width, height);
		int[] strip = CubemapAtlas.toStrip(atlas, width, height);
		assertNotNull(strip);
		// +X is the sixth square, at column 4 of row 6.
		assertEquals(atlas[6 * width + 4], strip[0]);
		assertEquals(atlas[8 * width + 6], strip[2 * 18 + 2]);
		// -Z is the second square, at column 4 of row 0.
		assertEquals(atlas[4], strip[15]);
	}

	@Test
	public void rejectsSizesWhosePixelCountsOverflow() {
		// 65536 * 65536 wraps to 0 in int arithmetic.
		assertNull(CubemapAtlas.toStrip(new int[0], 65536, 65536));
		assertNull(CubemapAtlas.toStrip(new int[16], 4, Integer.MAX_VALUE));
		assertNull(CubemapAtlas.toStrip(new int[16], Integer.MAX_VALUE,
				Integer.MAX_VALUE));
		assertNull(CubemapAtlas.toStrip(new int[16], -4, -4));
	}

	@Test
	public void rejectsImagesTooSmallForTheOldCut() {
		assertNull(CubemapAtlas.toStrip(new int[1], 1, 1));
		// Faces of 3 pixels at column 3 do not fit 5 pixels.
		assertNull(CubemapAtlas.toStrip(new int[5 * 9], 5, 9));
		assertNull(CubemapAtlas.toStrip(new int[3], 2, 3));
	}

	private static int[] pixels(int width, int height) {
		int[] pixels = new int[width * height];
		for (int i = 0; i < pixels.length; ++i) {
			pixels[i] = 0xff000000 | i;
		}
		return pixels;
	}

	private static int indexOf(String[] values, String value) {
		for (int i = 0; i < values.length; ++i) {
			if (values[i].equals(value)) {
				return i;
			}
		}
		return -1;
	}
}
