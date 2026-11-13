package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
 * kept for exactly one text, named by the SHA-256 of its bytes: read
 * for another text, damaged, or larger than a document may be, it is
 * ignored and deleted. Nothing depends on it; losing it loses undo.
 * Its size is that of the document's history, which keeps at most a
 * thousand steps and four million characters. Worker only.
 */
final class Histories {
	/**
	 * A history and the stored revision the text was based on when it
	 * was kept, null for none.
	 */
	record Kept(History history, String base) {
	}

	/**
	 * The most characters the texts of a history kept may have, as many
	 * as a document keeps beyond its newest step; one of a document
	 * with more is not kept.
	 */
	static final int MAX_CHARS = 4_000_000;

	// "SEH1"
	private static final int MAGIC = 0x53454831;
	private static final String SUFFIX = ".history";

	private final File dir;

	Histories(File dir) {
		this.dir = dir;
	}

	/** Return the SHA-256 of the bytes of text, or null. */
	static String hash(Text text) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
			TextBytes.write(text, new OutputStream() {
				@Override
				public void write(int b) {
					digest.update((byte) b);
				}

				@Override
				public void write(byte[] b, int off, int len) {
					digest.update(b, off, len);
				}
			});
		} catch (NoSuchAlgorithmException | IOException e) {
			return null;
		}
		return StoredFiles.revision(digest);
	}

	/** Return whether path has a history kept. */
	boolean has(ProjectPath path) {
		return file(path).isFile();
	}

	/**
	 * Keep history for path, whose text has hash and was based on the
	 * stored revision base. Return whether it was written.
	 */
	boolean write(ProjectPath path, String hash, String base,
			History history) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeUTF(path.toString());
			out.writeUTF(hash);
			out.writeBoolean(base != null);
			out.writeUTF(base != null ? base : "");
			out.writeInt(history.revision());
			out.writeInt(history.state());
			out.writeInt(history.saved());
			writeSelection(out, history.selection());
			writeSteps(out, history.undo());
			writeSteps(out, history.redo());
		} catch (IOException e) {
			return false;
		}
		byte[] body = bytes.toByteArray();
		CRC32 crc = new CRC32();
		crc.update(body, 0, body.length);
		return Journal.replace(dir, name(path), out -> {
			DataOutputStream data = new DataOutputStream(out);
			data.writeInt(MAGIC);
			data.writeInt(body.length);
			data.write(body);
			data.writeInt((int) crc.getValue());
			data.flush();
		});
	}

	/**
	 * Return the history kept for path when it was kept for the text of
	 * hash, else null; one that is not, or cannot be read, is deleted.
	 */
	Kept read(ProjectPath path, String hash) {
		File file = file(path);
		if (!file.isFile()) {
			return null;
		}
		Kept kept = null;
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

	private File file(ProjectPath path) {
		return new File(dir, name(path));
	}

	private static Kept parse(ProjectPath path, String hash, File file)
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
		boolean based = in.readBoolean();
		String base = in.readUTF();
		int revision = in.readInt();
		int state = in.readInt();
		int saved = in.readInt();
		Selection selection = readSelection(in);
		List<History.Step> undo = readSteps(in);
		List<History.Step> redo = readSteps(in);
		return in.available() == 0
				? new Kept(new History(undo, redo, revision, state, saved,
						selection), based ? base : null)
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
