package de.markusfisch.android.shadereditor.navigation;

import java.util.Locale;

import de.markusfisch.android.shadereditor.project.ProjectManifest;
import de.markusfisch.android.shadereditor.project.ShaderLanguage;

/**
 * What stands at the start of a row, decided here and nowhere else,
 * from what the row stands for, its name and what is known of it. A
 * picture stands on a tile: a shader's edge to edge, a folder's by its
 * default shader, an image's whole on a mat. Anything without one gets
 * a stock icon without a tile, so it never looks like a missing
 * picture; a shader that never ran is the one exception, an empty
 * frame where its picture will be, with nothing in it. A file that
 * cannot be opened is dimmed. Every face says what the row is, for
 * TalkBack. How a row looks is changed in this one place.
 */
public final class Face {
	/** What a row stands for, as far as its face is concerned. */
	public enum Of {
		/** The library, on the top level. */
		LIBRARY,
		/** The examples, on the top level. */
		EXAMPLES,
		/** A folder, or a folder opened before. */
		FOLDER,
		/** A single file opened before. */
		SINGLE_FILE,
		/** A file that is not an image by its name. */
		FILE,
		/** A file that is an image by its name. */
		IMAGE,
		/** The row that shows more rows. */
		MORE
	}

	/**
	 * Where an image stands on its mat: the part of it shown, from
	 * left, top to right, bottom in its pixels, drawn width by height
	 * in the middle of the mat, with crisp pixels or smoothly.
	 */
	public record Placed(int left, int top, int right, int bottom,
			int width, int height, boolean crisp) {
	}

	/** A strip longer than this to one is shown by its middle. */
	public static final int LONGEST = 3;
	/** The largest file the editor opens, in bytes. */
	public static final long LARGEST = 16L * 1024 * 1024;

	private Face() {
	}

	/** The face of a row of kind named name, with its picture or null. */
	public static Row.Picture of(Row.Kind kind, String name, byte[] picture) {
		return switch (kind) {
			case FOLDER -> of(Of.FOLDER, name, picture, true);
			case IMAGE -> of(Of.IMAGE, name, picture, true);
			case MORE -> of(Of.MORE, name, picture, true);
			case OTHER -> of(Of.FILE, name, picture, false);
			default -> of(Of.FILE, name, picture, true);
		};
	}

	/**
	 * The face of a row standing for of, named name, with the picture
	 * kept for it, or null when none is known: the picture of a shader
	 * that ran, of what runs in a folder.
	 *
	 * @param openable whether the editor can open the file
	 */
	public static Row.Picture of(Of of, String name, byte[] picture,
			boolean openable) {
		boolean known = picture != null && picture.length > 0;
		switch (of) {
			case MORE:
				return icon(Row.Picture.Icon.FILE, Row.Picture.Spoken.NONE,
						false);
			case LIBRARY:
			case EXAMPLES:
			case FOLDER:
				return known
						? png(picture, Row.Picture.Spoken.FOLDER)
						: icon(of == Of.LIBRARY
								? Row.Picture.Icon.LIBRARY
								: of == Of.EXAMPLES
								? Row.Picture.Icon.EXAMPLES
								: Row.Picture.Icon.FOLDER,
								Row.Picture.Spoken.FOLDER, false);
			case IMAGE:
				return openable
						? new Row.Picture(Row.Picture.Icon.IMAGE, null, 0,
								true, false, false, Row.Picture.Spoken.IMAGE)
						: icon(Row.Picture.Icon.IMAGE,
								Row.Picture.Spoken.CANNOT_OPEN, true);
			default:
				break;
		}
		if (!openable) {
			return icon(Row.Picture.Icon.FILE,
					Row.Picture.Spoken.CANNOT_OPEN, true);
		} else if (known) {
			return png(picture, Row.Picture.Spoken.SHADER);
		}
		String lower = name != null ? name.toLowerCase(Locale.ROOT) : "";
		ShaderLanguage language = ShaderLanguage.listed(lower);
		if (language == ShaderLanguage.GLSL &&
				!lower.endsWith(ShaderLanguage.INCLUDE)) {
			// The empty place of its picture: one run fills it.
			return new Row.Picture(Row.Picture.Icon.FILE, null, 0, false,
					true, false, Row.Picture.Spoken.SHADER_NOT_RUN);
		} else if (language == ShaderLanguage.GLSL) {
			return icon(Row.Picture.Icon.CODE, Row.Picture.Spoken.INCLUDE,
					false);
		} else if (language == ShaderLanguage.UNSUPPORTED) {
			return icon(Row.Picture.Icon.CODE,
					Row.Picture.Spoken.OTHER_LANGUAGE, true);
		} else if (language == ShaderLanguage.JSON) {
			return lower.equals(ProjectManifest.FILE.toString())
					? icon(Row.Picture.Icon.SETTINGS,
							Row.Picture.Spoken.SETTINGS, false)
					: icon(Row.Picture.Icon.DATA, Row.Picture.Spoken.JSON,
							false);
		} else if (language == ShaderLanguage.MARKDOWN) {
			return icon(Row.Picture.Icon.NOTES, Row.Picture.Spoken.MARKDOWN,
					false);
		}
		return icon(Row.Picture.Icon.FILE, Row.Picture.Spoken.FILE, false);
	}

	/**
	 * Place an image of width by height pixels on a mat whose inside is
	 * inner pixels square: whole and in its own proportions, a strip
	 * wider than 3:1 or taller than 1:3 by its middle at 3:1, and an
	 * image smaller than the inside at a whole multiple of its size
	 * with crisp pixels, so that a tiny icon shows its pixels.
	 */
	public static Placed place(int width, int height, int inner) {
		int left = 0;
		int top = 0;
		int right = Math.max(1, width);
		int bottom = Math.max(1, height);
		if (right > bottom * LONGEST) {
			int shown = bottom * LONGEST;
			left = (right - shown) / 2;
			right = left + shown;
		} else if (bottom > right * LONGEST) {
			int shown = right * LONGEST;
			top = (bottom - shown) / 2;
			bottom = top + shown;
		}
		int w = right - left;
		int h = bottom - top;
		if (w <= inner && h <= inner) {
			int times = Math.max(1, inner / Math.max(w, h));
			return new Placed(left, top, right, bottom, w * times,
					h * times, true);
		}
		return w >= h
				? new Placed(left, top, right, bottom, inner,
						Math.max(1, Math.round((float) h * inner / w)), false)
				: new Placed(left, top, right, bottom,
						Math.max(1, Math.round((float) w * inner / h)), inner,
						false);
	}

	private static Row.Picture png(byte[] png, Row.Picture.Spoken spoken) {
		return new Row.Picture(Row.Picture.Icon.FILE, png, 0, false, false,
				false, spoken);
	}

	private static Row.Picture icon(Row.Picture.Icon icon,
			Row.Picture.Spoken spoken, boolean dim) {
		return new Row.Picture(icon, null, 0, false, false, dim, spoken);
	}
}
