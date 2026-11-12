package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.IntBuffer;

public class ImageOrientationTest {
	// A 3x2 image as stored, rows from the top:
	//   1 2 3
	//   4 5 6
	private static final int[] STORED = {1, 2, 3, 4, 5, 6};

	@Test
	public void turnsEveryOrientationUprightWithTheFirstRowAtTheBottom() {
		// Rows from the bottom of the image as it is meant to be seen.
		assertTurn(1, 4, 5, 6, 1, 2, 3);
		assertTurn(2, 6, 5, 4, 3, 2, 1);
		assertTurn(3, 3, 2, 1, 6, 5, 4);
		assertTurn(4, 1, 2, 3, 4, 5, 6);
		// Seen 2x3: 1 4 / 2 5 / 3 6.
		assertTurn(5, 3, 6, 2, 5, 1, 4);
		// Seen 2x3: 4 1 / 5 2 / 6 3.
		assertTurn(6, 6, 3, 5, 2, 4, 1);
		// Seen 2x3: 6 3 / 5 2 / 4 1.
		assertTurn(7, 4, 1, 5, 2, 6, 3);
		// Seen 2x3: 3 6 / 2 5 / 1 4.
		assertTurn(8, 1, 4, 2, 5, 3, 6);
	}

	@Test
	public void readsTheOrientationOfAJpeg() {
		assertEquals(6, ImageOrientation.of(jpeg(tiff(true, 6))));
		assertEquals(8, ImageOrientation.of(jpeg(tiff(false, 8))));
	}

	@Test
	public void readsTheOrientationOfAPng() {
		assertEquals(3, ImageOrientation.of(png(tiff(false, 3))));
		assertEquals(5, ImageOrientation.of(png(tiff(true, 5))));
	}

	@Test
	public void imagesWithoutAReadableOrientationAreNormal() {
		assertEquals(1, ImageOrientation.of(new byte[0]));
		assertEquals(1, ImageOrientation.of(png(new byte[0])));
		assertEquals(1, ImageOrientation.of(jpeg(tiff(true, 9))));
		byte[] truncated = jpeg(tiff(true, 6));
		byte[] head = new byte[truncated.length - 12];
		System.arraycopy(truncated, 0, head, 0, head.length);
		assertEquals(1, ImageOrientation.of(head));
		assertEquals(1, ImageOrientation.of(new byte[]{(byte) 0xff,
				(byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0x7f, (byte) 0xff}));
	}

	private static void assertTurn(int orientation, int... expected) {
		var out = IntBuffer.allocate(STORED.length);
		ImageOrientation.turn(STORED, 3, 2, orientation, out);
		assertArrayEquals("orientation " + orientation, expected, out.array());
	}

	// A TIFF header with one IFD0 entry: the orientation.
	private static byte[] tiff(boolean little, int orientation) {
		var out = new ByteArrayOutputStream();
		out.writeBytes(little ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
		write(out, 42, 2, little);
		write(out, 8, 4, little);
		write(out, 1, 2, little);
		write(out, 0x0112, 2, little);
		write(out, 3, 2, little);
		write(out, 1, 4, little);
		write(out, orientation, 2, little);
		write(out, 0, 2, little);
		write(out, 0, 4, little);
		return out.toByteArray();
	}

	private static byte[] jpeg(byte[] tiff) {
		var out = new ByteArrayOutputStream();
		out.writeBytes(new byte[]{(byte) 0xff, (byte) 0xd8});
		// An APP0 segment first, as JFIF files have.
		out.writeBytes(new byte[]{(byte) 0xff, (byte) 0xe0, 0, 4, 0, 0});
		out.writeBytes(new byte[]{(byte) 0xff, (byte) 0xe1});
		write(out, 2 + 6 + tiff.length, 2, false);
		out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
		out.writeBytes(tiff);
		out.writeBytes(new byte[]{(byte) 0xff, (byte) 0xda, 0, 2});
		return out.toByteArray();
	}

	private static byte[] png(byte[] exif) {
		var out = new ByteArrayOutputStream();
		out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n',
				0x1a, '\n'});
		chunk(out, "IHDR", new byte[13]);
		if (exif.length > 0) {
			chunk(out, "eXIf", exif);
		}
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		write(out, data.length, 4, false);
		out.writeBytes(type.getBytes());
		out.writeBytes(data);
		write(out, 0, 4, false);
	}

	private static void write(ByteArrayOutputStream out, int value, int size,
			boolean little) {
		for (int i = 0; i < size; ++i) {
			int shift = 8 * (little ? i : size - 1 - i);
			out.write((value >> shift) & 0xff);
		}
	}
}
