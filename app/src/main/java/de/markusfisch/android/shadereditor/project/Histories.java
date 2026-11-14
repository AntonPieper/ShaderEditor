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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Document.Change;
import de.markusfisch.android.shadereditor.editor.Document.History;
import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * The undo history of the files of a session, one file each, so undo
 * survives a document leaving memory and the process. A history is
 * kept for exactly one text, named by the SHA-256 of its characters,
 * each encoded by itself, so no two texts share a name: read for
 * another text, damaged, or larger than a document may be, it is
 * ignored and deleted. Nothing depends on it; losing it loses undo and
 * the selection, never text or whether the text is saved, which a
 * history does not tell. Its size is that of the document's history,
 * which keeps at most a thousand steps and four million characters;
 * all of a session together keep at most {@link #MAX_BYTES}, the
 * oldest going first. Worker only.
 */
final class Histories {
	/**
	 * The most characters the texts of a history kept may have, as many
	 * as a document keeps beyond its newest step; one of a document
	 * with more is not kept.
	 */
	static final int MAX_CHARS = 4_000_000;

	/** The most bytes the histories of a session take together. */
	static final long MAX_BYTES = 2 * Document.MAX_BYTES;

	// "SEH2"
	private static final int MAGIC = 0x53454832;
	private static final String SUFFIX = ".history";

	private final File dir;

	Histories(File dir) {
		this.dir = dir;
	}

	/**
	 * Return the SHA-256 of the characters of text, each encoded by
	 * itself as the journal does, lines ended by a line feed, or null.
	 * Unlike the bytes a file gets, where every lone surrogate is the
	 * same '?', it differs for texts that differ.
	 */
	static String hash(Text text) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
		for (int i = 0, lines = text.lineCount(); i < lines; ++i) {
			digest.update(Journal.encode(text.line(i)));
			digest.update((byte) '\n');
		}
		return StoredFiles.revision(digest);
	}

	/** Return whether path has a history kept. */
	boolean has(ProjectPath path) {
		return file(path).isFile();
	}

	/**
	 * Keep history for path, whose text has hash. Return whether it was
	 * written.
	 */
	boolean write(ProjectPath path, String hash, History history) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeUTF(path.toString());
			out.writeUTF(hash);
			out.writeInt(history.revision());
			out.writeInt(history.state());
			writeSelection(out, history.selection());
			writeSteps(out, history.undo());
			writeSteps(out, history.redo());
		} catch (IOException e) {
			return false;
		}
		byte[] body = bytes.toByteArray();
		CRC32 crc = new CRC32();
		crc.update(body, 0, body.length);
		boolean written = Journal.replace(dir, name(path), out -> {
			DataOutputStream data = new DataOutputStream(out);
			data.writeInt(MAGIC);
			data.writeInt(body.length);
			data.write(body);
			data.writeInt((int) crc.getValue());
			data.flush();
		});
		// The bound holds while a project stays open, not only when
		// one opens; the one just written is the newest and stays.
		trim();
		return written;
	}

	/**
	 * Return the history kept for path when it was kept for the text of
	 * hash, else null; one that is not, or cannot be read, is deleted.
	 */
	History read(ProjectPath path, String hash) {
		File file = file(path);
		if (!file.isFile()) {
			return null;
		}
		History kept = null;
		if (file.length() <= Document.MAX_BYTES) {
			try {
				kept = parse(path, hash, file);
			} catch (IOException | RuntimeException e) {
				kept = null;
			}
		}
		if (kept == null) {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
		return kept;
	}

	/** Forget the history of path. */
	void remove(ProjectPath path) {
		//noinspection ResultOfMethodCallIgnored
		file(path).delete();
	}

	/**
	 * Let the histories follow move, which storage made: each one of a
	 * file at or under its source is kept for the new path, or deleted.
	 * One that cannot be followed is deleted too; that loses undo.
	 */
	void follow(Journal.Move move) {
		File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
		if (files == null) {
			return;
		}
		for (File file : files) {
			byte[] body = file.length() <= Document.MAX_BYTES
					? body(file)
					: null;
			ProjectPath path = null;
			int rest = 0;
			if (body != null) {
				try (DataInputStream in = new DataInputStream(
						new ByteArrayInputStream(body))) {
					ProjectParseResult<ProjectPath> parsed =
							ProjectPath.parse(in.readUTF());
					path = parsed.isSuccess() ? parsed.value() : null;
					rest = body.length - in.available();
				} catch (IOException e) {
					path = null;
				}
			}
			if (path == null || !move.touches(path)) {
				continue;
			}
			ProjectPath moved = move.moved(path);
			if (moved != null) {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				try (DataOutputStream out = new DataOutputStream(bytes)) {
					out.writeUTF(moved.toString());
					out.write(body, rest, body.length - rest);
					write(name(moved), bytes.toByteArray());
				} catch (IOException e) {
					// Undo of that file is lost.
				}
			}
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
	}

	// Return the body of a history file, or null when it is not one.
	private static byte[] body(File file) {
		try (DataInputStream in = new DataInputStream(
				new FileInputStream(file))) {
			int length = in.readInt() == MAGIC ? in.readInt() : -1;
			if (length < 0 || length > file.length() - 12) {
				return null;
			}
			byte[] body = new byte[length];
			in.readFully(body);
			CRC32 crc = new CRC32();
			crc.update(body, 0, body.length);
			return in.readInt() == (int) crc.getValue() ? body : null;
		} catch (IOException e) {
			return null;
		}
	}

	private boolean write(String name, byte[] body) {
		CRC32 crc = new CRC32();
		crc.update(body, 0, body.length);
		return Journal.replace(dir, name, out -> {
			DataOutputStream data = new DataOutputStream(out);
			data.writeInt(MAGIC);
			data.writeInt(body.length);
			data.write(body);
			data.writeInt((int) crc.getValue());
			data.flush();
		});
	}

	/**
	 * Delete the histories written longest ago while all together take
	 * more than {@link #MAX_BYTES}: those of files deleted or renamed
	 * elsewhere are never read again and would stay forever.
	 */
	void trim() {
		File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
		if (files == null) {
			return;
		}
		Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(),
				a.lastModified()));
		long total = 0;
		for (File file : files) {
			total += file.length();
			if (total > MAX_BYTES) {
				//noinspection ResultOfMethodCallIgnored
				file.delete();
			}
		}
	}

	private File file(ProjectPath path) {
		return new File(dir, name(path));
	}

	private static History parse(ProjectPath path, String hash, File file)
			throws IOException {
		byte[] body;
		try (DataInputStream in = new DataInputStream(
				new FileInputStream(file))) {
			int length = in.readInt() == MAGIC ? in.readInt() : -1;
			if (length < 0 || length > file.length() - 12) {
				return null;
			}
			body = new byte[length];
			in.readFully(body);
			CRC32 crc = new CRC32();
			crc.update(body, 0, body.length);
			if (in.readInt() != (int) crc.getValue() || in.read() > -1) {
				return null;
			}
		}
		DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(body));
		if (!path.toString().equals(in.readUTF()) ||
				!in.readUTF().equals(hash)) {
			return null;
		}
		int revision = in.readInt();
		int state = in.readInt();
		Selection selection = readSelection(in);
		List<History.Step> undo = readSteps(in);
		List<History.Step> redo = readSteps(in);
		return in.available() == 0
				? new History(undo, redo, revision, state, selection)
				: null;
	}

	private static void writeSteps(DataOutputStream out,
			List<History.Step> steps) throws IOException {
		out.writeInt(steps.size());
		for (History.Step step : steps) {
			writeSelection(out, step.selectionBefore());
			writeSelection(out, step.selectionAfter());
			out.writeInt(step.stateBefore());
			out.writeInt(step.stateAfter());
			out.writeInt(step.changes().size());
			for (Change change : step.changes()) {
				out.writeInt(change.start());
				writeText(out, change.removed());
				writeText(out, change.inserted());
			}
		}
	}

	private static List<History.Step> readSteps(DataInputStream in)
			throws IOException {
		int count = in.readInt();
		if (count < 0 || count > in.available()) {
			throw new IOException("not a count of steps");
		}
		List<History.Step> steps = new ArrayList<>(count);
		for (int i = 0; i < count; ++i) {
			Selection before = readSelection(in);
			Selection after = readSelection(in);
			int stateBefore = in.readInt();
			int stateAfter = in.readInt();
			int changes = in.readInt();
			if (changes < 0 || changes > in.available()) {
				throw new IOException("not a count of changes");
			}
			List<Change> list = new ArrayList<>(changes);
			for (int j = 0; j < changes; ++j) {
				list.add(new Change(in.readInt(), readText(in),
						readText(in)));
			}
			steps.add(new History.Step(list, before, after, stateBefore,
					stateAfter));
		}
		return steps;
	}

	private static void writeSelection(DataOutputStream out,
			Selection selection) throws IOException {
		out.writeInt(selection.anchor());
		out.writeInt(selection.head());
	}

	private static Selection readSelection(DataInputStream in)
			throws IOException {
		return new Selection(in.readInt(), in.readInt());
	}

	private static void writeText(DataOutputStream out, Text text)
			throws IOException {
		byte[] bytes = Journal.encode(text.toString());
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static Text readText(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > in.available()) {
			throw new IOException("not a length of text");
		}
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return Text.of(Journal.decode(bytes));
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
