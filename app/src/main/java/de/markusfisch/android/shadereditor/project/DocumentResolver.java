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
	 * whether it is a folder, and its DocumentsContract.Document flags.
	 */
	record Document(String id, String name, boolean folder, int flags) {
	}

	Document document(String id) throws IOException;

	List<Document> children(String folderId) throws IOException;

	byte[] read(String id) throws IOException;

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
