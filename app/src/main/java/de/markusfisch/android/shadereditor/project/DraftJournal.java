package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * The drafts of one editing session kept in an app-private folder, one
 * small file per drafted file with its path, base revision and text, so
 * they survive the process. Independent of the project's store. A
 * draft is replaced in one rename, so a crash leaves the old or the new
 * draft, never a part. Use it off the main thread.
 */
public final class DraftJournal {
	private static final int MAGIC = 0x53454431;
	private static final String SUFFIX = ".draft";
	private static final String TEMP = ".tmp";

	private final File dir;

	public DraftJournal(File dir) {
		this.dir = dir;
	}

	/** Keep the draft of path; return whether it was written. */
	public boolean put(ProjectPath path, Draft draft) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC);
			out.writeUTF(path.toString());
			out.writeBoolean(draft.base() != null);
			out.writeUTF(draft.base() != null ? draft.base() : "");
			byte[] text = draft.bytes();
			out.writeInt(text.length);
			out.write(text);
		} catch (IOException e) {
			return false;
		}
		return replace(dir, name(path), bytes.toByteArray());
	}

	/** Forget the draft of path; return whether none is kept now. */
	public boolean remove(ProjectPath path) {
		File file = new File(dir, name(path));
		return !file.exists() || file.delete();
	}

	/**
	 * Return every kept draft, by path. A file that cannot be read is
	 * skipped and left where it is.
	 */
	public Map<ProjectPath, Draft> drafts() {
		Map<ProjectPath, Draft> drafts = new HashMap<>();
		String[] names = dir.list();
		if (names == null) {
			return drafts;
		}
		for (String name : names) {
			if (!name.endsWith(SUFFIX)) {
				continue;
			}
			try {
				readDraft(new File(dir, name), drafts);
			} catch (IOException | RuntimeException e) {
				// Not a complete draft; leave it alone.
			}
		}
		return drafts;
	}

	/**
	 * Return the path of every kept draft, reading only the start of
	 * each draft and not its text.
	 */
	public Set<ProjectPath> paths() {
		Set<ProjectPath> paths = new HashSet<>();
		String[] names = dir.list();
		if (names == null) {
			return paths;
		}
		for (String name : names) {
			if (!name.endsWith(SUFFIX)) {
				continue;
			}
			try (DataInputStream in = new DataInputStream(
					new FileInputStream(new File(dir, name)))) {
				ProjectParseResult<ProjectPath> path = in.readInt() == MAGIC
						? ProjectPath.parse(in.readUTF())
						: null;
				if (path != null && path.isSuccess() &&
						name(path.value()).equals(name)) {
					paths.add(path.value());
				}
			} catch (IOException e) {
				// Not a draft; leave it alone.
			}
		}
		return paths;
	}

	/**
	 * Replace the file name in dir with bytes in one rename, creating
	 * dir when it is missing. Return whether it was written.
	 */
	static boolean replace(File dir, String name, byte[] bytes) {
		if (!dir.isDirectory() && !dir.mkdirs()) {
			return false;
		}
		File temp = null;
		try {
			temp = File.createTempFile(".journal-", TEMP, dir);
			try (FileOutputStream out = new FileOutputStream(temp)) {
				out.write(bytes);
				out.getFD().sync();
			}
			return temp.renameTo(new File(dir, name));
		} catch (IOException e) {
			return false;
		} finally {
			if (temp != null && temp.exists()) {
				//noinspection ResultOfMethodCallIgnored
				temp.delete();
			}
		}
	}

	private static void readDraft(File file, Map<ProjectPath, Draft> drafts)
			throws IOException {
		byte[] data;
		try (InputStream in = new FileInputStream(file)) {
			data = new byte[(int) file.length()];
			new DataInputStream(in).readFully(data);
		}
		DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(data));
		if (in.readInt() != MAGIC) {
			return;
		}
		ProjectParseResult<ProjectPath> path = ProjectPath.parse(in.readUTF());
		boolean hasBase = in.readBoolean();
		String base = in.readUTF();
		int length = in.readInt();
		if (length < 0 || length > in.available()) {
			return;
		}
		byte[] text = new byte[length];
		in.readFully(text);
		if (path.isSuccess() && in.read() < 0 &&
				name(path.value()).equals(file.getName())) {
			drafts.put(path.value(), new Draft(
					new String(text, StandardCharsets.UTF_8),
					hasBase ? base : null));
		}
	}

	private static String name(ProjectPath path) {
		String digest = StoredFiles.revision(
				path.toString().getBytes(StandardCharsets.UTF_8));
		if (digest == null) {
			throw new IllegalStateException("SHA-256 is not available");
		}
		return digest.substring(digest.indexOf(':') + 1) + SUFFIX;
	}
}
