package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageDecodeTest {
	private static final int SIDE = 128;
	// A phone's screen: 1080 × 2424.
	private static final int LONGEST = 2424;
	private static final long PIXELS = 1080L * 2424;

	// A strip (a sprite sheet, a panorama, a LUT) was decoded whole for
	// one row: 80 MB for 100000 × 200. Its face shows the middle at
	// 3:1, so only that is decoded, small.
	@Test
	public void aStripIsNotDecodedWholeForARow() {
		int[][] strips = {{100_000, 200}, {16_384, 256}, {30_000, 1_000},
				{1_000, 30_000}, {8_000, 8_000}, {200_000, 3}};
		for (int[] strip : strips) {
			ImageDecode.Plan plan = ImageDecode.face(strip[0], strip[1],
					SIDE);
			assertTrue(strip[0] + " x " + strip[1] + " decodes " +
					plan.bytes() + " bytes for one 40 dp row",
					plan.bytes() < 48L * SIDE * SIDE);
		}
	}

	@Test
	public void aRowDecodesTheMiddleOfAStrip() {
		ImageDecode.Plan plan = ImageDecode.face(30_000, 1_000, SIDE);
		assertEquals(new ImageDecode.Plan(13_500, 0, 16_500, 1_000, 4),
				plan);
		assertEquals(4L * 750 * 250, plan.bytes());
	}

	// A small image is decoded whole, as it is: its pixels show crisp.
	@Test
	public void aSmallImageIsDecodedAsItIs() {
		assertEquals(new ImageDecode.Plan(0, 0, 16, 16, 1),
				ImageDecode.face(16, 16, SIDE));
		assertEquals(new ImageDecode.Plan(0, 0, 640, 480, 2),
				ImageDecode.face(640, 480, SIDE));
	}

	// The sheet shows the whole image at no more than the screen: a
	// strip by its longer side, a large square by its pixels.
	@Test
	public void theSheetIsBoundedByTheScreen() {
		ImageDecode.Plan strip = ImageDecode.whole(30_000, 1_000, LONGEST,
				PIXELS);
		assertEquals(16, strip.sample());
		assertTrue(strip.bytes() < 500_000);
		ImageDecode.Plan square = ImageDecode.whole(8_000, 8_000, LONGEST,
				PIXELS);
		assertEquals(8, square.sample());
		assertEquals(4L * 1_000 * 1_000, square.bytes());
		assertEquals(1, ImageDecode.whole(256, 256, LONGEST, PIXELS)
				.sample());
		for (int[] size : new int[][]{{100_000, 200}, {65_535, 65_535},
				{4_032, 3_024}, {1, 1}}) {
			ImageDecode.Plan plan = ImageDecode.whole(size[0], size[1],
					LONGEST, PIXELS);
			assertTrue(plan.bytes() <= 4 * PIXELS);
		}
	}
}
