package de.markusfisch.android.shadereditor.project.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PresetShaderTest {
	// The shader computes in 32-bit floats; 1e-6 is a few units in the
	// last place near 1, and far below half a pixel of any image or
	// target, so it cannot move a position into another pixel.
	private static final float TOLERANCE = 1e-6f;

	@Test
	public void samplesTheImageWhereTheOldPresetBitmapHadIt() {
		// Non-square images; target widths whose scaled tile height
		// rounds down, up, exactly at .5, and to zero.
		int[][] images = {{5, 3}, {4, 3}, {3, 7}, {10, 2}};
		int[][] targets = {{7, 20}, {13, 9}, {8, 31}, {2, 11}, {1, 5},
				{100, 17}, {6, 40}};
		for (int[] image : images) {
			for (int[] target : targets) {
				assertSameMapping(image[0], image[1], target[0], target[1]);
			}
		}
	}

	@Test
	public void noPresetWhereTheTileIsLessThanAPixelHigh() {
		// 2 / 10 * 2 = 0.4 rounds to a tile height of 0.
		assertNull(oldTilePixel(2, 4, 10, 2, 0, 0));
		assertNull(PresetShader.position(10, 2, 2, 4, 0.5f, 0.5f));
	}

	private static void assertSameMapping(int imageWidth, int imageHeight,
			int width, int height) {
		for (int row = 0; row < height; ++row) {
			for (int x = 0; x < width; ++x) {
				String at = imageWidth + "x" + imageHeight + " on " +
						width + "x" + height + " at " + x + "," + row;
				int[] old = oldTilePixel(width, height, imageWidth,
						imageHeight, x, row);
				float[] position = PresetShader.position(imageWidth,
						imageHeight, width, height, x + 0.5f, row + 0.5f);
				if (old == null) {
					assertNull(at, position);
					continue;
				}
				assertNotNull(at, position);
				int tileHeight = old[2];
				// The old tile is the image scaled to width x tileHeight,
				// rows from the top; the shader reads the image with
				// (0,0) at its lower left.
				float u = position[0];
				float down = 1f - position[1];
				assertEquals(at, old[0], (int) Math.floor(u * width));
				assertEquals(at, old[1], (int) Math.floor(down * tileHeight));
				assertEquals(at, (old[0] + 0.5f) / width, u, TOLERANCE);
				assertEquals(at, (old[1] + 0.5f) / tileHeight, down,
						TOLERANCE);
				assertTrue(at, u > 0f && u < 1f && down > 0f && down < 1f);
			}
		}
	}

	// BackBufferParameters.getPresetBitmap and its upload at cf4ac23:
	// the bitmap, rows from the top, was uploaded flipped, so GL row r
	// (from the bottom, gl_FragCoord.y = r + 0.5) held bitmap row
	// height - 1 - r. Returns the column and row of the scaled tile
	// that pixel copied, and the tile height, or null where
	// createScaledBitmap threw and there was no preset.
	private static int[] oldTilePixel(int width, int height, int tw, int th,
			int x, int glRow) {
		int y = height - 1 - glRow;
		int scaledHeight = Math.round((float) width / tw * th);
		if (scaledHeight <= 0) {
			return null;
		}
		int mirroredY = y % (scaledHeight * 2);
		if (mirroredY >= scaledHeight) {
			mirroredY = (scaledHeight * 2) - mirroredY - 1;
		}
		return new int[]{x, mirroredY, scaledHeight};
	}
}
