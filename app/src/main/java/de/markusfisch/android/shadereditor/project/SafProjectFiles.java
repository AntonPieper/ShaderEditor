package de.markusfisch.android.shadereditor.project;

import android.annotation.SuppressLint;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.DocumentResolver.Document;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * A project root that is a folder granted through the Storage Access
 * Framework. Opening it reads nothing. A path resolves by matching names
 * exactly in Unicode NFC in the listings of the folders on the way; two
 * entries with one name fail. A listing is kept, so opening what was
 * listed is one call, until {@link #forgetListings()}, a rename or a
 * delete; a name it lacks and a document that is gone are looked up
 * again. A document renamed elsewhere can be found under its old name
 * until then. Changes always look up their path again. A change
 * happens only where the grant allows writing and the document flags
 * offer it; on a root that offers no change at all, every change is
 * unsupported. Revisions are content
 * digests, because provider metadata need not change with the bytes.
 * Checking a revision and writing are not one step against other
 * writers. Use it off the main thread.
 */
public final class SafProjectFiles implements ProjectFiles {
	static final String LOST_FOLDER =
			"ShaderEditor lost access to this folder, choose it again";

	/**
	 * Map the flags of a document to the changes it offers; nothing is
	 * offered without a writable grant.
	 */
	@SuppressLint("InlinedApi")
	public static Offers offers(Document document, boolean writable) {
		if (!writable) {
			return Offers.NONE;
		}
		int flags = document.flags();
		return new Offers(
				!document.folder() && has(flags,
						DocumentsContract.Document.FLAG_SUPPORTS_WRITE),
				document.folder() && has(flags,
						DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE),
				has(flags, DocumentsContract.Document.FLAG_SUPPORTS_RENAME),
				has(flags, DocumentsContract.Document.FLAG_SUPPORTS_MOVE),
				has(flags, DocumentsContract.Document.FLAG_SUPPORTS_DELETE));
	}

	// What the last listing of a folder found: the folder, and its
	// children by name, TWICE for a name that two children have.
	private record Listed(Document folder, Map<String, Document> children) {
	}

	private static final Document TWICE = new Document("", "", false, 0);

	// By the path of the folder, "" for the root.
	private final Map<String, Listed> listings = new HashMap<>();
	private final DocumentResolver resolver;
	private final String rootId;
	private final boolean writable;

	/**
	 * Open the folder rootId with a grant that only reads.
	 */
	public SafProjectFiles(DocumentResolver resolver, String rootId) {
		this(resolver, rootId, false);
	}

	/**
	 * Open the folder rootId; writable tells whether the grant allows
	 * writing.
	 */
	public SafProjectFiles(DocumentResolver resolver, String rootId,
			boolean writable) {
		this.resolver = resolver;
		this.rootId = rootId;
		this.writable = writable;
	}

	/**
	 * Return why the folder cannot be used, or null when it can.
	 */
	public String check() {
		try {
			return resolver.document(rootId).folder()
					? null
					: "it is not a folder";
		} catch (IOException | RuntimeException e) {
			return reason(e, LOST_FOLDER);
		}
	}

	/**
	 * Return the changes the document at path, the root when path is
	 * null, offers; none when there is no such document. A listed
	 * document needs no call, the root one.
	 */
	@Override
	public Offers offers(ProjectPath path) {
		try {
			Document document = path != null
					? known(segments(path))
					: resolver.document(rootId);
			return document != null
					? offers(document, writable)
					: Offers.NONE;
		} catch (IOException | RuntimeException e) {
			return Offers.NONE;
		}
	}

	@Override
	public Read read(ProjectPath path) {
		// A kept listing may name a document that is gone.
		for (boolean retry = remembers(); ; retry = false) {
			try {
				Document file = known(segments(path));
				if (file == null) {
					return null;
				} else if (file.folder()) {
					return new Failed("it is a folder");
				}
				byte[] bytes = resolver.read(file.id());
				return new Content(bytes, revision(bytes));
			} catch (IOException | RuntimeException e) {
				if (!retry) {
					return new Failed(reason(e, LOST_FOLDER));
				}
				forgetListings();
			} catch (OutOfMemoryError e) {
				return new Failed("it is too large");
			}
		}
	}

	@Override
	public Listing list(ProjectPath folder) {
		for (boolean retry = remembers(); ; retry = false) {
			try {
				return listOnce(folder != null
						? segments(folder)
						: new String[0]);
			} catch (IOException | RuntimeException e) {
				if (!retry) {
					return new Failed(reason(e, LOST_FOLDER));
				}
				forgetListings();
			}
		}
	}

	@Override
	public void forgetListings() {
		synchronized (listings) {
			listings.clear();
		}
	}

	private Listing listOnce(String[] segments) throws IOException {
		Document dir = known(segments);
		if (dir == null) {
			return null;
		} else if (!dir.folder()) {
			return new Failed("it is not a folder");
		}
		List<Entry> entries = new ArrayList<>();
		for (Document child : listChildren(key(segments, segments.length),
				dir)) {
			entries.add(new Entry(nfc(child.name()), child.folder(),
					child.folder() ? UNKNOWN : child.size(),
					child.modified()));
		}
		return new Entries(entries);
	}

	@Override
	public Change write(ProjectPath path, byte[] bytes,
			String expectedRevision) {
		try {
			Document root = changeableRoot();
			String[] segments = segments(path);
			Document file = find(root, segments);
			if (file != null) {
				if (file.folder()) {
					throw new Problem(Refusal.FAILED, "it is a folder");
				} else if (!offers(file, writable).write()) {
					throw new Problem(Refusal.UNSUPPORTED,
							"the storage cannot change " + path);
				} else if (!revision(resolver.read(file.id())).equals(
						expectedRevision)) {
					throw new Problem(Refusal.CONFLICT,
							expectedRevision == null
									? "the file exists"
									: "the file has changed");
				}
				resolver.write(file.id(), bytes);
			} else if (expectedRevision != null) {
				throw new Problem(Refusal.CONFLICT, "the file was deleted");
			} else {
				int last = segments.length - 1;
				String id = create(folders(root, segments, last),
						segments[last], false).id();
				try {
					resolver.write(id, bytes);
				} catch (IOException | RuntimeException e) {
					deleteQuietly(id);
					throw e;
				}
			}
			return new Done(revision(bytes));
		} catch (IOException | RuntimeException e) {
			return refused(e);
		}
	}

	@Override
	public Change createFolder(ProjectPath folder) {
		try {
			Document root = changeableRoot();
			String[] segments = segments(folder);
			if (find(root, segments) != null) {
				throw new Problem(Refusal.EXISTS, "it exists");
			}
			folders(root, segments, segments.length);
			return new Done(null);
		} catch (IOException | RuntimeException e) {
			return refused(e);
		} finally {
			forgetListings();
		}
	}

	@Override
	public Change rename(ProjectPath from, ProjectPath to) {
		try {
			Document root = changeableRoot();
			String[] source = segments(from);
			String[] target = segments(to);
			Document parent = find(root, source, source.length - 1);
			Document document = parent != null && parent.folder()
					? child(parent, source[source.length - 1])
					: null;
			if (document == null) {
				throw new Problem(Refusal.ABSENT, "there is no " + from);
			} else if (from.equals(to)) {
				return new Done(null);
			} else if (to.toString().startsWith(from + "/")) {
				throw new Problem(Refusal.FAILED,
						"a folder cannot move into itself");
			} else if (find(root, target) != null) {
				throw new Problem(Refusal.EXISTS, to + " exists");
			}
			String name = target[target.length - 1];
			boolean move = !folderOf(from).equals(folderOf(to));
			boolean rename = !nfc(document.name()).equals(name);
			Offers offers = offers(document, writable);
			if ((move && !offers.move()) || (rename && !offers.rename())) {
				throw new Problem(Refusal.UNSUPPORTED, "the storage cannot " +
						(move ? "move " : "rename ") + from);
			}
			String id = document.id();
			if (move) {
				id = resolver.move(id, parent.id(), folders(root, target,
						target.length - 1).id());
			}
			if (rename) {
				id = resolver.rename(id, name);
				expectName(resolver.document(id), name);
			}
			return new Done(null);
		} catch (IOException | RuntimeException e) {
			return refused(e);
		} finally {
			forgetListings();
		}
	}

	@Override
	public Change delete(ProjectPath path) {
		try {
			Document document = find(changeableRoot(), segments(path));
			if (document == null) {
				throw new Problem(Refusal.ABSENT, "there is no " + path);
			} else if (!offers(document, writable).delete()) {
				throw new Problem(Refusal.UNSUPPORTED,
						"the storage cannot delete " + path);
			}
			resolver.delete(document.id());
			return new Done(null);
		} catch (IOException | RuntimeException e) {
			return refused(e);
		} finally {
			forgetListings();
		}
	}

	/**
	 * Return the reason a user reads for a failed provider call;
	 * lostAccess when permission was lost.
	 */
	static String reason(Exception e, String lostAccess) {
		String message = e.getMessage();
		if (e instanceof Problem) {
			return message;
		} else if (e instanceof SecurityException) {
			return lostAccess;
		} else if (e instanceof FileNotFoundException) {
			return "it is no longer available";
		}
		return (e instanceof IOException ? "I/O error" : "the storage failed") +
				(message != null ? ": " + message : "");
	}

	static Change refused(Exception e, String lostAccess) {
		return new Refused(e instanceof Problem problem
				? problem.refusal
				: Refusal.FAILED, reason(e, lostAccess));
	}

	static String revision(byte[] bytes) throws IOException {
		String revision = StoredFiles.revision(bytes);
		if (revision == null) {
			throw new IOException("SHA-256 is not available");
		}
		return revision;
	}

	static String nfc(String name) {
		return Normalizer.normalize(name, Normalizer.Form.NFC);
	}

	// The root without asking the provider, for paths that only list.
	private Document start() {
		return new Document(rootId, "", true, 0);
	}

	private Document changeableRoot() throws IOException {
		Document root = writable ? resolver.document(rootId) : null;
		if (root == null || !offers(root, true).any()) {
			throw new Problem(Refusal.UNSUPPORTED, "this folder is read-only");
		}
		return root;
	}

	private boolean remembers() {
		synchronized (listings) {
			return !listings.isEmpty();
		}
	}

	// Return the document of segments as the kept listings tell it,
	// listing the folders on the way they do not tell about, or null.
	private Document known(String[] segments) throws IOException {
		Document document = start();
		int i = 0;
		synchronized (listings) {
			for (int j = segments.length - 1; j > 0; --j) {
				Listed listed = listings.get(key(segments, j));
				if (listed != null) {
					document = listed.folder();
					i = j;
					break;
				}
			}
		}
		for (; i < segments.length; ++i) {
			if (!document.folder()) {
				return null;
			}
			String key = key(segments, i);
			Listed listed;
			synchronized (listings) {
				listed = listings.get(key);
			}
			Document child = listed != null
					? listed.children().get(segments[i])
					: null;
			if (child == null) {
				listChildren(key, document);
				synchronized (listings) {
					listed = listings.get(key);
				}
				child = listed != null
						? listed.children().get(segments[i])
						: null;
			}
			if (child == TWICE) {
				throw new Problem(Refusal.FAILED,
						"two entries are named " + segments[i]);
			} else if (child == null) {
				return null;
			}
			document = child;
		}
		return document;
	}

	// List folder, the folder at key, and keep what it found.
	private List<Document> listChildren(String key, Document folder)
			throws IOException {
		List<Document> children = resolver.children(folder.id());
		Map<String, Document> byName = new HashMap<>();
		for (Document child : children) {
			String name = nfc(child.name());
			byName.put(name, byName.containsKey(name) ? TWICE : child);
		}
		synchronized (listings) {
			listings.put(key, new Listed(folder, byName));
		}
		return children;
	}

	private static String key(String[] segments, int count) {
		StringBuilder key = new StringBuilder();
		for (int i = 0; i < count; ++i) {
			if (i > 0) {
				key.append('/');
			}
			key.append(segments[i]);
		}
		return key.toString();
	}

	private Document find(Document root, String[] segments)
			throws IOException {
		return find(root, segments, segments.length);
	}

	// Return the document of the first count segments, or null.
	private Document find(Document root, String[] segments, int count)
			throws IOException {
		Document document = root;
		for (int i = 0; i < count; ++i) {
			if (!document.folder() ||
					(document = child(document, segments[i])) == null) {
				return null;
			}
		}
		return document;
	}

	// Return the folder of the first count segments, creating the
	// missing ones.
	private Document folders(Document root, String[] segments, int count)
			throws IOException {
		Document folder = root;
		for (int i = 0; i < count; ++i) {
			Document next = child(folder, segments[i]);
			if (next == null) {
				next = create(folder, segments[i], true);
			} else if (!next.folder()) {
				throw new Problem(Refusal.FAILED,
						segments[i] + " is not a folder");
			}
			folder = next;
		}
		return folder;
	}

	private Document child(Document folder, String name) throws IOException {
		Document found = null;
		for (Document child : resolver.children(folder.id())) {
			if (nfc(child.name()).equals(name)) {
				if (found != null) {
					throw new Problem(Refusal.FAILED,
							"two entries are named " + name);
				}
				found = child;
			}
		}
		return found;
	}

	private Document create(Document folder, String name, boolean isFolder)
			throws IOException {
		if (!offers(folder, writable).create()) {
			throw new Problem(Refusal.UNSUPPORTED,
					"the storage cannot create " + name + " here");
		}
		Document created = resolver.document(resolver.create(folder.id(),
				name, isFolder));
		try {
			expectName(created, name);
		} catch (Problem e) {
			deleteQuietly(created.id());
			throw e;
		}
		return created;
	}

	// A provider may store a name it cannot keep apart from an existing
	// one, or does not accept, under another name.
	private static void expectName(Document document, String name)
			throws Problem {
		if (!nfc(document.name()).equals(name)) {
			throw new Problem(Refusal.FAILED, "the storage stored " + name +
					" as " + document.name());
		}
	}

	private void deleteQuietly(String id) {
		try {
			resolver.delete(id);
		} catch (IOException | RuntimeException e) {
			// Leave it; the change is refused either way.
		}
	}

	private static boolean has(int flags, int flag) {
		return (flags & flag) != 0;
	}

	private static String folderOf(ProjectPath path) {
		String text = path.toString();
		return text.substring(0, Math.max(0, text.lastIndexOf('/')));
	}

	private static String[] segments(ProjectPath path) {
		return path.toString().split("/");
	}

	private static Change refused(Exception e) {
		return refused(e, LOST_FOLDER);
	}

	static final class Problem extends IOException {
		private final Refusal refusal;

		Problem(Refusal refusal, String reason) {
			super(reason);
			this.refusal = refusal;
		}
	}
}
