package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Short ids of opened projects, kept as app metadata in an app-private
 * folder: each id has a folder with the locator of its store and the
 * journal of its drafts, so screens can pass the id instead of project
 * content. Removing a session never touches the project's files. Use
 * it off the main thread.
 */
public final class ProjectSessions {
	/** What a locator points at. */
	public enum Kind {
		/** A directory of the app's own storage, by path. */
		DIRECTORY,
		/** A folder granted through the system picker, by tree URI. */
		TREE,
		/** A lone granted document, by document URI. */
		DOCUMENT,
		/** A shader of the database, by id. */
		DATABASE
	}

	/** Where the files of a project are stored. */
	public record Locator(Kind kind, String value) {
		public Locator {
			if (kind == null || value == null) {
				throw new IllegalArgumentException("incomplete locator");
			}
		}
	}

	private static final int MAGIC = 0x5345534c;
	private static final String LOCATOR = "locator";
	private static final String DRAFTS = "drafts";
	private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";
	private static final int LENGTH = 8;
	private static final Pattern ID = Pattern.compile(
			"[0-9a-z]{" + LENGTH + "}");

	private final SecureRandom random = new SecureRandom();
	private final File root;

	public ProjectSessions(File root) {
		this.root = root;
	}

	/**
	 * Return the id of the session of locator, starting one when there
	 * is none, or null when it cannot be kept.
	 */
	public synchronized String open(Locator locator) {
		String id = find(locator);
		if (id != null) {
			return id;
		}
		do {
			id = newId();
		} while (new File(root, id).exists());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC);
			out.writeUTF(locator.kind().name());
			out.writeUTF(locator.value());
		} catch (IOException e) {
			return null;
		}
		return DraftJournal.replace(new File(root, id), LOCATOR,
				bytes.toByteArray()) ? id : null;
	}

	/** Return the id of the session of locator, or null when none. */
	public synchronized String find(Locator locator) {
		String[] names = root.list();
		if (names != null) {
			for (String name : names) {
				if (locator.equals(locator(name))) {
					return name;
				}
			}
		}
		return null;
	}

	/** Return the locator of session id, or null when there is none. */
	public Locator locator(String id) {
		File file = file(id, LOCATOR);
		if (file == null || !file.isFile()) {
			return null;
		}
		try (InputStream in = new FileInputStream(file)) {
			byte[] data = new byte[(int) file.length()];
			new DataInputStream(in).readFully(data);
			DataInputStream locator = new DataInputStream(
					new ByteArrayInputStream(data));
			if (locator.readInt() != MAGIC) {
				return null;
			}
			Kind kind = Kind.valueOf(locator.readUTF());
			String value = locator.readUTF();
			return locator.read() < 0 ? new Locator(kind, value) : null;
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Return the journal of the drafts of session id, or null when
	 * there is no such session.
	 */
	public DraftJournal drafts(String id) {
		return locator(id) != null
				? new DraftJournal(file(id, DRAFTS))
				: null;
	}

	/**
	 * Forget session id with its drafts; the project's files stay as
	 * they are. Return whether nothing of it is left.
	 */
	public synchronized boolean remove(String id) {
		if (file(id, null) == null) {
			return true;
		}
		File dir;
		try {
			// Resolved, so a link inside is told apart and not followed.
			dir = new File(root.getCanonicalFile(), id);
		} catch (IOException e) {
			return false;
		}
		return !dir.exists() || delete(dir);
	}

	private String newId() {
		char[] id = new char[LENGTH];
		for (int i = 0; i < LENGTH; ++i) {
			id[i] = DIGITS.charAt(random.nextInt(DIGITS.length()));
		}
		return new String(id);
	}

	// Return a file in the folder of a well-formed id, or that folder
	// when name is null.
	private File file(String id, String name) {
		if (id == null || !ID.matcher(id).matches()) {
			return null;
		}
		File dir = new File(root, id);
		return name != null ? new File(dir, name) : dir;
	}

	private static boolean delete(File file) {
		File[] children = isLink(file) ? null : file.listFiles();
		if (children != null) {
			for (File child : children) {
				delete(child);
			}
		}
		return file.delete();
	}

	private static boolean isLink(File file) {
		try {
			return DirectoryProjectFiles.isLink(file);
		} catch (IOException e) {
			return true;
		}
	}
}
