package de.markusfisch.android.shadereditor.project;

/**
 * Bytes that are a picture for the tests: what a file is, is read from
 * its bytes, so a test's image starts as a PNG does.
 */
public final class Pictures {
	private Pictures() {
	}

	/** Return the first bytes of a PNG, followed by rest. */
	public static byte[] png(int... rest) {
		byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
		byte[] png = java.util.Arrays.copyOf(bytes, bytes.length + rest.length);
		for (int i = 0; i < rest.length; ++i) {
			png[bytes.length + i] = (byte) rest[i];
		}
		return png;
	}

	/** Return the first bytes of a JPEG. */
	public static byte[] jpeg() {
		return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
	}
}
