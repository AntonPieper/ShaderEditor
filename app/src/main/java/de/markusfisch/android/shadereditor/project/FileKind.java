package de.markusfisch.android.shadereditor.project;

import java.util.Locale;

/**
 * What a file is, read from its bytes: a picture when it starts with
 * the signature of a supported image format, text when its bytes are
 * UTF-8, anything else other. The name of a file plays no part in that.
 * What a file is used as, a texture, a pass or an include, comes from
 * what refers to it, not from here.
 * <p>
 * A list of files that cannot read every file asks {@link #hint}
 * instead, which looks at a name and a reported type and never at
 * bytes. A hint chooses an icon and decides nothing else.
 */
public enum FileKind {
	PICTURE, TEXT, OTHER;

	// A picture format: the bytes its files start with, which tell it,
	// and the endings its files are usually named with, which only hint
	// at it. Supporting another format is adding a row.
	private record Format(String name, int[] signature, String... endings) {
	}

	private static final Format[] FORMATS = {
			new Format("PNG", new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a,
					0x1a, 0x0a}, ".png"),
			new Format("JPEG", new int[]{0xff, 0xd8, 0xff}, ".jpg", ".jpeg"),
	};

	/** How many of its first bytes tell whether a file is a picture. */
	public static final int HEAD = 8;

	/**
	 * Return the name of the image format of a file that starts with
	 * head, at least its first {@link #HEAD} bytes or all it has, or
	 * null when it is not a picture.
	 */
	public static String picture(byte[] head) {
		for (Format format : FORMATS) {
			int[] signature = format.signature();
			boolean starts = head.length >= signature.length;
			for (int i = 0; starts && i < signature.length; ++i) {
				starts = (head[i] & 0xff) == signature[i];
			}
			if (starts) {
				return format.name();
			}
		}
		return null;
	}

	/**
	 * Return what a file of bytes, all of them, is. Limits on the size
	 * of a text are the caller's.
	 */
	public static FileKind of(byte[] bytes) {
		return picture(bytes) != null ? PICTURE
				: SourceText.decode(bytes) != null ? TEXT : OTHER;
	}

	/**
	 * Return what a file named name, of the type its storage reports,
	 * null when it reports none, is likely to be, without reading it: a
	 * picture when the type or the name says so, else text. Only for
	 * choosing an icon; the file is what {@link #of} finds it to be
	 * once it is read.
	 */
	public static FileKind hint(String name, String type) {
		if (type != null && type.startsWith("image/")) {
			return PICTURE;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (Format format : FORMATS) {
			for (String ending : format.endings()) {
				if (lower.endsWith(ending)) {
					return PICTURE;
				}
			}
		}
		return TEXT;
	}
}
