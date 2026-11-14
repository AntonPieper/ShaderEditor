package de.markusfisch.android.shadereditor.navigation;

import java.util.Set;

/**
 * One entry of a level as its source lists it: a key the source knows
 * it by, what it is, its picture, its name and an optional second
 * line, when it was last changed in milliseconds since the epoch (0
 * when not known), the level a folder leads into, its marks and what
 * its menu offers.
 */
public record Row(String key, Kind kind, Picture picture, String name,
		String detail, long changed, Level enters, Set<Mark> marks,
		Set<Action> actions) {
	/** What a row stands for, which decides what a tap does. */
	public enum Kind {
		/** Leads into another level. */
		FOLDER,
		/** A file that can run: its text defines main. */
		SHADER,
		/** A text file that cannot run by itself, like an include. */
		TEXT,
		/** An image. */
		IMAGE,
		/** A file the editor cannot open. */
		OTHER
	}

	/** Marks at the end of a row, in the order they are shown. */
	public enum Mark {
		UNSAVED,
		WALLPAPER,
		READ_ONLY,
		RUNNING
	}

	/** Entries of a row's menu. */
	public enum Action {
		RENAME,
		DUPLICATE,
		DELETE
	}

	/** What stands at the start of a row: a picture or an icon. */
	public record Picture(Icon icon, byte[] png, int resource) {
		/** Icons for rows without a picture. */
		public enum Icon {
			SHADER,
			LIBRARY,
			EXAMPLES,
			FOLDER
		}

		public static Picture of(Icon icon) {
			return new Picture(icon, null, 0);
		}

		/** A PNG image; a shader without one shows the shader icon. */
		public static Picture png(byte[] png) {
			return new Picture(Icon.SHADER, png, 0);
		}

		/** A picture that comes with the app, by its resource id. */
		public static Picture resource(int resource) {
			return new Picture(Icon.SHADER, null, resource);
		}
	}
}
