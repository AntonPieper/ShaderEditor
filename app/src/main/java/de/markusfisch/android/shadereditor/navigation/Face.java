package de.markusfisch.android.shadereditor.navigation;

/**
 * What stands at the start of a row, decided here and nowhere else,
 * from what the row stands for and what is known of it: an image is
 * its own picture; anything else shows the picture known for it, a
 * folder the one of its default shader; else an icon of what it is.
 * How a row looks is changed in this one place.
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

	private Face() {
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

	/** The face of a row of kind, with its kept picture, or null. */
	public static Row.Picture of(Row.Kind kind, byte[] picture) {
		return of(switch (kind) {
			case FOLDER -> Of.FOLDER;
			case IMAGE -> Of.IMAGE;
			case MORE -> Of.MORE;
			default -> Of.FILE;
		}, picture);
	}

	/**
	 * The face of a row standing for of, with the picture kept for it,
	 * or null when none is known: the picture of a shader that ran, of
	 * what runs in a folder.
	 */
	public static Row.Picture of(Of of, byte[] picture) {
		if (of == Of.IMAGE) {
			// An image is its own picture.
			return Row.Picture.ofItself();
		} else if (picture != null && picture.length > 0 && of != Of.MORE) {
			return Row.Picture.png(picture);
		}
		return Row.Picture.of(switch (of) {
			case LIBRARY -> Row.Picture.Icon.LIBRARY;
			case EXAMPLES -> Row.Picture.Icon.EXAMPLES;
			case FOLDER -> Row.Picture.Icon.FOLDER;
			case IMAGE -> Row.Picture.Icon.IMAGE;
			case SINGLE_FILE, FILE, MORE -> Row.Picture.Icon.SHADER;
		});
	}
}
