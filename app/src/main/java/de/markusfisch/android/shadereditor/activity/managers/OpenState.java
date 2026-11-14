package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What the main screen has open where the shaders are files, as it is
 * kept when the screen is made again: the one thing that is written
 * for it, in one place, and the one thing a start reads to come back
 * to the same state. A file is told by the session of its folder and
 * its path, whether it is the library's, another folder's or an
 * example.
 *
 * @param kind     which of the states
 * @param key      the session of the file's folder, for a file
 * @param path     its path there
 * @param single   whether that is a single file, not a folder
 * @param tapped   for a file, whether by a tap that is not settled yet
 * @param quality  the quality it runs at
 * @param shader   the id of a shader of the database
 * @param modified whether that shader has changes that are not saved
 * @param title    its title
 */
public record OpenState(@NonNull Kind kind, @Nullable String key,
		@Nullable String path, boolean single, boolean tapped, float quality,
		long shader, boolean modified, @Nullable String title) {
	public enum Kind {
		/** No file, no shader, and the screen says so. */
		NOTHING,
		/** The start has not shown a file yet. */
		STARTING,
		/** As STARTING, and the screen says that the shaders move. */
		MOVING,
		/** A file of a folder. */
		FILE,
		/**
		 * A file that was asked for while the old editor was in use,
		 * to open once the screen is made again with the new one.
		 */
		ASKED,
		/** A shader of the database that was not moved. */
		NOT_MOVED
	}

	public static final OpenState NOTHING = new OpenState(Kind.NOTHING, null,
			null, false, false, 1f, 0, false, null);
	public static final OpenState STARTING = new OpenState(Kind.STARTING,
			null, null, false, false, 1f, 0, false, null);
	public static final OpenState MOVING = new OpenState(Kind.MOVING, null,
			null, false, false, 1f, 0, false, null);

	@NonNull
	public static OpenState file(@NonNull String key, @NonNull String path,
			boolean single, float quality) {
		return new OpenState(Kind.FILE, key, path, single, false, quality, 0,
				false, null);
	}

	/**
	 * Return this file as one whose tap is not settled yet: whether it
	 * runs is decided again where it comes back.
	 */
	@NonNull
	public OpenState undecided() {
		return new OpenState(kind, key, path, single, true, quality, shader,
				modified, title);
	}

	@NonNull
	public static OpenState asked(@NonNull String key, @NonNull String path,
			boolean single, boolean tapped) {
		return new OpenState(Kind.ASKED, key, path, single, tapped, 1f, 0,
				false, null);
	}

	@NonNull
	public static OpenState notMoved(long shader, @NonNull String title,
			float quality, boolean modified) {
		return new OpenState(Kind.NOT_MOVED, null, null, false, false,
				quality, shader, modified, title);
	}

	/**
	 * Return whether the start shows the file the library showed last:
	 * it had not come to that yet.
	 */
	public boolean showsLibrary() {
		return kind == Kind.STARTING || kind == Kind.MOVING;
	}

	/** Return whether a file of a folder is open or asked for. */
	public boolean isFile() {
		return kind == Kind.FILE || kind == Kind.ASKED;
	}

	/** Return this state as one text, which {@link #decode} reads. */
	@NonNull
	public String encode() {
		// One field a line, the title last: it alone may hold a line
		// end.
		return kind.name() + "\n" + (key != null ? key : "") + "\n" +
				(path != null ? path : "") + "\n" + single + "\n" + tapped +
				"\n" + quality + "\n" + shader + "\n" + modified + "\n" +
				(title != null ? title : "");
	}

	/**
	 * Return the state text was made of. For a text that is none, or
	 * that says nothing a screen can come back to, the start shows
	 * what the library shows, as when it starts cold.
	 */
	@NonNull
	public static OpenState decode(@Nullable String text) {
		String[] fields = text != null ? text.split("\n", 9) : new String[0];
		if (fields.length != 9) {
			return STARTING;
		}
		try {
			Kind kind = Kind.valueOf(fields[0]);
			OpenState state = new OpenState(kind,
					fields[1].isEmpty() ? null : fields[1],
					fields[2].isEmpty() ? null : fields[2],
					Boolean.parseBoolean(fields[3]),
					Boolean.parseBoolean(fields[4]),
					Float.parseFloat(fields[5]),
					Long.parseLong(fields[6]),
					Boolean.parseBoolean(fields[7]),
					fields[8]);
			return state.isFile() && (state.key == null ||
					state.path == null) ||
					kind == Kind.NOT_MOVED && state.shader <= 0
					? STARTING
					: state;
		} catch (IllegalArgumentException e) {
			return STARTING;
		}
	}
}
