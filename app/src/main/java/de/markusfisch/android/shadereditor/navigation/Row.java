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
		OTHER,
		/** Shows more rows of the level. */
		MORE
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
		/** Runs the file from now on. */
		RUN,
		RENAME,
		DUPLICATE,
		/** Creates something in the folder. */
		NEW_HERE,
		DELETE,
		/** Removes it from Recent; its files stay. */
		REMOVE
	}

	/**
	 * What stands at the start of a row, as {@link Face} decides it: a
	 * picture edge to edge; the file itself, an image read small while
	 * its row is shown and framed on a mat, with its icon until then;
	 * an empty frame where a shader's picture will be; or an icon
	 * without a tile. dim says that the file cannot be opened, and
	 * spoken what the row is, for TalkBack.
	 */
	public record Picture(Icon icon, byte[] png, int resource, boolean own,
			boolean frame, boolean dim, Spoken spoken) {
		/** Icons for rows without a picture, without a tile. */
		public enum Icon {
			LIBRARY,
			EXAMPLES,
			FOLDER,
			IMAGE,
			/** GLSL to include, or another shading language. */
			CODE,
			/** The project's manifest. */
			SETTINGS,
			/** Other JSON. */
			DATA,
			/** Markdown. */
			NOTES,
			/** Any other file. */
			FILE
		}

		/** What a row is, as TalkBack says it. */
		public enum Spoken {
			NONE,
			FOLDER,
			SHADER,
			SHADER_NOT_RUN,
			PASS,
			IMAGE,
			INCLUDE,
			SETTINGS,
			JSON,
			MARKDOWN,
			OTHER_LANGUAGE,
			FILE,
			CANNOT_OPEN
		}

		public static Picture of(Icon icon) {
			return new Picture(icon, null, 0, false, false, false,
					Spoken.NONE);
		}

		/** A PNG image; a shader without one shows the file icon. */
		public static Picture png(byte[] png) {
			return new Picture(Icon.FILE, png, 0, false, false, false,
					Spoken.SHADER);
		}

		/** A picture that comes with the app, by its resource id. */
		public static Picture resource(int resource) {
			return new Picture(Icon.FILE, null, resource, false, false,
					false, Spoken.SHADER);
		}
	}
}
