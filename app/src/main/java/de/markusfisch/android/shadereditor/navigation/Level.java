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
