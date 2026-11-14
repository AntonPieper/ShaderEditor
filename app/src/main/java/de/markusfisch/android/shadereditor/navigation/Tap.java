package de.markusfisch.android.shadereditor.navigation;

/** What a tap on a row does. */
public enum Tap {
	/** Goes into the level the row leads to; nothing else changes. */
	ENTER,
	/** Opens the file in the editor; what runs keeps running. */
	OPEN,
	/** Opens the file and runs it. */
	OPEN_AND_RUN,
	/** Shows the image. */
	SHOW_IMAGE,
	/** Shows more rows of the level. */
	MORE,
	/** Nothing: the editor cannot open the file. */
	NOTHING;

	/**
	 * A tap runs a file that can run, unless the running shader
	 * already uses it; then, like any other text, it only opens.
	 *
	 * @param inUse whether the running shader is this file or uses it
	 */
	public static Tap of(Row.Kind kind, boolean inUse) {
		return switch (kind) {
			case FOLDER -> ENTER;
			case SHADER -> inUse ? OPEN : OPEN_AND_RUN;
			case TEXT -> OPEN;
			case IMAGE -> SHOW_IMAGE;
			case OTHER -> NOTHING;
			case MORE -> MORE;
		};
	}

	/**
	 * What this tap, chosen from what a file's name hints at, does once
	 * its first bytes told whether it is a picture: a picture shows as
	 * one whatever its name, any other file opens as text does, also
	 * when its name looked like a picture's.
	 */
	public Tap read(boolean picture) {
		return picture ? SHOW_IMAGE : this == SHOW_IMAGE ? OPEN : this;
	}
}
