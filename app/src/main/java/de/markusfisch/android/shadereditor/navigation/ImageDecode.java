package de.markusfisch.android.shadereditor.navigation;

/**
 * How much of an image to decode, and at which step, from the size its
 * header tells, so that no image costs more memory than what shows it:
 * a row's face shows the middle of a long strip at 3:1 and needs no
 * more than its 40 dp; the sheet shows the whole image at no more than
 * the screen. Decoding takes every sample-th pixel of the region.
 */
public final class ImageDecode {
	/**
	 * The region to decode, from left, top to right, bottom in the
	 * image's pixels, and the power of two to step by.
	 */
	public record Plan(int left, int top, int right, int bottom,
			int sample) {
		/** Bytes the decoded region takes at four bytes a pixel. */
		public long bytes() {
			return 4L * ((right - left) / sample) *
					((bottom - top) / sample);
		}

		/** Whether the region is the whole image of width by height. */
		public boolean whole(int width, int height) {
			return left == 0 && top == 0 && right == width &&
					bottom == height;
		}
	}

	private ImageDecode() {
	}

	/**
	 * The plan for a row's face of an image of width by height: the
	 * part {@link Face#place} shows, the middle of a long strip, at the
	 * largest step that keeps its shorter side at side pixels or more.
	 * So the region decoded is at most 3 by 1 and its shorter side less
	 * than twice side: under 48 side² bytes (786 kB at 128 px), whatever
	 * the image.
	 */
	public static Plan face(int width, int height, int side) {
		Face.Placed placed = Face.place(width, height, side);
		int w = placed.right() - placed.left();
		int h = placed.bottom() - placed.top();
		int shorter = Math.min(w, h);
		int sample = 1;
		while (shorter / (sample * 2) >= side) {
			sample *= 2;
		}
		return new Plan(placed.left(), placed.top(), placed.right(),
				placed.bottom(), sample);
	}

	/**
	 * The plan for the sheet: the whole image, at the smallest step
	 * that keeps its longer side at most longest pixels and its pixels
	 * at most pixels, the screen's.
	 */
	public static Plan whole(int width, int height, int longest,
			long pixels) {
		int w = Math.max(1, width);
		int h = Math.max(1, height);
		int sample = 1;
		while (Math.max(w, h) / sample > longest ||
				(long) (w / sample) * (h / sample) > pixels) {
			sample *= 2;
		}
		return new Plan(0, 0, w, h, sample);
	}
}
