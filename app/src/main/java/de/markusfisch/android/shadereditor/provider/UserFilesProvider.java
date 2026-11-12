package de.markusfisch.android.shadereditor.provider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import de.markusfisch.android.shadereditor.BuildConfig;
import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.project.ProviderDocuments;

/**
 * Exports the user files directory to other apps and to the Files app
 * as the root "ShaderEditor". The documents and every refusal come
 * from {@link ProviderDocuments}.
 */
public class UserFilesProvider extends DocumentsProvider {
	public static final String AUTHORITY = BuildConfig.APPLICATION_ID +
			".documents";

	private static final String ROOT_ID = "shadereditor";
	public static final String TITLE = "ShaderEditor";
	private static final String[] ROOT_COLUMNS = {
			Root.COLUMN_ROOT_ID,
			Root.COLUMN_DOCUMENT_ID,
			Root.COLUMN_TITLE,
			Root.COLUMN_SUMMARY,
			Root.COLUMN_FLAGS,
			Root.COLUMN_ICON,
			Root.COLUMN_AVAILABLE_BYTES,
	};
	private static final String[] DOCUMENT_COLUMNS = {
			Document.COLUMN_DOCUMENT_ID,
			Document.COLUMN_DISPLAY_NAME,
			Document.COLUMN_MIME_TYPE,
			Document.COLUMN_FLAGS,
			Document.COLUMN_SIZE,
			Document.COLUMN_LAST_MODIFIED,
	};

	private ProviderDocuments documents;

	/**
	 * Return the directory with the user's own files. It is removed with
	 * the app and holds nothing but those files.
	 */
	@NonNull
	public static File getUserFilesDir(@NonNull Context context) {
		return new File(context.getFilesDir(), "user-files");
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	// getAllocatableBytes() needs API 26.
	@SuppressLint("UsableSpace")
	@Override
	public Cursor queryRoots(String[] projection) {
		Context context = context();
		MatrixCursor result = new MatrixCursor(projection != null
				? projection
				: ROOT_COLUMNS);
		result.newRow()
				.add(Root.COLUMN_ROOT_ID, ROOT_ID)
				.add(Root.COLUMN_DOCUMENT_ID, ProviderDocuments.ROOT)
				.add(Root.COLUMN_TITLE, TITLE)
				.add(Root.COLUMN_SUMMARY,
						context.getString(R.string.user_files_summary))
				.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE |
						Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY)
				.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
				.add(Root.COLUMN_AVAILABLE_BYTES,
						context.getFilesDir().getUsableSpace());
		return result;
	}

	@Override
	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		MatrixCursor result = documentCursor(projection);
		addRow(result, documentId, documents().file(documentId));
		return result;
	}

	@Override
	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection, String sortOrder)
			throws FileNotFoundException {
		MatrixCursor result = documentCursor(projection);
		for (Map.Entry<String, File> child :
				documents().children(parentDocumentId).entrySet()) {
			addRow(result, child.getKey(), child.getValue());
		}
		result.setNotificationUri(context().getContentResolver(),
				childrenUri(parentDocumentId));
		return result;
	}

	@Override
	public ParcelFileDescriptor openDocument(String documentId, String mode,
			@Nullable CancellationSignal signal)
			throws FileNotFoundException {
		File file = documents().file(documentId);
		if (file.isDirectory()) {
			throw new FileNotFoundException(documentId + ": it is a folder");
		}
		int access = ParcelFileDescriptor.parseMode(mode);
		if (access == ParcelFileDescriptor.MODE_READ_ONLY) {
			return ParcelFileDescriptor.open(file, access);
		}
		String parentId = ProviderDocuments.parent(documentId);
		try {
			return ParcelFileDescriptor.open(file, access,
					new Handler(Looper.getMainLooper()),
					e -> notifyChildren(parentId));
		} catch (IOException e) {
			throw new FileNotFoundException(documentId + ": " +
					e.getMessage());
		}
	}

	@Override
	public String createDocument(String parentDocumentId, String mimeType,
			String displayName) throws FileNotFoundException {
		String id = documents().create(parentDocumentId, displayName,
				Document.MIME_TYPE_DIR.equals(mimeType));
		notifyChildren(parentDocumentId);
		return id;
	}

	@Override
	public String renameDocument(String documentId, String displayName)
			throws FileNotFoundException {
		String id = documents().rename(documentId, displayName);
		notifyChildren(ProviderDocuments.parent(documentId));
		return id.equals(documentId) ? null : id;
	}

	@Override
	public void deleteDocument(String documentId)
			throws FileNotFoundException {
		ProviderDocuments documents = documents();
		String parentId = ProviderDocuments.parent(documentId);
		if (documents.file(documentId).isDirectory()) {
			revokeChildren(documents, documentId);
		}
		documents.delete(documentId);
		notifyChildren(parentId);
	}

	@Override
	public boolean isChildDocument(String parentDocumentId,
			String documentId) {
		try {
			return documents().isChild(parentDocumentId, documentId);
		} catch (FileNotFoundException e) {
			return false;
		}
	}

	@NonNull
	private Context context() {
		Context context = getContext();
		if (context == null) {
			throw new IllegalStateException("provider is not attached");
		}
		return context;
	}

	// Create the directory on first use, off the main thread.
	@NonNull
	private synchronized ProviderDocuments documents()
			throws FileNotFoundException {
		if (documents == null) {
			File dir = getUserFilesDir(context());
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new FileNotFoundException("cannot create " + dir);
			}
			documents = new ProviderDocuments(dir);
		}
		return documents;
	}

	private void revokeChildren(ProviderDocuments documents, String id)
			throws FileNotFoundException {
		for (Map.Entry<String, File> child :
				documents.children(id).entrySet()) {
			if (child.getValue().isDirectory()) {
				revokeChildren(documents, child.getKey());
			}
			revokeDocumentPermission(child.getKey());
		}
	}

	private void notifyChildren(String parentId) {
		context().getContentResolver().notifyChange(childrenUri(parentId),
				null, false);
	}

	private static Uri childrenUri(String parentId) {
		return DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId);
	}

	private static MatrixCursor documentCursor(String[] projection) {
		return new MatrixCursor(projection != null
				? projection
				: DOCUMENT_COLUMNS);
	}

	private static void addRow(MatrixCursor result, String id, File file) {
		boolean root = ProviderDocuments.ROOT.equals(id);
		String name = root ? TITLE : id.substring(id.lastIndexOf('/') + 1);
		boolean folder = file.isDirectory();
		int flags = folder
				? Document.FLAG_DIR_SUPPORTS_CREATE
				: Document.FLAG_SUPPORTS_WRITE;
		if (!root) {
			flags |= Document.FLAG_SUPPORTS_DELETE |
					Document.FLAG_SUPPORTS_RENAME;
		}
		result.newRow()
				.add(Document.COLUMN_DOCUMENT_ID, id)
				.add(Document.COLUMN_DISPLAY_NAME, name)
				.add(Document.COLUMN_MIME_TYPE, folder
						? Document.MIME_TYPE_DIR
						: mimeType(name))
				.add(Document.COLUMN_FLAGS, flags)
				.add(Document.COLUMN_SIZE, folder ? null : file.length())
				.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
	}

	// Shader files are plain text, so every text editor can open them.
	private static String mimeType(String name) {
		int dot = name.lastIndexOf('.');
		if (dot > -1) {
			String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
			if ("glsl".equals(extension)) {
				return "text/plain";
			}
			String type = MimeTypeMap.getSingleton()
					.getMimeTypeFromExtension(extension);
			if (type != null) {
				return type;
			}
		}
		return "application/octet-stream";
	}
}
