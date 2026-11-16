package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * The unsaved text of the files of a session, one self-contained file
 * each, so it is recovered without the stored files: a header with the
 * base the text came from and the consent to overwrite, a snapshot of
 * the text, and change records appended after it without a sync. Each
 * part has its own checksum; a torn or damaged tail is cut at the last
 * good record, a damaged header or snapshot is reported and the file
 * left as it is. Text a reload replaces can be kept beside it, in files
 * of the same form: the journal of the file as it was, put aside by
 * one rename, so the file has no journal and every later write of
 * unsaved text is a journal like any other. Only the journal that
 * {@link #own owns} the folder writes and deletes there, so what a
 * project that is closed, or replaced by a later one, still asks for
 * changes nothing; any journal reads. Worker only, one call at a time.
 *
 * One rule holds for every file here, and {@link #delete},
 * {@link #move} and {@link #replace} are the only places that remove,
 * rename or write over one:
 * nothing is deleted because an operation failed. A file goes only
 * for a reason of {@link Gone}: its successor is written, durable
 * and read back; the user asked; its file has no unsaved text; or no
 * undo leads to it. A file that cannot be read now is kept as it is
 * and read again later; only bytes that were read and are wrong are
 * told as damaged, and those stay as well until the user gives them
 * up. No rename ever lands on a file that is there.
 */
final class Journal {
	/**
	 * Where a file's text came from: the stored revision it was last
	 * equal to, null for none, and whether the user chose to overwrite
	 * the stored revision keptOver, null for a file that was absent.
	 * replaced tells that a reload replaced the text with the stored
	 * one: the journal is kept until a history holds the text, and the
	 * text comes back with this mark when the app ends before.
	 */
	record Header(ProjectPath path, String base, boolean kept,
			String keptOver, boolean replaced) {
		Header(ProjectPath path, String base, boolean kept,
				String keptOver) {
			this(path, base, kept, keptOver, false);
		}
	}

	/**
	 * A rename of a file or folder in storage to to, or its deletion
	 * when to is null, that the journals have yet to follow. It is kept
	 * in the folder before storage is touched, so that a project opened
	 * after the process died finishes it or drops it, by what storage
	 * holds then.
	 */
	record Move(ProjectPath from, ProjectPath to) {
		/** Return whether path is from or lies under it. */
		boolean touches(ProjectPath path) {
			return path.equals(from) ||
					path.toString().startsWith(from + "/");
		}

		/**
		 * Return where path is after the move: null when it is deleted,
		 * path itself when the move does not touch it.
		 */
		ProjectPath moved(ProjectPath path) {
			if (!touches(path)) {
				return path;
			}
			ProjectParseResult<ProjectPath> moved = to == null
					? null
					: ProjectPath.parse(to + path.toString().substring(
							from.toString().length()));
			return moved != null && moved.isSuccess()
					? moved.value()
					: null;
		}
	}

	/** A change: length removed at start, and the text inserted. */
	record Change(int start, int removed, String inserted) {
	}

	/**
	 * What a journal holds: its header, the text as of its last good
	 * record and the revision of that record. text is null and problem
	 * tells why when the header or the snapshot is damaged or holds
	 * more than a document may: no more than that is taken into memory.
	 * tooLarge tells that the records after the snapshot made the text
	 * larger than {@link Document#MAX_BYTES}; it is recovered all the
	 * same. damaged tells that the file itself is damaged, so reading it
	 * again gives nothing else; when a text is null without it, the
	 * device had no memory to read a journal that may be whole.
	 */
	record Recovered(Header header, Text text, int revision, String problem,
			boolean tooLarge, boolean damaged) {
	}

	/**
	 * Text a reload replaced, kept beside the journal of its file under
	 * number; a later one has a higher number. The number is given
	 * once, is no other text's in the folder, and stays with the text
	 * whatever its file is renamed to: it is part of the name of the
	 * file that keeps it, so what is kept is told from the files alone.
	 */
	record Replaced(int number, Text text) {
	}

	/** Why a file of the folder may go; there is no other reason. */
	private enum Gone {
		/**
		 * Another file holds its text, or a newer text of the same
		 * document: written, made durable, and read back whole.
		 */
		SUCCEEDED,
		/** The user asked: a confirmed discard, or deleting the file. */
		ASKED,
		/** Its file has no unsaved text: storage holds what it has. */
		SAVED,
		/** Neither undo nor redo of its document lead to it. */
		UNREACHABLE,
		/**
		 * It never was a file of the folder: a write that did not
		 * finish, a note of a move, or a draft of the form before
		 * journals, which nothing reads.
		 */
		NO_TEXT
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
	private static final String REPLACED = ".replaced";
	private static final String TEMP_PREFIX = ".journal-";
	private static final String TEMP = ".tmp";
	private static final String MOVE = "moving";
	// What the format before this one called a journal.
	private static final String DRAFT = ".draft";
	// Records are rewritten into a snapshot once they exceed the
	// snapshot's size, but not before MIN_RECORDS and at the latest at
	// MAX_RECORDS bytes.
	private static final long MIN_RECORDS = 16 * 1024;
	private static final long MAX_RECORDS = 128 * 1024;
	// The most bytes a header and a snapshot are read as, checked
	// before any are taken into memory: a path and two revisions, and
	// the text of a document, whose characters take at most twice the
	// bytes here that they take in a file.
	private static final int MAX_HEAD = 256 * 1024;
	private static final long MAX_TEXT = 2 * Document.MAX_BYTES;
	// The most bytes a journal file is read as: more is no journal.
	private static final long MAX_FILE = 12L + MAX_HEAD + 8 + MAX_TEXT +
			MAX_RECORDS + 1024;

	// Where a journal ends and at which revision, as last read or
	// written.
	private record Tail(int revision, long length, long snapshot,
			long records) {
	}

	// The journal that writes and deletes in each folder.
	private static final Map<String, Journal> OWNERS = new HashMap<>();

	private final Map<ProjectPath, Tail> tails = new HashMap<>();
	private final File dir;
	private final Disk disk;
	// What the folder is owned by: the folder itself, however its path
	// is spelled.
	private final String key;

	Journal(File dir) {
		this(dir, Disk.REAL);
	}

	/** A journal in dir whose files are read and written through disk. */
	Journal(File dir, Disk disk) {
		this.dir = dir;
		this.disk = disk;
		key = key(dir);
	}

	private static String key(File dir) {
		try {
			return dir.getCanonicalPath();
		} catch (IOException e) {
			return dir.getAbsolutePath();
		}
	}

	/**
	 * Become the journal that writes and deletes in its folder, in
	 * place of the one that did; return this.
	 */
	Journal own() {
		synchronized (OWNERS) {
			OWNERS.put(key, this);
		}
		return this;
	}

	/** Stop owning the folder, so the next command changes nothing. */
	void release() {
		synchronized (OWNERS) {
			if (owns()) {
				OWNERS.remove(key);
			}
		}
	}

	/**
	 * Run abandon when no journal owns dir and it holds no file, while
	 * none can come to own it.
	 */
	static void ifAbandoned(File dir, Runnable abandon) {
		synchronized (OWNERS) {
			String[] kept = dir.list();
			if (!OWNERS.containsKey(key(dir)) &&
					(kept == null || kept.length == 0)) {
				abandon.run();
			}
		}
	}

	private boolean owns() {
		synchronized (OWNERS) {
			return OWNERS.get(key) == this;
		}
	}

	/**
	 * Replace the journal of header's path with text at revision, synced.
	 * Return whether it was written.
	 */
	boolean snapshot(Header header, int revision, Text text) {
		if (!owns()) {
			return false;
		}
		ProjectPath path = header.path();
		tails.remove(path);
		Tail tail = write(name(path), header, revision, text);
		if (tail != null) {
			tails.put(path, tail);
		}
		return tail != null;
	}

	// Write a file of header and text at revision as name; return where
	// it ends, or null when it was not written.
	private Tail write(String name, Header header, int revision,
			Text text) {
		byte[] head = header(header, revision);
		byte[] bytes = encode(text.toString());
		return replace(disk, dir, name, out -> {
			DataOutputStream data = new DataOutputStream(out);
			data.writeInt(MAGIC);
			writeBlock(data, head);
			writeBlock(data, bytes);
			data.flush();
		}, this::whole) ? new Tail(revision, 12L + head.length + 8 + bytes.length,
				bytes.length, 0) : null;
	}

	/**
	 * Append the changes that take the text of path from revision from
	 * to revision to, without a sync. Answer {@link Appended#SNAPSHOT}
	 * when the journal is not at from, cannot be read, or its records
	 * should be rewritten into a snapshot.
	 */
	Appended append(ProjectPath path, int from, int to,
			List<Change> changes) {
		if (!owns()) {
			return Appended.FAILED;
		}
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
			// Cut a torn or damaged tail before appending after it:
			// what follows the last record that was read back whole,
			// or was written whole, are bytes of no record.
			if (file.length() > tail.length()) {
				disk.truncate(file, tail.length());
			}
			disk.write(file, out -> {
				DataOutputStream data = new DataOutputStream(out);
				writeBlock(data, record);
				data.flush();
			}, true);
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

	/**
	 * Mark the journal of path as holding text a reload replaced, see
	 * {@link Header#replaced}. Return whether there is one that says so
	 * now.
	 */
	boolean markReplaced(ProjectPath path) {
		Recovered recovered = read(path);
		if (recovered == null || recovered.text() == null) {
			return false;
		}
		Header header = recovered.header();
		return header.replaced() || snapshot(new Header(path,
				header.base(), header.kept(), header.keptOver(), true),
				recovered.revision(), recovered.text());
	}

	/**
	 * Put the journal of path aside as text a reload is about to
	 * replace: made durable, then renamed, so from then on the file has
	 * no journal and the text is kept until {@link #forget} or
	 * {@link #putBack}. Only a journal that holds its text at revision;
	 * with -1, as it is. Return the number the text is kept under, or
	 * -1 when nothing was put aside: then the journal is as it was.
	 */
	int putAside(ProjectPath path, int revision) {
		if (!owns()) {
			return -1;
		}
		Recovered recovered = read(path);
		File[] all = list(name -> name.endsWith(REPLACED));
		if (recovered == null || recovered.text() == null || all == null ||
				revision >= 0 && recovered.revision() != revision) {
			return -1;
		}
		tails.remove(path);
		// One more than any text kept in the folder has, of any file.
		int number = 1;
		for (File kept : all) {
			number = Math.max(number, number(kept) + 1);
		}
		File file = file(path);
		try {
			disk.sync(file);
		} catch (IOException e) {
			return -1;
		}
		return move(file, replacedFile(path, number)) ? number : -1;
	}

	/**
	 * Take back {@link #putAside}: the reload did not replace the text
	 * after all. It is the journal again, unless one was written since,
	 * which is the newer; then it goes. When it can be neither, it
	 * stays where it is, as text undo leads to.
	 */
	void putBack(ProjectPath path, int number) {
		if (!owns()) {
			return;
		}
		File kept = replacedFile(path, number);
		File file = file(path);
		tails.remove(path);
		if (!kept.isFile()) {
			return;
		} else if (file.exists()) {
			delete(kept, Gone.SUCCEEDED, file);
		} else {
			move(kept, file);
		}
	}

	/**
	 * Make the newest text a reload replaced of path its journal, when
	 * it has none: the stored file is gone, so there is no text for
	 * undo to lead back from, and what is kept is the unsaved text of a
	 * file that is not there. Return whether path has a journal now.
	 */
	boolean promote(ProjectPath path) {
		File file = file(path);
		File[] kept = owns() ? replacedFiles(path) : null;
		if (file.exists() || kept == null || kept.length < 1) {
			return file.exists();
		}
		tails.remove(path);
		return move(kept[kept.length - 1], file);
	}

	/**
	 * Return the texts reloads replaced that are kept for path, oldest
	 * first. One that cannot be read now, or is damaged, is left out
	 * and stays as it is.
	 */
	List<Replaced> replaced(ProjectPath path) {
		List<Replaced> replaced = new ArrayList<>();
		File[] files = replacedFiles(path);
		for (File file : files != null ? files : new File[0]) {
			Recovered recovered;
			try {
				recovered = parse(path, file).recovered();
			} catch (OutOfMemoryError e) {
				continue;
			}
			if (recovered.text() != null) {
				replaced.add(new Replaced(number(file), recovered.text()));
			}
		}
		return replaced;
	}

	/**
	 * Return the files that have a text a reload replaced kept, as far
	 * as the folder can be read now.
	 */
	Set<ProjectPath> replacedPaths() {
		Set<ProjectPath> paths = new LinkedHashSet<>();
		File[] files = list(name -> name.endsWith(REPLACED));
		for (File file : files != null ? files : new File[0]) {
			Header header = header(file);
			if (header != null && file.getName().startsWith(
					digest(header.path()) + ".")) {
				paths.add(header.path());
			}
		}
		return paths;
	}

	/**
	 * Forget the text a reload replaced that is kept for path under
	 * number: nothing leads back to it anymore.
	 */
	void forget(ProjectPath path, int number) {
		if (owns()) {
			delete(replacedFile(path, number), Gone.UNREACHABLE, null);
		}
	}

	// Return the files of the texts reloads replaced of path, oldest
	// first; null when the folder cannot be listed now.
	private File[] replacedFiles(ProjectPath path) {
		String prefix = digest(path) + ".";
		File[] files = list(name -> name.startsWith(prefix) &&
				name.endsWith(REPLACED) && number(name) > 0);
		if (files != null) {
			Arrays.sort(files,
					(a, b) -> Integer.compare(number(a), number(b)));
		}
		return files;
	}

	private File replacedFile(ProjectPath path, int number) {
		return new File(dir, digest(path) + "." + number + REPLACED);
	}

	private static int number(File file) {
		return number(file.getName());
	}

	// Return the number in a name of digest.number.replaced, else 0.
	private static int number(String name) {
		int end = name.length() - REPLACED.length();
		int start = name.lastIndexOf('.', end - 1) + 1;
		try {
			return start > 0 && start < end
					? Integer.parseInt(name.substring(start, end))
					: 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// The one place a file of the folder is deleted: for why, and
	// after another only when that successor is there and whole.
	// Return whether file is gone.
	private boolean delete(File file, Gone why, File successor) {
		if (why == Gone.SUCCEEDED && (successor == null ||
				!successor.isFile() || !whole(successor))) {
			return false;
		}
		return disk.delete(file);
	}

	// The one place a file of the folder is renamed: never onto one
	// that is there. Return whether from has the name to now.
	private boolean move(File from, File to) {
		return !to.exists() && disk.rename(from, to);
	}

	// Return whether file can be read now and holds a header and a
	// text that are whole.
	private boolean whole(File file) {
		try (DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(disk.read(file, MAX_FILE)))) {
			return readMagic(in) && readBlock(in, MAX_HEAD) != null &&
					readBlock(in, MAX_TEXT) != null;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			return false;
		}
	}

	/**
	 * Keep move as under way, or that none is when null. Return whether
	 * that is kept.
	 */
	boolean setMove(Move move) {
		if (!owns()) {
			return false;
		}
		if (move == null) {
			return delete(new File(dir, MOVE), Gone.NO_TEXT, null);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC);
			out.writeUTF(move.from().toString());
			out.writeBoolean(move.to() != null);
			out.writeUTF(move.to() != null ? move.to().toString() : "");
		} catch (IOException e) {
			return false;
		}
		byte[] note = bytes.toByteArray();
		return replace(disk, dir, MOVE, out -> out.write(note), null);
	}

	/** Return the move that is under way, or null. */
	Move move() {
		File file = new File(dir, MOVE);
		if (!file.isFile() || file.length() > MAX_HEAD) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(disk.read(file, MAX_HEAD)))) {
			if (in.readInt() != MAGIC) {
				return null;
			}
			ProjectParseResult<ProjectPath> from = ProjectPath.parse(
					in.readUTF());
			ProjectParseResult<ProjectPath> to = in.readBoolean()
					? ProjectPath.parse(in.readUTF())
					: null;
			return from.isSuccess() && (to == null || to.isSuccess())
					? new Move(from.value(), to != null ? to.value() : null)
					: null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Let the journals follow move, which storage made: each one at or
	 * under its source is kept under the new path, with the text and
	 * header it has, or deleted with its file. One written under the
	 * new path since is the newer and stays. Texts reloads replaced
	 * follow under the numbers they have. Return whether none is left
	 * behind; one that cannot be read or written now is, and follows
	 * when this is asked again.
	 */
	boolean follow(Move move) {
		if (!owns()) {
			return false;
		}
		Scan scan = scan();
		// One that cannot be read now may be one of those to follow.
		boolean followed = scan.complete();
		Set<ProjectPath> paths = new LinkedHashSet<>(
				scan.headers().keySet());
		// One whose header cannot be read is known by its name alone.
		paths.add(move.from());
		for (ProjectPath path : paths) {
			if (move.touches(path)) {
				followed &= follow(path, move.moved(path));
			}
		}
		return followed & followReplaced(move);
	}

	// Let the texts reloads replaced follow move: kept for the new path
	// under the numbers they have, or deleted with their file.
	private boolean followReplaced(Move move) {
		File[] files = list(name -> name.endsWith(REPLACED));
		if (files == null) {
			return false;
		}
		boolean followed = true;
		for (File file : files) {
			Header header;
			try {
				header = head(file);
			} catch (IOException e) {
				// It may be one of those to follow: asked again later.
				followed = false;
				continue;
			}
			if (header == null) {
				// Of the file itself by its name; of no file that can
				// be told otherwise, so it stays where it is.
				if (move.to() == null && file.getName().startsWith(
						digest(move.from()) + ".")) {
					followed &= delete(file, Gone.ASKED, null);
				}
				continue;
			} else if (!move.touches(header.path()) || !file.getName()
					.startsWith(digest(header.path()) + ".")) {
				continue;
			}
			ProjectPath to = move.moved(header.path());
			if (to == null) {
				followed &= delete(file, Gone.ASKED, null);
				continue;
			}
			File target = replacedFile(to, number(file));
			if (!target.exists()) {
				Recovered kept = parse(header.path(), file).recovered();
				if (kept.text() == null || write(target.getName(),
						new Header(to, header.base(), header.kept(),
								header.keptOver()), kept.revision(),
						kept.text()) == null) {
					followed = false;
					continue;
				}
			}
			followed &= delete(file, Gone.SUCCEEDED, target);
		}
		return followed;
	}

	private boolean follow(ProjectPath from, ProjectPath to) {
		tails.remove(from);
		File old = file(from);
		if (!old.exists()) {
			return true;
		} else if (to == null) {
			return delete(old, Gone.ASKED, null);
		}
		tails.remove(to);
		File target = file(to);
		if (target.exists()) {
			// Written there since, or by this before the process died.
			return delete(old, Gone.SUCCEEDED, target);
		}
		Recovered recovered = read(from);
		if (recovered == null) {
			return true;
		} else if (recovered.text() != null) {
			Header header = recovered.header();
			return snapshot(new Header(to, header.base(), header.kept(),
					header.keptOver(), header.replaced()),
					recovered.revision(), recovered.text()) &&
					delete(old, Gone.SUCCEEDED, target);
		}
		// Damaged, it is told under the new name as it is; one that
		// only cannot be read now stays, to follow when it can.
		return recovered.damaged() && move(old, target);
	}

	/**
	 * Return the header of the journal of path without reading its
	 * text, or null when there is none that can be read.
	 */
	Header header(ProjectPath path) {
		return header(file(path));
	}

	private Header header(File file) {
		try {
			return head(file);
		} catch (IOException e) {
			return null;
		}
	}

	// Return the header of file, null when it is not there or holds
	// none that is whole; it throws when file cannot be read now.
	private Header head(File file) throws IOException {
		if (!file.isFile()) {
			return null;
		}
		byte[] bytes = disk.read(file, MAX_HEAD + 16);
		try (DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(bytes))) {
			byte[] head = readMagic(in) ? readBlock(in, MAX_HEAD) : null;
			return head != null ? header(new DataInputStream(
					new ByteArrayInputStream(head))) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Return what the journal of path holds, or null when none. */
	Recovered read(ProjectPath path) {
		File file = file(path);
		if (!file.isFile()) {
			return null;
		}
		Parsed parsed;
		try {
			parsed = parse(path, file);
		} catch (OutOfMemoryError e) {
			// It may be whole: to be read again, never given up.
			parsed = new Parsed(problem(path,
					"it is too large for this device", false), null);
		}
		if (parsed.tail() != null) {
			tails.put(path, parsed.tail());
		} else {
			tails.remove(path);
		}
		return parsed.recovered();
	}

	// What a file of the form of a journal holds, and where it ends.
	private record Parsed(Recovered recovered, Tail tail) {
	}

	// Return what file holds as the journal of path, or as text of it
	// that was put aside.
	private Parsed parse(ProjectPath path, File file) {
		byte[] all;
		try {
			all = disk.read(file, MAX_FILE);
		} catch (IOException e) {
			// Whatever it holds, it is as it was: to be read again.
			return new Parsed(problem(path, "it cannot be read now",
					false), null);
		}
		try (DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(all))) {
			byte[] head = readMagic(in) ? readBlock(in, MAX_HEAD) : null;
			if (head == null) {
				return damaged(path, "its header is damaged");
			}
			Header header;
			int revision;
			try (DataInputStream fields = new DataInputStream(
					new ByteArrayInputStream(head))) {
				header = header(fields);
				fields.reset();
				revision = revision(fields);
			}
			if (!path.equals(header.path())) {
				return damaged(path, "it belongs to " + header.path());
			}
			byte[] bytes = readBlock(in, MAX_TEXT);
			if (bytes == null) {
				return damaged(path, "its text is damaged");
			} else if (fileBytes(bytes) > Document.MAX_BYTES) {
				return damaged(path,
						"its text is larger than a document may be");
			}
			Text text = Text.of(decode(bytes));
			long length = 12L + head.length + 8 + bytes.length;
			long records = 0;
			// No more records than are ever appended, all together.
			for (byte[] record; (record = readBlock(in,
					MAX_RECORDS - records - 8)) != null; ) {
				Replayed replayed = replay(record, revision, text);
				if (replayed == null) {
					break;
				}
				revision = replayed.revision;
				text = replayed.text;
				length += record.length + 8;
				records += record.length + 8;
			}
			return new Parsed(new Recovered(header, text, revision, null,
					TextBytes.length(text) > Document.MAX_BYTES, false),
					new Tail(revision, length, bytes.length, records));
		} catch (IOException | RuntimeException e) {
			return damaged(path, "it is no journal");
		}
	}

	/**
	 * The journals of a folder: the header of every one that has a
	 * readable one, by path, and how many have none, so no file is
	 * known for them until the file they are named after is opened.
	 * complete tells that the folder and every journal in it could be
	 * read now, so that none is missing for that alone.
	 */
	record Scan(Map<ProjectPath, Header> headers, int damaged,
			boolean complete) {
	}

	/**
	 * Return the header of every journal that has a readable one, by
	 * path; the texts are not read.
	 */
	Map<ProjectPath, Header> headers() {
		return scan().headers();
	}

	/** Return the journals of the folder; the texts are not read. */
	Scan scan() {
		Map<ProjectPath, Header> headers = new LinkedHashMap<>();
		int damaged = 0;
		File[] files = list(name -> name.endsWith(SUFFIX));
		boolean complete = files != null;
		for (File file : files != null ? files : new File[0]) {
			int known = headers.size();
			// Not a journal anyone can read stays as it is.
			Header header = null;
			try {
				header = head(file);
			} catch (IOException e) {
				complete = false;
			}
			if (header != null &&
					file.getName().equals(name(header.path()))) {
				headers.put(header.path(), header);
			}
			damaged += headers.size() == known ? 1 : 0;
		}
		return new Scan(headers, damaged, complete);
	}

	/**
	 * Forget the journal of path, whose file has no unsaved text;
	 * return whether none is kept now.
	 */
	boolean remove(ProjectPath path) {
		if (!owns()) {
			return false;
		}
		tails.remove(path);
		return delete(file(path), Gone.SAVED, null);
	}

	/**
	 * Forget the journal of path if it is still damaged; one written
	 * since stays, and so does one this device only has no memory to
	 * read. Return whether none that cannot be read is kept now.
	 */
	boolean dismiss(ProjectPath path) {
		if (!owns()) {
			return false;
		}
		Recovered recovered = read(path);
		tails.remove(path);
		return recovered == null || recovered.text() != null ||
				recovered.damaged() &&
						delete(file(path), Gone.ASKED, null);
	}

	/**
	 * Make every journal durable, as when the app goes to the
	 * background; return whether all are.
	 */
	boolean sync() {
		File[] files = list(name -> name.endsWith(SUFFIX));
		boolean synced = files != null;
		for (File file : files != null ? files : new File[0]) {
			try {
				disk.sync(file);
			} catch (IOException e) {
				synced = false;
			}
		}
		return synced;
	}

	/**
	 * Delete what earlier versions and crashes left in the folder: the
	 * drafts of the format before this one and unfinished snapshots.
	 */
	void removeLeftovers() {
		File[] files = !owns() ? null : list(name -> name.endsWith(DRAFT) ||
				name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP));
		for (File file : files != null ? files : new File[0]) {
			delete(file, Gone.NO_TEXT, null);
		}
	}

	private interface Named {
		boolean is(String name);
	}

	// Return the files of the folder whose name is named; null when
	// the folder is there and cannot be listed now.
	private File[] list(Named named) {
		File[] files = disk.list(dir);
		if (files == null && dir.exists()) {
			return null;
		}
		List<File> listed = new ArrayList<>();
		for (File file : files != null ? files : new File[0]) {
			if (named.is(file.getName())) {
				listed.add(file);
			}
		}
		return listed.toArray(new File[0]);
	}

	File file(ProjectPath path) {
		return new File(dir, name(path));
	}

	/**
	 * Replace the file name in dir with bytes in one rename, creating
	 * dir when it is missing. Return whether it was written.
	 */
	static boolean replace(File dir, String name, byte[] bytes) {
		return replace(dir, name, out -> out.write(bytes));
	}

	/**
	 * Replace the file name in dir with what source writes, synced, as
	 * {@link #replace(File, String, byte[])} does.
	 */
	static boolean replace(File dir, String name,
			ProjectFiles.Source source) {
		return replace(Disk.REAL, dir, name, source, null);
	}

	/** Tells whether a file just written holds what it should. */
	interface Check {
		boolean whole(File file);
	}

	/**
	 * The same, through disk: written to a file of its own, made
	 * durable, read back by check when there is one, and only then
	 * given the name, in place of what had it. When any of that fails,
	 * what had the name has it still.
	 */
	static boolean replace(Disk disk, File dir, String name,
			ProjectFiles.Source source, Check check) {
		File temp = null;
		try {
			temp = disk.temp(dir, TEMP_PREFIX, TEMP);
			disk.write(temp, source, false);
			disk.sync(temp);
			if ((check == null || check.whole(temp)) &&
					disk.rename(temp, new File(dir, name))) {
				temp = null;
				return true;
			}
			return false;
		} catch (IOException e) {
			return false;
		} finally {
			if (temp != null) {
				// Never a file of the folder: a write that did not
				// finish.
				disk.delete(temp);
			}
		}
	}

	private static Parsed damaged(ProjectPath path, String problem) {
		return new Parsed(problem(path, problem, true), null);
	}

	private static Recovered problem(ProjectPath path, String problem,
			boolean damaged) {
		return new Recovered(null, null, -1,
				"the unsaved text of " + path + " cannot be recovered: " +
						problem, false, damaged);
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
			out.writeBoolean(header.replaced());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	// Read the header of a header block; one written before the mark
	// of a reload was kept ends after the revision.
	private static Header header(DataInputStream in) throws IOException {
		ProjectParseResult<ProjectPath> path = ProjectPath.parse(
				in.readUTF());
		if (!path.isSuccess()) {
			throw new IOException("not a path");
		}
		String base = readRevision(in);
		boolean kept = in.readBoolean();
		String keptOver = readRevision(in);
		in.readInt();
		return new Header(path.value(), base, kept, keptOver,
				in.available() > 0 && in.readBoolean());
	}

	// Return the revision a header block names.
	private static int revision(DataInputStream in) throws IOException {
		in.readUTF();
		readRevision(in);
		in.readBoolean();
		readRevision(in);
		return in.readInt();
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

	// Return the next block, or null at the end, at a torn block, one
	// of more than max bytes or one whose checksum does not match.
	private static byte[] readBlock(DataInputStream in, long max)
			throws IOException {
		try {
			int length = in.readInt();
			// More than the file has left is a damaged length.
			if (length < 0 || length > max || length > in.available()) {
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

	// Return how many bytes the text encoded in bytes has in a file, as
	// Document.bytes counts them, without decoding it.
	private static long fileBytes(byte[] bytes) {
		long count = 0;
		for (int i = 0, n = bytes.length; i < n; ) {
			int b = bytes[i] & 0xff;
			if (b < 0x80) {
				i += 1;
				count += 1;
			} else if ((b & 0xe0) == 0xc0) {
				i += 2;
				count += 2;
			} else {
				// A surrogate is half of four bytes.
				count += b == 0xed && i + 1 < n &&
						(bytes[i + 1] & 0xe0) == 0xa0 ? 2 : 3;
				i += 3;
			}
		}
		return count;
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
		return digest(path) + SUFFIX;
	}

	private static String digest(ProjectPath path) {
		String digest = StoredFiles.revision(
				path.toString().getBytes(StandardCharsets.UTF_8));
		if (digest == null) {
			throw new IllegalStateException("SHA-256 is not available");
		}
		return digest.substring(digest.indexOf(':') + 1);
	}
}
