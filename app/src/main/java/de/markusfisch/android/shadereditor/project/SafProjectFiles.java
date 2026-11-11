package de.markusfisch.android.shadereditor.project;

import android.annotation.SuppressLint;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.DocumentResolver.Document;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * A project root that is a folder granted through the Storage Access
 * Framework. Opening it reads nothing. A path resolves by listing each
 * folder on the way and matching names exactly in Unicode NFC; two
 * entries with one name fail. A change happens only where the grant
 * allows writing and the document flags offer it; on a root that offers
 * no change at all, every change is unsupported. Revisions are content
 * digests, because provider metadata need not change with the bytes.
 * Checking a revision and writing are not one step against other
 * writers. Use it off the main thread.
 */
public final class SafProjectFiles implements ProjectFiles {
	static final String LOST_FOLDER =
			"ShaderEditor lost access to this folder, choose it again";

	/**
	 * The changes a document offers.
	 */
	public record Offers(boolean write, boolean create, boolean rename,
			boolean move, boolean delete) {
		public static final Offers NONE = new Offers(false, false, false,
				false, false);

		/**
		 * Map the flags of a document; nothing is offered without a
		 * writable grant.
		 */
		@SuppressLint("InlinedApi")
		public static Offers of(Document document, boolean writable) {
			if (!writable) {
				return NONE;
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

		public boolean any() {
			return write || create || rename || move || delete;
		}

		private static boolean has(int flags, int flag) {
			return (flags & flag) != 0;
		}
	}

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
	 * null, offers; none when there is no such document.
	 */
	public Offers offers(ProjectPath path) {
		try {
			Document document = path != null
					? find(start(), segments(path))
					: resolver.document(rootId);
			return document != null
					? Offers.of(document, writable)
					: Offers.NONE;
		} catch (IOException | RuntimeException e) {
			return Offers.NONE;
		}
	}

	@Override
	public Read read(ProjectPath path) {
		try {
			Document file = find(start(), segments(path));
			if (file == null) {
				return null;
			} else if (file.folder()) {
				return new Failed("it is a folder");
			}
			byte[] bytes = resolver.read(file.id());
			return new Content(bytes, revision(bytes));
		} catch (IOException | RuntimeException e) {
			return new Failed(reason(e, LOST_FOLDER));
		} catch (OutOfMemoryError e) {
			return new Failed("it is too large");
		}
	}

	@Override
	public Listing list(ProjectPath folder) {
		try {
			Document dir = folder != null
					? find(start(), segments(folder))
					: start();
			if (dir == null) {
				return null;
			} else if (!dir.folder()) {
				return new Failed("it is not a folder");
			}
			List<Entry> entries = new ArrayList<>();
			for (Document child : resolver.children(dir.id())) {
				entries.add(new Entry(nfc(child.name()), child.folder()));
			}
			return new Entries(entries);
		} catch (IOException | RuntimeException e) {
			return new Failed(reason(e, LOST_FOLDER));
		}
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
				} else if (!Offers.of(file, writable).write()) {
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
			Offers offers = Offers.of(document, writable);
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
		}
	}

	@Override
	public Change delete(ProjectPath path) {
		try {
			Document document = find(changeableRoot(), segments(path));
			if (document == null) {
				throw new Problem(Refusal.ABSENT, "there is no " + path);
			} else if (!Offers.of(document, writable).delete()) {
				throw new Problem(Refusal.UNSUPPORTED,
						"the storage cannot delete " + path);
			}
			resolver.delete(document.id());
			return new Done(null);
		} catch (IOException | RuntimeException e) {
			return refused(e);
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
		if (root == null || !Offers.of(root, true).any()) {
			throw new Problem(Refusal.UNSUPPORTED, "this folder is read-only");
		}
		return root;
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
		if (!Offers.of(folder, writable).create()) {
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
