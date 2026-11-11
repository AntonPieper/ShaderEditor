package de.markusfisch.android.shadereditor.project.legacy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * What the view over database shaders and library textures decides
 * without Android.
 */
public final class StoredFiles {
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private StoredFiles() {
	}

	/**
	 * Return a revision of these bytes that changes whenever they do, or
	 * null when SHA-256 is not available.
	 */
	public static String revision(byte[] bytes) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
		StringBuilder sb = new StringBuilder("sha256:");
		for (byte b : digest.digest(bytes)) {
			sb.append(HEX[(b >> 4) & 15]).append(HEX[b & 15]);
		}
		return sb.toString();
	}
}
