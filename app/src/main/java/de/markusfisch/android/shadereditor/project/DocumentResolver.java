package de.markusfisch.android.shadereditor.project;

import java.io.IOException;
import java.util.List;

/**
 * The calls a documents provider answers for one grant, by document
 * id. Every call asks the provider again. A document that does not
 * exist throws a {@link java.io.FileNotFoundException}, lost permission
 * a {@link SecurityException}.
 */
public interface DocumentResolver {
	/**
	 * A document as the provider describes it: its id, display name,
	 * whether it is a folder, its DocumentsContract.Document flags, its
	 * size in bytes and when it last changed, in milliseconds since the
	 * epoch, or {@link ProjectFiles#UNKNOWN} when the provider does not
	 * tell.
	 */
	record Document(String id, String name, boolean folder, int flags,
			long size, long modified) {
		public Document(String id, String name, boolean folder, int flags) {
			this(id, name, folder, flags, ProjectFiles.UNKNOWN,
					ProjectFiles.UNKNOWN);
		}
	}

	Document document(String id) throws IOException;

	List<Document> children(String folderId) throws IOException;

	default byte[] read(String id) throws IOException {
		return read(id, Long.MAX_VALUE);
	}

	/**
	 * Return the content of a file, or null as soon as it has more than
	 * limit bytes, whatever its size said.
	 */
	byte[] read(String id, long limit) throws IOException;

	/** Return at most the first count bytes of document id. */
	default byte[] head(String id, int count) throws IOException {
		byte[] bytes = read(id);
		return java.util.Arrays.copyOf(bytes,
				Math.min(count, bytes.length));
	}

	/**
	 * Replace the content of a file.
	 */
	void write(String id, byte[] bytes) throws IOException;

	/**
	 * Create an empty file or folder and return its id. The provider
	 * may store it under another name.
	 */
	String create(String folderId, String name, boolean folder)
			throws IOException;

	/**
	 * Rename a document in its folder and return its id afterwards.
	 */
	String rename(String id, String name) throws IOException;

	/**
	 * Move a document to another folder and return its id afterwards.
	 */
	String move(String id, String fromFolderId, String toFolderId)
			throws IOException;

	/**
	 * Delete a document, a folder with everything in it.
	 */
	void delete(String id) throws IOException;
}
