package de.markusfisch.android.shadereditor.navigation;

/**
 * What stands at the start of a row, decided here and nowhere else,
 * from what the row stands for and what is known of it: a picture
 * where one is known, else an icon of what it is. How a row looks is
 * changed in this one place.
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

	private Face() {
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
		if (picture != null && picture.length > 0 && of != Of.MORE) {
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
