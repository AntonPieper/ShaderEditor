package de.markusfisch.android.shadereditor.navigation;

/**
 * One list the drawer can show, and the level its arrow leads up to.
 * The drawer has one column: a tap goes into a level, the arrow goes
 * out of it.
 */
public record Level(String name, Level up) {
	/** The top: the library, the examples and later the folders. */
	public static final Level FOLDERS = new Level("folders", null);
	/** The library of the user's shaders. */
	public static final Level LIBRARY = new Level("library", FOLDERS);
	/** The bundled examples. */
	public static final Level EXAMPLES = new Level("examples", FOLDERS);

	private static final String FOLDER = "folder:";

	/** The root of a folder opened before, known to its source by key. */
	public static Level root(String key) {
		return new Level(FOLDER + key, FOLDERS);
	}

	/**
	 * The level a name of {@link #name()} stands for, or null when it
	 * stands for none.
	 */
	public static Level of(String name) {
		for (Level level : new Level[]{FOLDERS, LIBRARY, EXAMPLES}) {
			if (level.name.equals(name)) {
				return level;
			}
		}
		if (name == null || !name.startsWith(FOLDER)) {
			return null;
		}
		String[] parts = name.substring(FOLDER.length()).split("/", -1);
		Level level = null;
		for (String part : parts) {
			if (part.isEmpty()) {
				return null;
			}
			level = level == null ? root(part) : level.enter(part);
		}
		return level;
	}

	/** The folder child in this folder. */
	public Level enter(String child) {
		return new Level(name + "/" + child, this);
	}

	/**
	 * The key of the folder opened before this level is in, or null
	 * when it is in none.
	 */
	public String key() {
		if (!name.startsWith(FOLDER)) {
			return null;
		}
		int slash = name.indexOf('/');
		return name.substring(FOLDER.length(),
				slash < 0 ? name.length() : slash);
	}

	/**
	 * The path of this folder from the root of the folder opened
	 * before, or null for that root and for levels outside of it.
	 */
	public String path() {
		int slash = name.startsWith(FOLDER) ? name.indexOf('/') : -1;
		return slash < 0 ? null : name.substring(slash + 1);
	}

	/** Whether this level is below the other one, at any depth. */
	public boolean isBelow(Level other) {
		for (Level level = up; level != null; level = level.up) {
			if (level.equals(other)) {
				return true;
			}
		}
		return false;
	}
}
