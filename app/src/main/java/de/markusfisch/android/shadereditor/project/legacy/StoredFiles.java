package de.markusfisch.android.shadereditor.project.legacy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * What the view over database shaders and library textures decides
 * without Android.
 */
public final class StoredFiles {
	/** Why a library texture could not be served. */
	public enum Problem {
		UNREADABLE,
		NOT_A_CUBEMAP,
		NOT_ENCODED,
		NO_MEMORY,
		DATABASE
	}

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

	/**
	 * Return why an image of this size cannot be served within a limit
	 * of decoded pixels, as a cubemap strip if cubemap is set, or null
	 * when it can. Called before the image is decoded.
	 */
	public static String check(String name, int width, int height,
			boolean cubemap, int limit) {
		if (width < 1 || height < 1) {
			return "The library texture \"" + name + "\" has no pixels";
		}
		limit = Math.max(limit, 0);
		long pixels = (long) width * height;
		if (cubemap) {
			long side = CubemapAtlas.faceSize(width, height);
			if (side < 1) {
				return reason(Problem.NOT_A_CUBEMAP, name);
			}
			pixels = Math.max(pixels, 6 * side * side);
		}
		return pixels > limit
				? "The library texture \"" + name + "\" needs " + pixels +
						" decoded pixels, more than the limit of " + limit +
						" pixels per image; use a smaller texture"
				: null;
	}

	/** Return the reason for a problem with a library texture. */
	public static String reason(Problem problem, String name) {
		String texture = "The library texture \"" + name + "\" ";
		return texture + switch (problem) {
			case UNREADABLE -> "is gone from the library or could not " +
					"be decoded; import it again";
			case NOT_A_CUBEMAP -> "cannot be cut into the six faces of a " +
					"cubemap; import it again as a cubemap";
			case NOT_ENCODED -> "could not be encoded as PNG";
			case NO_MEMORY -> "does not fit into memory; close other " +
					"apps or use a smaller texture";
			case DATABASE -> "could not be read from the database; " +
					"try again";
		};
	}
}
