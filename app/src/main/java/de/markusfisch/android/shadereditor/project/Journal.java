package de.markusfisch.android.shadereditor.project;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * The unsaved text of the files of a session, one self-contained file
 * each, so it is recovered without the stored files: a header with the
 * base the text came from and the consent to overwrite, a snapshot of
 * the text, and change records appended after it without a sync. Each
 * part has its own checksum; a torn or damaged tail is cut at the last
 * good record, a damaged header or snapshot is reported and the file
 * left as it is. Worker only, one call at a time.
 */
final class Journal {
	/**
	 * Where a file's text came from: the stored revision it was last
	 * equal to, null for none, and whether the user chose to overwrite
	 * the stored revision keptOver, null for a file that was absent.
	 */
	record Header(ProjectPath path, String base, boolean kept,
			String keptOver) {
	}

	/** A change: length removed at start, and the text inserted. */
	record Change(int start, int removed, String inserted) {
	}

	/**
	 * What a journal holds: its header, the text as of its last good
	 * record and the revision of that record. text is null and problem
	 * tells why when the header or the snapshot is damaged. tooLarge
	 * tells that the text is over {@link FileTexts#MAX_BYTES}; it is
	 * recovered all the same.
	 */
	record Recovered(Header header, Text text, int revision, String problem,
			boolean tooLarge) {
	}

	/** What an append did. */
	enum Appended {
		/** The records are in the journal, not yet synced. */
		DONE,
		/** The journal does not continue there: write a snapshot. */
		SNAPSHOT,
		/** It could not be written. */
		FAILED
	}

	// "SEJ1"
	private static final int MAGIC = 0x53454a31;
	private static final String SUFFIX = ".journal";
	// Records are rewritten into a snapshot once they exceed the
	// snapshot's size, but not before MIN_RECORDS and at the latest at
	// MAX_RECORDS bytes.
	private static final long MIN_RECORDS = 16 * 1024;
	private static final long MAX_RECORDS = 128 * 1024;

	// Where a journal ends and at which revision, as last read or
	// written.
	private record Tail(int revision, long length, long snapshot,
			long records) {
	}

	private final Map<ProjectPath, Tail> tails = new HashMap<>();
	private final File dir;

	Journal(File dir) {
		this.dir = dir;
	}

	/**
	 * Replace the journal of header's path with text at revision, synced.
	 * Return whether it was written.
	 */
	boolean snapshot(Header header, int revision, Text text) {
		ProjectPath path = header.path();
		tails.remove(path);
		byte[] head = header(header, revision);
		byte[] bytes = encode(text.toString());
		if (!DraftJournal.replace(dir, name(path), out -> {
			DataOutputStream data = new DataOutputStream(out);
			data.writeInt(MAGIC);
			writeBlock(data, head);
			writeBlock(data, bytes);
			data.flush();
		})) {
			return false;
		}
		tails.put(path, new Tail(revision, 12L + head.length + 8 +
				bytes.length, bytes.length, 0));
		return true;
	}

	/**
	 * Append the changes that take the text of path from revision from
	 * to revision to, without a sync. Answer {@link Appended#SNAPSHOT}
	 * when the journal is not at from, cannot be read, or its records
	 * should be rewritten into a snapshot.
	 */
	Appended append(ProjectPath path, int from, int to,
			List<Change> changes) {
		Tail tail = tails.get(path);
		if (tail == null) {
			Recovered recovered = read(path);
			tail = recovered != null && recovered.text() != null
					? tails.get(path)
					: null;
		}
		if (tail == null || tail.revision() != from) {
			return Appended.SNAPSHOT;
		}
		byte[] record = record(from, to, changes);
		long records = tail.records() + record.length + 8;
		if (records > Math.max(MIN_RECORDS,
				Math.min(tail.snapshot(), MAX_RECORDS))) {
			return Appended.SNAPSHOT;
		}
		File file = file(path);
		// Shorter than written, or gone: it changed under the journal.
		if (file.length() < tail.length()) {
			tails.remove(path);
			return Appended.SNAPSHOT;
		}
		try {
			// Cut a torn or damaged tail before appending after it.
			if (file.length() > tail.length()) {
				try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
					raf.setLength(tail.length());
				}
			}
			try (DataOutputStream out = new DataOutputStream(
					new FileOutputStream(file, true))) {
				writeBlock(out, record);
			}
		} catch (IOException e) {
			tails.remove(path);
			return Appended.FAILED;
		}
		tails.put(path, new Tail(to, tail.length() + record.length + 8,
				tail.snapshot(), records));
		return Appended.DONE;
	}

	/**
	 * Keep header for the journal of its path, with the text it has.
	 * Return whether it was written.
	 */
	boolean setHeader(Header header) {
		Recovered recovered = read(header.path());
		return recovered != null && recovered.text() != null &&
				snapshot(header, recovered.revision(), recovered.text());
	}

	/** Return what the journal of path holds, or null when none. */
	Recovered read(ProjectPath path) {
		File file = file(path);
		if (!file.isFile()) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(new FileInputStream(file)))) {
			byte[] head = readMagic(in) ? readBlock(in) : null;
			if (head == null) {
				return damaged(path, "its header is damaged");
			}
			Header header;
			int revision;
			try (DataInputStream fields = new DataInputStream(
					new ByteArrayInputStream(head))) {
				header = header(fields);
				revision = fields.readInt();
			}
			if (!path.equals(header.path())) {
				return damaged(path, "it belongs to " + header.path());
			}
			byte[] bytes = readBlock(in);
			if (bytes == null) {
				return damaged(path, "its text is damaged");
			}
			Text text = Text.of(decode(bytes));
			long length = 12L + head.length + 8 + bytes.length;
			long records = 0;
			for (byte[] record; (record = readBlock(in)) != null; ) {
				Replayed replayed = replay(record, revision, text);
				if (replayed == null) {
					break;
				}
				revision = replayed.revision;
				text = replayed.text;
				length += record.length + 8;
				records += record.length + 8;
			}
			tails.put(path, new Tail(revision, length, bytes.length,
					records));
			return new Recovered(header, text, revision, null,
					TextBytes.length(text) > FileTexts.MAX_BYTES);
		} catch (IOException | RuntimeException e) {
			return damaged(path, "it cannot be read");
		}
	}

	/**
	 * Return the header of every journal that has a readable one, by
	 * path; the texts are not read.
	 */
	Map<ProjectPath, Header> headers() {
		Map<ProjectPath, Header> headers = new LinkedHashMap<>();
		File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
		if (files == null) {
			return headers;
		}
		for (File file : files) {
			try (DataInputStream in = new DataInputStream(
					new BufferedInputStream(new FileInputStream(file)))) {
				byte[] head = readMagic(in) ? readBlock(in) : null;
				if (head != null) {
					Header header = header(new DataInputStream(
							new ByteArrayInputStream(head)));
					if (file.getName().equals(name(header.path()))) {
						headers.put(header.path(), header);
					}
				}
			} catch (IOException | RuntimeException e) {
				// Not a journal anyone can read; it stays as it is.
			}
		}
		return headers;
	}

	/** Forget the journal of path; return whether none is kept now. */
	boolean remove(ProjectPath path) {
		tails.remove(path);
		File file = file(path);
		return !file.exists() || file.delete();
	}

	/**
	 * Make every journal durable, as when the app goes to the
	 * background; return whether all are.
	 */
	boolean sync() {
		File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
		boolean synced = true;
		if (files != null) {
			for (File file : files) {
				try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
					raf.getFD().sync();
				} catch (IOException e) {
					synced = false;
				}
			}
		}
		return synced;
	}

	File file(ProjectPath path) {
		return new File(dir, name(path));
	}

	private Recovered damaged(ProjectPath path, String problem) {
		tails.remove(path);
		return new Recovered(null, null, -1,
				"the unsaved text of " + path + " cannot be recovered: " +
						problem, false);
	}

	private record Replayed(int revision, Text text) {
	}

	// Return the text after record, or null when it does not continue
	// the journal at revision or does not fit text.
	private static Replayed replay(byte[] record, int revision, Text text) {
		try (DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(record))) {
			if (in.readInt() != revision) {
				return null;
			}
			int to = in.readInt();
			for (int i = in.readInt(); i-- > 0; ) {
				int start = in.readInt();
				int removed = in.readInt();
				int length = in.readInt();
				if (start < 0 || removed < 0 || length < 0 ||
						length > in.available() ||
						(long) start + removed > text.length()) {
					return null;
				}
				byte[] inserted = new byte[length];
				in.readFully(inserted);
				text = text.replace(start, start + removed,
						Text.of(decode(inserted)));
			}
			return in.available() == 0 ? new Replayed(to, text) : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static byte[] header(Header header, int revision) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeUTF(header.path().toString());
			writeRevision(out, header.base());
			out.writeBoolean(header.kept());
			writeRevision(out, header.keptOver());
			out.writeInt(revision);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	private static Header header(DataInputStream in) throws IOException {
		ProjectParseResult<ProjectPath> path = ProjectPath.parse(
				in.readUTF());
		if (!path.isSuccess()) {
			throw new IOException("not a path");
		}
		String base = readRevision(in);
		boolean kept = in.readBoolean();
		return new Header(path.value(), base, kept, readRevision(in));
	}

	private static byte[] record(int from, int to, List<Change> changes) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(from);
			out.writeInt(to);
			out.writeInt(changes.size());
			for (Change change : changes) {
				byte[] inserted = encode(change.inserted());
				out.writeInt(change.start());
				out.writeInt(change.removed());
				out.writeInt(inserted.length);
				out.write(inserted);
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	private static void writeRevision(DataOutputStream out, String revision)
			throws IOException {
		out.writeBoolean(revision != null);
		out.writeUTF(revision != null ? revision : "");
	}

	private static String readRevision(DataInputStream in)
			throws IOException {
		boolean present = in.readBoolean();
		String revision = in.readUTF();
		return present ? revision : null;
	}

	private static boolean readMagic(DataInputStream in) throws IOException {
		try {
			return in.readInt() == MAGIC;
		} catch (EOFException e) {
			return false;
		}
	}

	// A block is its length, its bytes and their CRC-32.
	private static void writeBlock(DataOutputStream out, byte[] bytes)
			throws IOException {
		out.writeInt(bytes.length);
		out.write(bytes);
		out.writeInt((int) crc(bytes));
	}

	// Return the next block, or null at the end, at a torn block or one
	// whose checksum does not match.
	private static byte[] readBlock(DataInputStream in) throws IOException {
		try {
			int length = in.readInt();
			// More than the file has left is a damaged length.
			if (length < 0 || length > in.available()) {
				return null;
			}
			byte[] bytes = new byte[length];
			in.readFully(bytes);
			return in.readInt() == (int) crc(bytes) ? bytes : null;
		} catch (EOFException e) {
			return null;
		}
	}

	private static long crc(byte[] bytes) {
		CRC32 crc = new CRC32();
		crc.update(bytes, 0, bytes.length);
		return crc.getValue();
	}

	// Each char on its own, as modified UTF-8 does, so lone surrogates
	// and NUL come back as they were.
	static byte[] encode(String s) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
		for (int i = 0, n = s.length(); i < n; ++i) {
			char c = s.charAt(i);
			if (c >= 0x01 && c <= 0x7f) {
				out.write(c);
			} else if (c <= 0x7ff) {
				out.write(0xc0 | (c >> 6));
				out.write(0x80 | (c & 0x3f));
			} else {
				out.write(0xe0 | (c >> 12));
				out.write(0x80 | ((c >> 6) & 0x3f));
				out.write(0x80 | (c & 0x3f));
			}
		}
		return out.toByteArray();
	}

	static String decode(byte[] bytes) throws IOException {
		StringBuilder sb = new StringBuilder(bytes.length);
		for (int i = 0, n = bytes.length; i < n; ) {
			int b = bytes[i++] & 0xff;
			if (b < 0x80) {
				sb.append((char) b);
			} else if ((b & 0xe0) == 0xc0 && i < n) {
				sb.append((char) (((b & 0x1f) << 6) | (bytes[i++] & 0x3f)));
			} else if ((b & 0xf0) == 0xe0 && i + 1 < n) {
				sb.append((char) (((b & 0x0f) << 12) |
						((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f)));
			} else {
				throw new IOException("not modified UTF-8");
			}
		}
		return sb.toString();
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
