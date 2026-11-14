package de.markusfisch.android.shadereditor.project;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * A project root that is a directory of the file system. Names are
 * matched exactly in Unicode NFC by listing each folder, also where the
 * file system folds case or normalizes names, so a path finds the same
 * entries everywhere. Symbolic links are refused, and nothing outside
 * the root is read or written. A file is replaced in one rename, so a
 * crash leaves the old or the new bytes. Revisions are content digests.
 * Checking a revision and writing are not one step against other
 * writers. Use it off the main thread.
 */
public final class DirectoryProjectFiles implements ProjectFiles {
	private final File root;

	public DirectoryProjectFiles(File root) {
		this.root = root;
	}

	@Override
	public Read read(ProjectPath path) {
		return read(path, Long.MAX_VALUE);
	}

	@Override
	public Read read(ProjectPath path, long limit) {
		try {
			File file = find(path);
			if (file == null) {
				return null;
			} else if (file.isDirectory()) {
				return new Failed("it is a folder");
			}
			byte[] bytes = readBytes(file, limit);
			return bytes != null
					? new Content(bytes, revision(bytes))
					: new Failed(TOO_LARGE);
		} catch (IOException e) {
			return new Failed(reason(e));
		} catch (OutOfMemoryError e) {
			return new Failed("it is too large");
		}
	}

	@Override
	public byte[] head(ProjectPath path, int count) {
		try {
			File file = find(path);
			if (file == null || file.isDirectory()) {
				return null;
			}
			try (InputStream in = new FileInputStream(file)) {
				return head(in, count);
			}
		} catch (IOException e) {
			return null;
		}
	}

	/** Return at most the first count bytes in has. */
	static byte[] head(InputStream in, int count) throws IOException {
		byte[] head = new byte[count];
		int total = 0;
		for (int n; total < count &&
				(n = in.read(head, total, count - total)) > -1; ) {
			total += n;
		}
		return java.util.Arrays.copyOf(head, total);
	}

	@Override
	public Listing list(ProjectPath folder) {
		try {
			File dir = folder != null ? find(folder) : root();
			if (dir == null) {
				return null;
			} else if (!dir.isDirectory()) {
				return new Failed("it is not a folder");
			}
			List<Entry> entries = new ArrayList<>();
			for (String name : names(dir)) {
				File file = new File(dir, name);
				if (!isLink(file)) {
					boolean isFolder = file.isDirectory();
					long modified = file.lastModified();
					entries.add(new Entry(nfc(name), isFolder,
							isFolder ? UNKNOWN : file.length(),
							modified > 0 ? modified : UNKNOWN));
				}
			}
			return new Entries(entries);
		} catch (IOException e) {
			return new Failed(reason(e));
		}
	}

	/**
	 * Return what the file system lets this process change: writing a
	 * file or creating in a folder it may write, renaming, moving and
	 * deleting what is in a folder it may write.
	 */
	@Override
	public Offers offers(ProjectPath path) {
		try {
			File file = path != null ? find(path) : root();
			if (file == null) {
				return Offers.NONE;
			}
			boolean folder = file.isDirectory();
			boolean writable = file.canWrite();
			boolean movable = path != null &&
					file.getParentFile().canWrite();
			return new Offers(!folder && writable, folder && writable,
					movable, movable, movable);
		} catch (IOException e) {
			return Offers.NONE;
		}
	}

	@Override
	public Change write(ProjectPath path, byte[] bytes,
			String expectedRevision) {
		return write(path, out -> out.write(bytes), expectedRevision);
	}

	@Override
	public Change write(ProjectPath path, Source source,
			String expectedRevision) {
		try {
			String[] segments = segments(path);
			File file = find(segments, segments.length);
			if (file != null && file.isDirectory()) {
				throw new Problem(Refusal.FAILED, "it is a folder");
			}
			String revision = file != null ? revision(file) : null;
			if (revision == null ? expectedRevision != null
					: !revision.equals(expectedRevision)) {
				throw new Problem(Refusal.CONFLICT, file == null
						? "the file was deleted"
						: expectedRevision == null
								? "the file exists"
								: "the file has changed");
			}
			File folder = folders(segments, segments.length - 1);
			String name = file != null
					? file.getName()
					: newName(folder, segments[segments.length - 1]);
			return new Done(replace(folder, name, source, expectedRevision));
		} catch (IOException e) {
			return refused(e);
		}
	}

	@Override
	public Change createFolder(ProjectPath folder) {
		try {
			String[] segments = segments(folder);
			if (find(segments, segments.length) != null) {
				throw new Problem(Refusal.EXISTS, "it exists");
			}
			folders(segments, segments.length);
			return new Done(null);
		} catch (IOException e) {
			return refused(e);
		}
	}

	@Override
	public Change rename(ProjectPath from, ProjectPath to) {
		try {
			File source = find(from);
			if (source == null) {
				throw new Problem(Refusal.ABSENT, "there is no " + from);
			} else if (from.equals(to)) {
				return new Done(null);
			} else if (to.toString().startsWith(from + "/")) {
				throw new Problem(Refusal.FAILED,
						"a folder cannot move into itself");
			}
			String[] segments = segments(to);
			if (find(segments, segments.length) != null) {
				throw new Problem(Refusal.EXISTS, to + " exists");
			}
			File folder = folders(segments, segments.length - 1);
			String name = segments[segments.length - 1];
			// A case-insensitive file system finds the source itself
			// under a name that differs only in case.
			if (!folder.equals(source.getParentFile()) ||
					!nfc(source.getName()).equalsIgnoreCase(name)) {
				name = newName(folder, name);
			}
			if (!source.renameTo(new File(folder, name))) {
				throw new Problem(Refusal.FAILED, "cannot rename " + from);
			}
			return new Done(null);
		} catch (IOException e) {
			return refused(e);
		}
	}

	@Override
	public Change delete(ProjectPath path) {
		try {
			File file = find(path);
			if (file == null) {
				throw new Problem(Refusal.ABSENT, "there is no " + path);
			}
			deleteTree(file);
			return new Done(null);
		} catch (IOException e) {
			return refused(e);
		}
	}

	/**
	 * Return the root as the file system resolves it.
	 */
	File root() throws IOException {
		File dir = root.getCanonicalFile();
		if (!dir.isDirectory()) {
			throw new Problem(Refusal.FAILED, "the project folder is missing");
		}
		return dir;
	}

	/**
	 * Return the file or folder at path, or null when there is none.
	 * Throws for a link, for two entries with one name, and when a
	 * folder cannot be listed.
	 */
	File find(ProjectPath path) throws IOException {
		String[] segments = segments(path);
		return find(segments, segments.length);
	}

	private File find(String[] segments, int count) throws IOException {
		File file = root();
		for (int i = 0; i < count; ++i) {
			if (!file.isDirectory() || (file = child(file,
					segments[i])) == null) {
				return null;
			}
		}
		return file;
	}

	// Return the existing folders of the first count segments, creating
	// the missing ones.
	private File folders(String[] segments, int count) throws IOException {
		File folder = root();
		for (int i = 0; i < count; ++i) {
			File next = child(folder, segments[i]);
			if (next == null) {
				next = new File(folder, newName(folder, segments[i]));
				if (!next.mkdir()) {
					throw new Problem(Refusal.FAILED,
							"cannot create the folder " + segments[i]);
				}
			} else if (!next.isDirectory()) {
				throw new Problem(Refusal.FAILED,
						segments[i] + " is not a folder");
			}
			folder = next;
		}
		return folder;
	}

	private static File child(File folder, String name) throws IOException {
		File found = null;
		for (String entry : names(folder)) {
			if (nfc(entry).equals(name)) {
				if (found != null) {
					throw new Problem(Refusal.FAILED,
							"two entries are named " + name);
				}
				found = new File(folder, entry);
			}
		}
		if (found != null && isLink(found)) {
			throw new Problem(Refusal.FAILED, name + " is a link");
		}
		return found;
	}

	// Refuse a name the file system would take for an existing entry
	// with another name, for example one that differs in case.
	private static String newName(File folder, String name)
			throws IOException {
		if (new File(folder, name).exists()) {
			throw new Problem(Refusal.EXISTS, "the storage cannot tell " +
					name + " from an existing name");
		}
		return name;
	}

	// Replace name in folder with what source writes and return the
	// revision of it.
	private static String replace(File folder, String name, Source source,
			String expectedRevision) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 is not available");
		}
		File temp = File.createTempFile(".shadereditor-", ".tmp", folder);
		try {
			try (FileOutputStream file = new FileOutputStream(temp)) {
				OutputStream out = new DigestOutputStream(
						new BufferedOutputStream(file), digest);
				source.writeTo(out);
				out.flush();
				file.getFD().sync();
			}
			// Check again right before publishing, so a change made while
			// the text was written is a conflict. This narrows the window
			// to the rename; it cannot make other writers transactional.
			File target = new File(folder, name);
			String revision = target.exists()
					? revision(target)
					: null;
			if (revision == null ? expectedRevision != null
					: !revision.equals(expectedRevision)) {
				throw new Problem(Refusal.CONFLICT, revision == null
						? "the file was deleted"
						: "the file changed while it was written");
			}
			if (!temp.renameTo(target)) {
				throw new Problem(Refusal.FAILED, "cannot replace " + name);
			}
			return StoredFiles.revision(digest);
		} finally {
			if (temp.exists()) {
				//noinspection ResultOfMethodCallIgnored
				temp.delete();
			}
		}
	}

	private static void deleteTree(File file) throws IOException {
		if (!isLink(file) && file.isDirectory()) {
			for (String name : names(file)) {
				deleteTree(new File(file, name));
			}
		}
		if (!file.delete()) {
			throw new Problem(Refusal.FAILED, "cannot delete " +
					file.getName());
		}
	}

	/**
	 * Return whether a listed name in a resolved folder is a symbolic
	 * link; a listed name that does not exist is a dangling one.
	 */
	static boolean isLink(File file) throws IOException {
		return !file.exists() || !file.getCanonicalFile().equals(file);
	}

	private static String[] names(File folder) throws IOException {
		String[] names = folder.list();
		if (names == null) {
			throw new Problem(Refusal.FAILED, "cannot list " +
					folder.getName());
		}
		return names;
	}

	// Return the revision of the bytes of file, reading them a buffer at
	// a time.
	private static String revision(File file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 is not available");
		}
		try (InputStream in = new FileInputStream(file)) {
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > -1; ) {
				digest.update(buffer, 0, n);
			}
		}
		return StoredFiles.revision(digest);
	}

	// Return the bytes of file, or null as soon as there are more than
	// limit, whatever its length said.
	private static byte[] readBytes(File file, long limit)
			throws IOException {
		try (InputStream in = new FileInputStream(file)) {
			return readBytes(in, file.length(), limit);
		}
	}

	/**
	 * Return the bytes in, of about length, has, or null as soon as
	 * there are more than limit.
	 */
	static byte[] readBytes(InputStream in, long length, long limit)
			throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(
				(int) Math.min(Math.min(length, limit), 1 << 20));
		byte[] buffer = new byte[8192];
		long total = 0;
		for (int n; (n = in.read(buffer)) > -1; ) {
			total += n;
			if (total > limit) {
				return null;
			}
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	private static String revision(byte[] bytes) throws IOException {
		String revision = StoredFiles.revision(bytes);
		if (revision == null) {
			throw new IOException("SHA-256 is not available");
		}
		return revision;
	}

	private static String[] segments(ProjectPath path) {
		return path.toString().split("/");
	}

	private static String nfc(String name) {
		return Normalizer.normalize(name, Normalizer.Form.NFC);
	}

	private static String reason(IOException e) {
		String message = e.getMessage();
		return e instanceof Problem
				? message
				: "I/O error" + (message != null ? ": " + message : "");
	}

	private static Change refused(IOException e) {
		return new Refused(e instanceof Problem problem
				? problem.refusal
				: Refusal.FAILED, reason(e));
	}

	private static final class Problem extends IOException {
		private final Refusal refusal;

		private Problem(Refusal refusal, String reason) {
			super(reason);
			this.refusal = refusal;
		}
	}
}
