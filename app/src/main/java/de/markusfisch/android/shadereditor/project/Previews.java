package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The preview pictures of the shaders of one project: one PNG a path,
 * kept with the revision of the content it was made from, so that who
 * shows it can tell a picture of the file as it is from one of what
 * it was. App data, not project files.
 * <p>
 * Anyone may read a picture, off the main thread; one that cannot be
 * read shows as none. Only the queue the projects work on changes
 * them. A picture names its path, so that the pictures of a renamed
 * or deleted folder are found, and none is shown for another file.
 */
public final class Previews {
	/** A picture and the revision of the content it shows. */
	public record Picture(byte[] png, String revision) {
	}

	private static final int MAGIC = 0x53455032;
	private static final String SUFFIX = ".preview";
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private final File folder;
	// What has not followed a rename or deletion yet, or null.
	private final Following following;

	Previews(File folder) {
		this(folder, null);
	}

	Previews(File folder, Following following) {
		this.folder = folder;
		this.following = following;
	}

	/** Return the picture kept for path, or null when there is none. */
	public Picture get(ProjectPath path) {
		// Where it is kept while a rename has not been followed; a
		// path a file left has no picture.
		if (following != null) {
			path = following.stored(path);
		}
		File file = path != null ? file(path) : null;
		if (file == null || !file.isFile()) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(
				new FileInputStream(file))) {
			// A file named alike that is of another path is not shown.
			if (in.readInt() != MAGIC ||
					!in.readUTF().equals(path.toString())) {
				return null;
			}
			String revision = in.readUTF();
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > 0; ) {
				png.write(buffer, 0, n);
			}
			return new Picture(png.toByteArray(), revision);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Keep png as the picture of path, made from its content at
	 * revision, in place of the one kept before. Return whether it is
	 * kept.
	 */
	boolean put(ProjectPath path, String revision, byte[] png) {
		File file = file(path);
		if (file == null) {
			return false;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC);
			out.writeUTF(path.toString());
			out.writeUTF(revision != null ? revision : "");
			out.write(png);
		} catch (IOException e) {
			return false;
		}
		return StateFile.replace(file, bytes.toByteArray());
	}

	/**
	 * Follow a file or folder that was renamed or deleted: its picture,
	 * and the picture of every file under it, is kept for where the
	 * file is now, or removed. Return whether none is left behind.
	 */
	boolean follow(Journal.Move move) {
		if (!folder.exists()) {
			return true;
		}
		File[] files = folder.listFiles((d, name) -> name.endsWith(SUFFIX));
		if (files == null) {
			return false;
		}
		boolean followed = true;
		for (File file : files) {
			byte[] bytes;
			ProjectPath path;
			try {
				bytes = StateFile.read(file);
				path = bytes != null ? path(bytes) : null;
			} catch (IOException e) {
				// Whose picture it is cannot be told, and it is shown
				// for nobody; it holds nothing up.
				continue;
			}
			if (path == null || !move.touches(path)) {
				continue;
			}
			ProjectPath now = move.moved(path);
			Picture picture = now != null ? get(path) : null;
			if (picture != null && !put(now, picture.revision(),
					picture.png())) {
				followed = false;
			} else if (!file.delete()) {
				followed = false;
			}
		}
		return followed;
	}

	// The path a picture file names; null when nothing here wrote it.
	private static ProjectPath path(byte[] bytes) throws IOException {
		try (DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(bytes))) {
			if (bytes.length < 4 || in.readInt() != MAGIC) {
				return null;
			}
			ProjectParseResult<ProjectPath> path =
					ProjectPath.parse(in.readUTF());
			return path.isSuccess() ? path.value() : null;
		} catch (java.io.EOFException | java.io.UTFDataFormatException e) {
			return null;
		}
	}

	// Named by a hash of the whole path, so that no path is a file
	// name here.
	private File file(ProjectPath path) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
		StringBuilder name = new StringBuilder();
		for (byte b : digest.digest(path.toString().getBytes(
				StandardCharsets.UTF_8))) {
			name.append(HEX[(b >> 4) & 15]).append(HEX[b & 15]);
		}
		return new File(folder, name + SUFFIX);
	}
}
