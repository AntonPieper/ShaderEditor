package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.nio.IntBuffer;

// The EXIF orientation of a JPEG (APP1) or PNG (eXIf), and the turn that
// puts the pixels as the image is meant to be seen, with the first row
// at the bottom, so (0,0) is its lower-left corner.
final class ImageOrientation {
	static final int NORMAL = 1;

	private static final int TAG_ORIENTATION = 0x0112;
	private static final int TYPE_SHORT = 3;
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

	private ImageOrientation() {
	}

	// Returns 1 to 8, NORMAL when the image says nothing readable.
	static int of(@NonNull byte[] bytes) {
		if (startsWith(bytes, 0, PNG)) {
			for (int at = PNG.length; at + 8 <= bytes.length; ) {
				long length = uint(bytes, at, 4, false);
				if (length > bytes.length - at - 12) {
					break;
				} else if (startsWith(bytes, at + 4, new byte[]{'e', 'X', 'I', 'f'})) {
					return tiff(bytes, at + 8, at + 8 + (int) length);
				}
				at += 12 + (int) length;
			}
		} else if (bytes.length > 3 && (bytes[0] & 0xff) == 0xff &&
				(bytes[1] & 0xff) == 0xd8) {
			for (int at = 2; at + 4 <= bytes.length &&
					(bytes[at] & 0xff) == 0xff; ) {
				int marker = bytes[at + 1] & 0xff;
				int length = (int) uint(bytes, at + 2, 2, false);
				if (marker == 0xda || marker == 0xd9 || length < 2) {
					break;
				} else if (marker == 0xe1 && startsWith(bytes, at + 4,
						new byte[]{'E', 'x', 'i', 'f', 0, 0})) {
					return tiff(bytes, at + 10,
							Math.min(bytes.length, at + 2 + length));
				}
				at += 2 + length;
			}
		}
		return NORMAL;
	}

	static boolean swapsAxes(int orientation) {
		return orientation >= 5 && orientation <= 8;
	}

	// Writes the stored pixels, rows from the top, into out as they are
	// meant to be seen, rows from the bottom. Pixels are copied whole.
	static void turn(
			@NonNull int[] stored,
			int width,
			int height,
			int orientation,
			@NonNull IntBuffer out) {
		boolean swap = swapsAxes(orientation);
		boolean mirrorX = orientation == 2 || orientation == 3 ||
				orientation == 7 || orientation == 8;
		boolean mirrorY = orientation == 3 || orientation == 4 ||
				orientation == 6 || orientation == 7;
		int shownWidth = swap ? height : width;
		int shownHeight = swap ? width : height;
		int i = 0;
		for (int bottom = 0; bottom < shownHeight; ++bottom) {
			int y = shownHeight - 1 - bottom;
			for (int x = 0; x < shownWidth; ++x) {
				int sx = swap ? y : x;
				int sy = swap ? x : y;
				if (mirrorX) {
					sx = width - 1 - sx;
				}
				if (mirrorY) {
					sy = height - 1 - sy;
				}
				out.put(i++, stored[sy * width + sx]);
			}
		}
	}

	// The orientation in IFD0 of the TIFF structure in [start, end).
	private static int tiff(@NonNull byte[] bytes, int start, int end) {
		if (end - start < 8) {
			return NORMAL;
		}
		boolean little = bytes[start] == 'I' && bytes[start + 1] == 'I';
		if (!little && !(bytes[start] == 'M' && bytes[start + 1] == 'M')) {
			return NORMAL;
		}
		long ifd = start + uint(bytes, start + 4, 4, little);
		if (ifd + 2 > end) {
			return NORMAL;
		}
		int count = (int) uint(bytes, (int) ifd, 2, little);
		for (int i = 0; i < count; ++i) {
			int entry = (int) ifd + 2 + i * 12;
			if (entry + 12 > end) {
				break;
			} else if (uint(bytes, entry, 2, little) == TAG_ORIENTATION &&
					uint(bytes, entry + 2, 2, little) == TYPE_SHORT) {
				int orientation = (int) uint(bytes, entry + 8, 2, little);
				return orientation >= 1 && orientation <= 8
						? orientation
						: NORMAL;
			}
		}
		return NORMAL;
	}

	private static long uint(@NonNull byte[] bytes, int at, int size, boolean little) {
		long value = 0;
		for (int i = 0; i < size; ++i) {
			int b = bytes[at + (little ? size - 1 - i : i)] & 0xff;
			value = (value << 8) | b;
		}
		return value;
	}

	private static boolean startsWith(@NonNull byte[] bytes, int at, @NonNull byte[] prefix) {
		if (at < 0 || at + prefix.length > bytes.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; ++i) {
			if (bytes[at + i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}
}
