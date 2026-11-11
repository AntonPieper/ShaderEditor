package de.markusfisch.android.shadereditor.project;

/**
 * Storage of one opened project root, keyed by logical path.
 */
public interface ProjectFiles {
	/**
	 * What reading a file found: {@link Content} or {@link Failed}.
	 */
	interface Read {
	}

	/**
	 * The bytes of a file and an opaque revision that changes whenever
	 * the bytes change.
	 */
	record Content(byte[] bytes, String revision) implements Read {
	}

	/**
	 * A file that could not be read, for example because permission was
	 * lost; reason tells the user why.
	 */
	record Failed(String reason) implements Read {
	}

	/**
	 * Read a file: its content, why it could not be read, or null when
	 * there is no file at path.
	 */
	Read read(ProjectPath path);
}
