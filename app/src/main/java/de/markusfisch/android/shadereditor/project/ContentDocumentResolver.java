package de.markusfisch.android.shadereditor.project;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks a documents provider through a ContentResolver, by the document
 * ids of a tree grant, or for the one document of a document grant.
 */
public final class ContentDocumentResolver implements DocumentResolver {
	private static final String[] COLUMNS = {
			DocumentsContract.Document.COLUMN_DOCUMENT_ID,
			DocumentsContract.Document.COLUMN_DISPLAY_NAME,
			DocumentsContract.Document.COLUMN_MIME_TYPE,
			DocumentsContract.Document.COLUMN_FLAGS,
			DocumentsContract.Document.COLUMN_SIZE,
			DocumentsContract.Document.COLUMN_LAST_MODIFIED,
	};

	private final ContentResolver resolver;
	private final Uri tree;
	private final Uri document;

	private ContentDocumentResolver(ContentResolver resolver, Uri tree,
			Uri document) {
		this.resolver = resolver;
		this.tree = tree;
		this.document = document;
	}

	/**
	 * Resolve the documents of a tree from ACTION_OPEN_DOCUMENT_TREE.
	 */
	public static ContentDocumentResolver forTree(ContentResolver resolver,
			Uri tree) {
		return new ContentDocumentResolver(resolver, tree, null);
	}

	/**
	 * Resolve the one document from ACTION_OPEN_DOCUMENT or an intent.
	 * Every id names that document.
	 */
	public static ContentDocumentResolver forDocument(
			ContentResolver resolver, Uri document) {
		return new ContentDocumentResolver(resolver, null, document);
	}

	@Override
	public Document document(String id) throws IOException {
		try (Cursor cursor = query(uri(id))) {
			if (!cursor.moveToFirst()) {
				throw new FileNotFoundException(id);
			}
			return document(cursor);
		}
	}

	@Override
	public List<Document> children(String folderId) throws IOException {
		if (tree == null) {
			throw new FileNotFoundException("a document has no children");
		}
		try (Cursor cursor = query(DocumentsContract
				.buildChildDocumentsUriUsingTree(tree, folderId))) {
			Bundle extras = cursor.getExtras();
			if (extras != null &&
					extras.getBoolean(DocumentsContract.EXTRA_LOADING)) {
				throw new IOException("the folder is still loading, try again");
			}
			List<Document> documents = new ArrayList<>();
			while (cursor.moveToNext()) {
				documents.add(document(cursor));
			}
			return documents;
		}
	}

	@Override
	public byte[] read(String id, long limit) throws IOException {
		try (InputStream in = resolver.openInputStream(uri(id))) {
			if (in == null) {
				throw new FileNotFoundException(id);
			}
			return DirectoryProjectFiles.readBytes(in, 8192, limit);
		}
	}

	@Override
	public void write(String id, byte[] bytes) throws IOException {
		try (OutputStream out = resolver.openOutputStream(uri(id), "wt")) {
			if (out == null) {
				throw new FileNotFoundException(id);
			}
			out.write(bytes);
		}
	}

	@Override
	public String create(String folderId, String name, boolean folder)
			throws IOException {
		// A file type would make some providers append its extension.
		return id(DocumentsContract.createDocument(resolver, uri(folderId),
				folder
						? DocumentsContract.Document.MIME_TYPE_DIR
						: "application/octet-stream",
				name), "cannot create " + name);
	}

	@Override
	public String rename(String id, String name) throws IOException {
		return id(DocumentsContract.renameDocument(resolver, uri(id), name),
				"cannot rename to " + name);
	}

	@Override
	public String move(String id, String fromFolderId, String toFolderId)
			throws IOException {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			throw new IOException("moving needs Android 7");
		}
		return id(DocumentsContract.moveDocument(resolver, uri(id),
				uri(fromFolderId), uri(toFolderId)), "cannot move");
	}

	@Override
	public void delete(String id) throws IOException {
		if (!DocumentsContract.deleteDocument(resolver, uri(id))) {
			throw new IOException("cannot delete");
		}
	}

	private Uri uri(String id) {
		return tree != null
				? DocumentsContract.buildDocumentUriUsingTree(tree, id)
				: document;
	}

	private Cursor query(Uri uri) throws IOException {
		Cursor cursor = resolver.query(uri, COLUMNS, null, null, null);
		if (cursor == null) {
			throw new IOException("the storage did not answer");
		}
		return cursor;
	}

	private static Document document(Cursor cursor) {
		String name = cursor.getString(1);
		return new Document(cursor.getString(0), name != null ? name : "",
				DocumentsContract.Document.MIME_TYPE_DIR.equals(
						cursor.getString(2)),
				cursor.getInt(3), known(cursor, 4), known(cursor, 5));
	}

	private static long known(Cursor cursor, int column) {
		return cursor.isNull(column)
				? ProjectFiles.UNKNOWN
				: cursor.getLong(column);
	}

	private static String id(Uri uri, String failure) throws IOException {
		if (uri == null) {
			throw new IOException(failure);
		}
		return DocumentsContract.getDocumentId(uri);
	}
}
