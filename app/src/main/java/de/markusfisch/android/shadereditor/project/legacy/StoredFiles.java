package de.markusfisch.android.shadereditor.project.legacy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.database.DataRecords.TextureInfo;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.LibraryTexture;

/**
 * What the view over database shaders and library textures decides
 * without Android.
 */
public final class StoredFiles {
	/**
	 * A library texture as the conversion saw it: its row, its stored
	 * size, and whether it is a cubemap atlas. The image served for it
	 * must have this size.
	 */
	public record Stored(Long id, String name, int width, int height,
			boolean atlas) {
		public LibraryTexture texture() {
			return new LibraryTexture(atlas, width, height);
		}
	}

	/** Why a library texture could not be served. */
	public enum Problem {
		UNREADABLE,
		REMOVED,
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
	 * Return the library texture of every name: the row with the lowest
	 * id, which the old renderer read, an atlas when that row is one.
	 */
	public static Map<String, Stored> library(List<TextureInfo> squares,
			List<TextureInfo> atlases) {
		Map<String, TextureInfo> rows = new HashMap<>();
		add(rows, squares);
		add(rows, atlases);
		Map<String, Stored> library = new HashMap<>();
		for (TextureInfo info : rows.values()) {
			library.put(info.name(), new Stored(info.id(), info.name(),
					info.width(), info.height(), atlases.contains(info)));
		}
		return library;
	}

	/**
	 * Return why an image of this size is not the one the conversion
	 * used, or null when it is.
	 */
	public static String changed(Stored stored, int width, int height) {
		return width != stored.width() || height != stored.height()
				? "The library texture \"" + stored.name() + "\" changed " +
						"from " + stored.width() + "x" + stored.height() +
						" to " + width + "x" + height + " pixels after the " +
						"shader was opened; open the shader again"
				: null;
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
			case REMOVED -> "was removed from the library after the " +
					"shader was opened; open the shader again";
			case NOT_A_CUBEMAP -> "cannot be cut into the six faces of a " +
					"cubemap; import it again as a cubemap";
			case NOT_ENCODED -> "could not be encoded as PNG";
			case NO_MEMORY -> "does not fit into memory; close other " +
					"apps or use a smaller texture";
			case DATABASE -> "could not be read from the database; " +
					"try again";
		};
	}

	private static void add(Map<String, TextureInfo> rows,
			List<TextureInfo> textures) {
		for (TextureInfo info : textures) {
			TextureInfo known = rows.get(info.name());
			if (known == null || info.id() < known.id()) {
				rows.put(info.name(), info);
			}
		}
	}
}
