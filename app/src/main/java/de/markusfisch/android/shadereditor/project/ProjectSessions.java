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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Short ids of opened projects, kept as app metadata in an app-private
 * folder: each id has a folder with the locator of its store, the
 * journal of its drafts, the file chosen to run and how it is listed
 * among the recent projects, so screens can pass the id instead of
 * project content. Removing a session never touches the project's
 * files. Use it off the main thread.
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

	/**
	 * A session listed among the recent projects: its id, locator, the
	 * name and place of its files to show, when it was last opened, in
	 * milliseconds since the epoch, and whether it has drafts.
	 */
	public record Recent(String id, Locator locator, String name,
			String location, long opened, boolean unsaved) {
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
	private static final String RUNNING = "running";
	private static final String RECENT = "recent";
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
	 * List session id among the recent projects with the name and
	 * location of its files, opened at time. Return whether it is kept.
	 */
	public boolean remember(String id, String name, String location,
			long opened) {
		File dir = file(id, null);
		if (dir == null || locator(id) == null) {
			return false;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC);
			out.writeUTF(name);
			out.writeUTF(location);
			out.writeLong(opened);
		} catch (IOException e) {
			return false;
		}
		return DraftJournal.replace(dir, RECENT, bytes.toByteArray());
	}

	/**
	 * Mark session id, a recent project, as opened at time. Return
	 * whether it is kept.
	 */
	public boolean touch(String id, long opened) {
		Recent recent = recent(id);
		return recent != null &&
				remember(id, recent.name(), recent.location(), opened);
	}

	/** Return the recent projects, the one opened last first. */
	public List<Recent> recent() {
		List<Recent> recent = new ArrayList<>();
		String[] names = root.list();
		if (names != null) {
			for (String name : names) {
				Recent session = recent(name);
				if (session != null) {
					recent.add(session);
				}
			}
		}
		Collections.sort(recent, (a, b) -> a.opened() != b.opened()
				? Long.compare(b.opened(), a.opened())
				: a.id().compareTo(b.id()));
		return recent;
	}

	/**
	 * Return session id as listed among the recent projects, or null
	 * when it is not listed.
	 */
	public Recent recent(String id) {
		File file = file(id, RECENT);
		Locator locator = locator(id);
		if (file == null || locator == null || !file.isFile()) {
			return null;
		}
		try (InputStream in = new FileInputStream(file)) {
			byte[] data = new byte[(int) file.length()];
			new DataInputStream(in).readFully(data);
			DataInputStream recent = new DataInputStream(
					new ByteArrayInputStream(data));
			if (recent.readInt() != MAGIC) {
				return null;
			}
			String[] drafts = file(id, DRAFTS).list();
			Recent session = new Recent(id, locator, recent.readUTF(),
					recent.readUTF(), recent.readLong(),
					drafts != null && drafts.length > 0);
			return recent.read() < 0 ? session : null;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Return the file last chosen to run in session id, or null when
	 * none was chosen.
	 */
	public ProjectPath running(String id) {
		File file = file(id, RUNNING);
		if (file == null || !file.isFile()) {
			return null;
		}
		try (InputStream in = new FileInputStream(file)) {
			byte[] data = new byte[(int) file.length()];
			new DataInputStream(in).readFully(data);
			DataInputStream running = new DataInputStream(
					new ByteArrayInputStream(data));
			if (running.readInt() != MAGIC) {
				return null;
			}
			ProjectParseResult<ProjectPath> path = ProjectPath.parse(
					running.readUTF());
			return path.isSuccess() && running.read() < 0
					? path.value()
					: null;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Remember path as the file to run in session id. Return whether it
	 * is kept.
	 */
	public boolean setRunning(String id, ProjectPath path) {
		File dir = file(id, null);
		if (dir == null || locator(id) == null) {
			return false;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC);
			out.writeUTF(path.toString());
		} catch (IOException e) {
			return false;
		}
		return DraftJournal.replace(dir, RUNNING, bytes.toByteArray());
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
