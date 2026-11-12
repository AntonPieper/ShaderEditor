package de.markusfisch.android.shadereditor.project;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;
import de.markusfisch.android.shadereditor.provider.UserFilesProvider;

/**
 * Opens a folder or a lone document the system picker granted, and
 * keeps those grants across restarts. A folder and a document stay
 * separate projects; a document never borrows a folder's grant. A
 * folder of ShaderEditor's own provider opens as its directory, not
 * through the provider. Open off the main thread.
 */
public final class SafProjects {
	private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
	private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

	/**
	 * An opened project: its files, or null and the problem that keeps
	 * it closed. file is the path of a lone document, null for a
	 * folder.
	 */
	public record Opened(ProjectFiles files, ProjectPath file,
			String problem) {
	}

	private SafProjects() {
	}

	/**
	 * Keep the grant of uri from a picker result with resultFlags
	 * across restarts, when the picker offered that. Return whether it
	 * is kept.
	 */
	public static boolean keepGrant(ContentResolver resolver, Uri uri,
			int resultFlags) {
		int modes = persistableModes(resultFlags);
		if (modes == 0) {
			return false;
		}
		try {
			resolver.takePersistableUriPermission(uri, modes);
			return true;
		} catch (SecurityException e) {
			return false;
		}
	}

	/**
	 * Return the modes of a picker result's grant that can be kept, 0
	 * when the picker offered none.
	 */
	static int persistableModes(int resultFlags) {
		return (resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
				? resultFlags & (READ | WRITE)
				: 0;
	}

	/**
	 * Give up the kept grant of uri, if there is one.
	 */
	public static void releaseGrant(ContentResolver resolver, Uri uri) {
		for (UriPermission permission :
				resolver.getPersistedUriPermissions()) {
			if (permission.getUri().equals(uri)) {
				try {
					resolver.releasePersistableUriPermission(uri,
							(permission.isReadPermission() ? READ : 0) |
									(permission.isWritePermission() ? WRITE : 0));
				} catch (SecurityException e) {
					// Already released.
				}
			}
		}
	}

	/**
	 * Return the locator of a tree from ACTION_OPEN_DOCUMENT_TREE, the
	 * directory by its canonical path for a folder of ShaderEditor's own
	 * provider, or null when that folder does not exist.
	 */
	public static Locator locate(Context context, Uri tree) {
		if (!UserFilesProvider.AUTHORITY.equals(tree.getAuthority())) {
			return new Locator(Kind.TREE, tree.toString());
		}
		try {
			return new Locator(Kind.DIRECTORY, new ProviderDocuments(
					UserFilesProvider.getUserFilesDir(context)).file(
					DocumentsContract.getTreeDocumentId(tree))
					.getCanonicalPath());
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Open the folder of locator, telling whether it is still there.
	 */
	public static Opened open(Context context, Locator locator) {
		if (locator.kind() == Kind.TREE) {
			return openFolder(context, Uri.parse(locator.value()));
		} else if (locator.kind() != Kind.DIRECTORY) {
			return closed("only folders open here");
		}
		File dir = new File(locator.value());
		return dir.isDirectory()
				? new Opened(new DirectoryProjectFiles(dir), null, null)
				: closed("this folder is no longer available");
	}

	/**
	 * Return the name of the folder of locator and where it is, to tell
	 * it apart in lists; null when it cannot be reached.
	 */
	public static String[] describe(Context context, Locator locator) {
		if (locator.kind() == Kind.DIRECTORY) {
			File dir;
			File root;
			try {
				dir = new File(locator.value()).getCanonicalFile();
				root = UserFilesProvider.getUserFilesDir(context)
						.getCanonicalFile();
			} catch (IOException e) {
				return null;
			}
			StringBuilder location = new StringBuilder(
					UserFilesProvider.TITLE);
			for (File parent = dir.getParentFile();
					parent != null && !parent.equals(root);
					parent = parent.getParentFile()) {
				location.insert(UserFilesProvider.TITLE.length(),
						" / " + parent.getName());
			}
			return dir.equals(root)
					? new String[]{UserFilesProvider.TITLE, ""}
					: new String[]{dir.getName(), location.toString()};
		}
		Uri tree = Uri.parse(locator.value());
		try {
			String name = ContentDocumentResolver.forTree(
					context.getContentResolver(), tree).document(
					DocumentsContract.getTreeDocumentId(tree)).name();
			PackageManager manager = context.getPackageManager();
			ProviderInfo provider = manager.resolveContentProvider(
					tree.getAuthority(), 0);
			return new String[]{name, provider != null
					? provider.loadLabel(manager).toString()
					: tree.getAuthority()};
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Open a tree from ACTION_OPEN_DOCUMENT_TREE, telling whether its
	 * grant is still valid.
	 */
	public static Opened openFolder(Context context, Uri tree) {
		String id = DocumentsContract.getTreeDocumentId(tree);
		if (UserFilesProvider.AUTHORITY.equals(tree.getAuthority())) {
			try {
				File dir = new ProviderDocuments(
						UserFilesProvider.getUserFilesDir(context)).file(id);
				return dir.isDirectory()
						? new Opened(new DirectoryProjectFiles(dir), null, null)
						: closed("it is not a folder");
			} catch (FileNotFoundException e) {
				return closed("this folder is no longer available");
			}
		} else if (!granted(context, tree, READ)) {
			return closed(SafProjectFiles.LOST_FOLDER);
		}
		SafProjectFiles files = new SafProjectFiles(
				ContentDocumentResolver.forTree(context.getContentResolver(),
						tree), id, granted(context, tree, WRITE));
		String problem = files.check();
		return problem == null ? new Opened(files, null, null) : closed(problem);
	}

	/**
	 * Open a document from ACTION_OPEN_DOCUMENT or an intent as a
	 * one-file project, telling whether its grant is still valid.
	 */
	public static Opened openDocument(Context context, Uri uri) {
		if (!granted(context, uri, READ)) {
			return closed(DocumentProjectFiles.LOST_FILE);
		}
		DocumentResolver resolver = ContentDocumentResolver.forDocument(
				context.getContentResolver(), uri);
		try {
			String id = DocumentsContract.getDocumentId(uri);
			DocumentProjectFiles files = new DocumentProjectFiles(resolver, id,
					resolver.document(id).name(), granted(context, uri, WRITE));
			return new Opened(files, files.getPath(), null);
		} catch (IOException | RuntimeException e) {
			return closed(SafProjectFiles.reason(e,
					DocumentProjectFiles.LOST_FILE));
		}
	}

	private static boolean granted(Context context, Uri uri, int mode) {
		return context.checkUriPermission(uri, Process.myPid(),
				Process.myUid(), mode) == PackageManager.PERMISSION_GRANTED;
	}

	private static Opened closed(String problem) {
		return new Opened(null, null, problem);
	}
}
