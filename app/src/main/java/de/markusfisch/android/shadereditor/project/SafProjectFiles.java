package de.markusfisch.android.shadereditor.project;

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
 * entries with one name fail. Revisions are content digests, because
 * provider metadata need not change with the bytes. Use it off the main
 * thread.
 */
public final class SafProjectFiles implements ProjectFiles {
	static final String LOST_FOLDER =
			"ShaderEditor lost access to this folder, choose it again";

	private final DocumentResolver resolver;
	private final String rootId;

	/**
	 * Open the folder rootId.
	 */
	public SafProjectFiles(DocumentResolver resolver, String rootId) {
		this.resolver = resolver;
		this.rootId = rootId;
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

	private static String[] segments(ProjectPath path) {
		return path.toString().split("/");
	}

	static final class Problem extends IOException {
		private final Refusal refusal;

		Problem(Refusal refusal, String reason) {
			super(reason);
			this.refusal = refusal;
		}
	}
}
