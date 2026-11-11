package de.markusfisch.android.shadereditor.project;

/**
 * Storage of one opened project root, keyed by logical path.
 */
public interface ProjectFiles {
	/**
	 * The bytes of a file and an opaque revision that changes whenever
	 * the bytes change.
	 */
	record Content(byte[] bytes, String revision) {
	}

	/**
	 * Read a file, or return null when there is no file at path.
	 */
	Content read(ProjectPath path);
}
