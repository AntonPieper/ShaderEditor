package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The documents a documents provider exports from one directory, by
 * document id. An id is {@link #ROOT} or that followed by '/' and the
 * project path of the document, spelled exactly as
 * {@link ProjectPath} normalizes it; any other string is refused.
 * Documents resolve through {@link DirectoryProjectFiles}, so links,
 * names that normalize alike and version-control folders are refused,
 * and a resolved file must lie inside the root. Every refusal is a
 * {@link FileNotFoundException}, as documents providers report it.
 */
public final class ProviderDocuments {
	public static final String ROOT = "root";

	private static final String PREFIX = ROOT + "/";
	private static final int NUMBERED_NAMES = 100;

	private final DirectoryProjectFiles files;

	public ProviderDocuments(File root) {
		files = new DirectoryProjectFiles(root);
	}

	/**
	 * Return the id of the folder that holds the document id.
	 */
	public static String parent(String id) throws FileNotFoundException {
		if (path(id) == null) {
			throw refused(id, "the root has no parent");
		}
		return id.substring(0, id.lastIndexOf('/'));
	}

	/**
	 * Return the existing file or folder of an id, inside the root.
	 */
	public File file(String id) throws FileNotFoundException {
		ProjectPath path = path(id);
		File file;
		try {
			file = path != null ? files.find(path) : files.root();
		} catch (IOException e) {
			throw refused(id, e.getMessage());
		}
		if (file == null) {
			throw refused(id, "there is no such document");
		}
		return contained(id, file);
	}

	/**
	 * Return the documents in a folder: their ids and files. Links and
	 * names that are no project path, like version-control folders, are
	 * left out.
	 */
	public Map<String, File> children(String parentId)
			throws FileNotFoundException {
		File folder = file(parentId);
		String[] names = folder.list();
		if (names == null) {
			throw refused(parentId, "it is not a folder");
		}
		Map<String, File> children = new LinkedHashMap<>();
		for (String name : names) {
			try {
				File file = new File(folder, name);
				if (!DirectoryProjectFiles.isLink(file)) {
					children.put(child(parentId, name), file);
				}
			} catch (IOException e) {
				// Not a document.
			}
		}
		return children;
	}

	/**
	 * Return whether the document id is inside the folder parentId,
	 * deciding on the resolved files.
	 */
	public boolean isChild(String parentId, String id) {
		try {
			File folder = file(parentId);
			for (File file = file(id).getParentFile(); file != null;
					file = file.getParentFile()) {
				if (file.equals(folder)) {
					return true;
				}
			}
		} catch (FileNotFoundException e) {
			// Neither is a document.
		}
		return false;
	}

	/**
	 * Create an empty file or folder named name in parentId, numbering
	 * the name when it is taken, and return its id.
	 */
	public String create(String parentId, String name, boolean folder)
			throws FileNotFoundException {
		if (!file(parentId).isDirectory()) {
			throw refused(parentId, "it is not a folder");
		}
		String id = child(parentId, name);
		for (int i = 1; ; ++i) {
			ProjectPath path = path(id);
			ProjectFiles.Change change = folder
					? files.createFolder(path)
					: files.write(path, new byte[0], null);
			if (change instanceof ProjectFiles.Done) {
				return id;
			}
			ProjectFiles.Refused refused = (ProjectFiles.Refused) change;
			if (i >= NUMBERED_NAMES || (refused.refusal() !=
					ProjectFiles.Refusal.EXISTS && refused.refusal() !=
					ProjectFiles.Refusal.CONFLICT)) {
				throw refused(id, refused.reason());
			}
			id = child(parentId, numbered(name, i, folder));
		}
	}

	/**
	 * Rename a document within its folder and return its new id.
	 */
	public String rename(String id, String name) throws FileNotFoundException {
		String target = child(parent(id), name);
		check(id, files.rename(path(id), path(target)));
		return target;
	}

	/**
	 * Delete a document, a folder with everything in it.
	 */
	public void delete(String id) throws FileNotFoundException {
		parent(id);
		check(id, files.delete(path(id)));
	}

	// Return the path of an id, null for the root.
	private static ProjectPath path(String id) throws FileNotFoundException {
		if (ROOT.equals(id)) {
			return null;
		} else if (id == null || !id.startsWith(PREFIX)) {
			throw refused(id, "malformed document id");
		}
		String text = id.substring(PREFIX.length());
		ProjectParseResult<ProjectPath> path = ProjectPath.parse(text);
		if (!path.isSuccess() || !path.value().toString().equals(text)) {
			throw refused(id, "malformed document id");
		}
		return path.value();
	}

	private static String child(String parentId, String name)
			throws FileNotFoundException {
		if (name == null || name.indexOf('/') > -1) {
			throw refused(parentId, "invalid name " + name);
		}
		String id = (path(parentId) == null ? ROOT : parentId) + "/" +
				Normalizer.normalize(name, Normalizer.Form.NFC);
		path(id);
		return id;
	}

	private static String numbered(String name, int number, boolean folder) {
		int dot = folder ? -1 : name.lastIndexOf('.');
		return dot > 0
				? name.substring(0, dot) + " (" + number + ")" +
						name.substring(dot)
				: name + " (" + number + ")";
	}

	private File contained(String id, File file) throws FileNotFoundException {
		try {
			File root = files.root();
			File canonical = file.getCanonicalFile();
			for (File f = canonical; f != null; f = f.getParentFile()) {
				if (f.equals(root)) {
					return canonical;
				}
			}
		} catch (IOException e) {
			throw refused(id, e.getMessage());
		}
		throw refused(id, "it is outside the user files");
	}

	private static void check(String id, ProjectFiles.Change change)
			throws FileNotFoundException {
		if (change instanceof ProjectFiles.Refused refused) {
			throw refused(id, refused.reason());
		}
	}

	private static FileNotFoundException refused(String id, String reason) {
		return new FileNotFoundException(id + ": " + reason);
	}
}
