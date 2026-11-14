package de.markusfisch.android.shadereditor.navigation;

/**
 * Where a path of a folder opened before is after a file or folder
 * was renamed or deleted; paths are from the folder's root.
 */
public final class Moves {
	private Moves() {
	}

	/** Whether path is at or under the file or folder at. */
	public static boolean within(String path, String at) {
		return path.equals(at) || path.startsWith(at + "/");
	}

	/**
	 * Where path is after from was renamed to to: under to when it was
	 * at or under from, else where it was.
	 */
	public static String renamed(String path, String from, String to) {
		return within(path, from)
				? to + path.substring(from.length())
				: path;
	}
}
