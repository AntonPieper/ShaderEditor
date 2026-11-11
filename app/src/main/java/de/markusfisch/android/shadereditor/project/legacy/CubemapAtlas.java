package de.markusfisch.android.shadereditor.project.legacy;

/**
 * The old cubemap layout: a stored image cut into a grid of squares,
 * two per row, uploaded as the faces -X, -Z, +Y, -Y, +Z, +X. A project
 * reads a cubemap as one horizontal strip of six square faces in the
 * order +X, -X, +Y, -Y, +Z, -Z, rows from the top.
 */
public final class CubemapAtlas {
	// Strip position of each square, in the order the old upload read them.
	private static final int[] STRIP_FACE = {1, 5, 2, 3, 4, 0};

	private CubemapAtlas() {
	}

	/**
	 * Return the side length of the faces the old upload cut from an
	 * image of this size.
	 */
	public static int faceSize(int width, int height) {
		return Math.min((int) Math.ceil(width / 2f), Math.round(height / 3f));
	}

	/**
	 * Repack the pixels of a stored image, row by row from the top, into
	 * the strip, pixel for pixel. Return null when the image is too small
	 * for the squares the old upload cut from it, has fewer pixels than
	 * its size, or makes a strip too large for an array.
	 */
	public static int[] toStrip(int[] pixels, int width, int height) {
		int sideWidth = (int) Math.ceil(width / 2f);
		int sideHeight = Math.round(height / 3f);
		int side = faceSize(width, height);
		if (side < 1 || pixels.length < (long) width * height ||
				6L * side * side > Integer.MAX_VALUE) {
			return null;
		}
		int stripWidth = side * 6;
		int[] strip = new int[stripWidth * side];
		int x = 0;
		int y = 0;
		for (int face : STRIP_FACE) {
			if (x + side > width || y + side > height) {
				return null;
			}
			for (int row = 0; row < side; ++row) {
				System.arraycopy(pixels, (y + row) * width + x,
						strip, row * stripWidth + face * side, side);
			}
			if ((x += sideWidth) >= width) {
				x = 0;
				y += sideHeight;
			}
		}
		return strip;
	}
}
