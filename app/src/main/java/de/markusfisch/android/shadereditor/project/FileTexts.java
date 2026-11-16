package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.OpenProject.Outcome;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;

/**
 * Files of a project opened as {@link Text} for the editor, saved from
 * it, and their unsaved text kept in a {@link Journal}. Everything that
 * costs time in proportion to a file, reading, decoding, splitting into
 * lines, encoding and writing, runs on the worker; the main thread only
 * hands over and receives immutable values. The worker runs one task
 * at a time, in the order given; results are posted to main. A text is
 * what a file holds when opening the file gives exactly that text,
 * character for character: that is what saved means here, for a save
 * as for a comparison, so a text a file cannot hold is never saved.
 */
public final class FileTexts {
	public interface Callback<T> {
		void done(T result);
	}

	/**
	 * What opening a file found: {@link Opened} or {@link Unopened};
	 * either tells why the journal of the file could not be read, else
	 * null, and whether it is damaged, so that reading it again finds
	 * the same.
	 */
	public interface Opening {
		String unreadable();

		boolean damaged();
	}

	/**
	 * A file for the editor: a document nobody else has yet, of its
	 * journal's text when unsaved, else of the stored text, with the
	 * undo history kept for that text; the revision of the stored text
	 * it is based on, null for none; the revision of the journal it came
	 * from, -1 for none, the identity of that journal, and whether a
	 * note of a rename or a deletion names it, so that it is the text
	 * of the file that left the name; and why its
	 * journal could not be read, so the
	 * stored text was opened instead, else null. A document of unsaved
	 * text starts modified, whatever its history; replaced are the
	 * texts reloads replaced that are kept for the file, each with the
	 * state of the document undo or redo find it at. It is made on the
	 * worker, so a device without the memory for it is told there.
	 */
	public record Opened(Document document, boolean unsaved,
			String revision, int journal, long id, boolean noted,
			String unreadable, boolean damaged,
			List<FileRecord.Replaced> replaced)
			implements Opening {
		public Text text() {
			return document.text();
		}
	}

	/**
	 * A file that cannot be edited; reason tells the user why, and
	 * found, never SAME or CHANGED, what storage holds in its place,
	 * at revision when one is known.
	 */
	public record Unopened(String reason, String unreadable,
			boolean damaged, Found found, String revision)
			implements Opening {
	}

	/**
	 * What a save did, the state of the document it took the text at,
	 * which a {@link OpenProject.Saved} outcome marks as saved, and
	 * what storage holds after a {@link OpenProject.Conflict}, else
	 * null.
	 */
	public record Save(int state, Outcome outcome, Checked found) {
	}

	/** What storage holds of a file, compared with what it was. */
	public enum Found {
		SAME, CHANGED, MISSING, UNAVAILABLE, TOO_LARGE, NOT_TEXT
	}

	/**
	 * What a check of a file found: what storage holds compared with the
	 * base, the revision it holds, null when none is known, the stored
	 * text when it changed and was asked for, and whether storage holds
	 * exactly the unsaved text it was compared with.
	 */
	public record Checked(Found found, Text text, String revision,
			boolean equal) {
	}

	private final ProjectFiles store;
	private final Journal journal;
	private final Histories histories;
	private final Executor worker;
	private final Executor main;

	/**
	 * What the app keeps by path about the files of a project beside
	 * their journals and histories, such as the settings and pictures
	 * of the library. It follows a rename and goes with a deletion in
	 * the same task, and when a project opens after the process died
	 * in one.
	 */
	interface Kept {
		/** Return whether nothing is left behind under the old path. */
		boolean follow(Journal.Move move);
	}

	// Set before anything is asked of this; null for none.
	Kept kept;
	// The rename storage made last, noted with the files it is for,
	// until main says that it knows of it; and a move storage made
	// that the journals could not follow yet. Null for none; worker
	// only.
	private Journal.Note noted;
	private Journal.Note unfollowed;

	FileTexts(ProjectFiles store, Journal journal, Histories histories,
			Executor worker, Executor main) {
		this.store = store;
		this.journal = journal;
		this.histories = histories;
		this.worker = worker;
		this.main = main;
	}

	/**
	 * Open path for the editor, from its journal when it has one that
	 * can be read. size is its size in bytes as the listing told it, or
	 * {@link ProjectFiles#UNKNOWN}; a file larger than
	 * {@link Document#MAX_BYTES} is refused before it is read when the
	 * size is known, after it is read otherwise.
	 */
	public void open(ProjectPath path, long size, Callback<Opening> done) {
		run(() -> {
			Journal.Recovered recovered = journal.read(path);
			Unread unread = recovered != null && recovered.text() == null
					? new Unread(recovered.problem(), recovered.damaged())
					: astray(path) ? new Unread(ASTRAY, false) : null;
			Opening opening;
			try {
				opening = withHistory(path, unread == null &&
						recovered != null
						? recovered(path, recovered, noted(path,
								recovered.header().id()))
						: read(path, size, unread));
			} catch (OutOfMemoryError e) {
				opening = tooLargeHere(path, unread);
			}
			Opening opened = opening;
			main.execute(() -> done.done(opened));
		});
	}

	/**
	 * What a reload read, and the number the journal of the file was
	 * put aside under before, as text the reload replaces, else -1.
	 */
	public record Reloaded(Opening opening, int aside) {
	}

	/**
	 * Open path for the editor as storage has it; when expect, only if
	 * storage is at seen. With held at 0 or more, the stored text is to
	 * take the place of unsaved text the journal holds at that write:
	 * before main is told, so before it takes the stored text, that
	 * journal is put aside, durable, as text a reload replaced, and
	 * only if it holds exactly that write. From then on the file has
	 * no journal, whatever happens to main.
	 */
	public void reload(ProjectPath path, int held, boolean expect,
			String seen, Callback<Reloaded> done) {
		run(() -> {
			Opening opening;
			try {
				opening = read(path, ProjectFiles.UNKNOWN, null);
			} catch (OutOfMemoryError e) {
				opening = tooLargeHere(path, null);
			}
			Reloaded reloaded = new Reloaded(opening, held >= 0 &&
					opening instanceof Opened opened && (!expect ||
					Objects.equals(seen, opened.revision()))
					? journal.putAside(path, held)
					: -1);
			main.execute(() -> done.done(reloaded));
		});
	}

	/**
	 * The reload did not take the stored text after all: the journal
	 * put aside under number is the journal of path again, unless one
	 * was written since.
	 */
	void putBack(ProjectPath path, int number) {
		run(() -> journal.putBack(path, number));
	}

	/**
	 * Forget the text a reload replaced that is kept for path under
	 * number: neither undo nor redo lead to it anymore.
	 */
	void forget(ProjectPath path, int number) {
		run(() -> journal.forget(path, number));
	}

	/**
	 * Save text, taken from the document at state, to path, expecting
	 * base, the revision the text came from. Call with null for the
	 * text the journal of path holds, which stays until main has it
	 * removed; the outcome is null when there is none, and failed when
	 * it cannot be read. The text is immutable, so editing on meanwhile
	 * changes nothing of what is written.
	 */
	public void save(ProjectPath path, Text text, int state, String base,
			Callback<Save> done) {
		run(() -> {
			Save save = save(path, text, base, state);
			main.execute(() -> done.done(save));
		});
	}

	/**
	 * Do what flush asks of the journal of path; done gets whether the
	 * journal holds what flush names now.
	 */
	void journal(ProjectPath path, FileRecord.Flush flush,
			Callback<Boolean> done) {
		run(() -> {
			// Not while the journal of the file may be under the name
			// it had: then nothing of it is where this would write.
			boolean written = !astray(path) && write(path, flush);
			main.execute(() -> done.done(written));
		});
	}

	/**
	 * Keep history, of text, for path; nothing but undo depends on it
	 * being written.
	 */
	void keepHistory(ProjectPath path, Text text,
			Document.History history) {
		run(() -> {
			String hash = Histories.hash(text);
			if (hash != null) {
				histories.write(path, hash, history);
			}
		});
	}

	/** Forget the history of path. */
	void forgetHistory(ProjectPath path) {
		run(() -> histories.remove(path));
	}

	/**
	 * Delete the journal of path if it still cannot be read; done gets
	 * whether none that cannot be read is kept now.
	 */
	void dismiss(ProjectPath path, Callback<Boolean> done) {
		run(() -> {
			boolean removed = journal.dismiss(path);
			main.execute(() -> done.done(removed));
		});
	}

	/** Count the journals whose header cannot be read. */
	void damaged(Callback<Integer> done) {
		run(() -> {
			int damaged = journal.scan().damaged();
			main.execute(() -> done.done(damaged));
		});
	}

	/**
	 * Rename a file or folder in storage as move tells, or delete it,
	 * as one task: note in the journal's folder that it is under way,
	 * then change storage; done gets what storage did. A deletion takes
	 * the journals and histories at and under the path with it at once.
	 * After a rename they stay where they are until {@link #follow},
	 * so what main writes before it knows still lands with them.
	 */
	void move(Journal.Move move, Callback<ProjectFiles.Change> done) {
		run(() -> {
			ProjectFiles.Change change;
			// One move is noted at a time: not while the journals have
			// yet to follow the one before. It is noted with the files
			// it is for, so not while those cannot be read either.
			Journal.Note note = unfollowed != null || noted != null
					? null
					: journal.noted(move);
			if (note == null || !journal.setNote(note)) {
				change = new ProjectFiles.Refused(
						ProjectFiles.Refusal.FAILED,
						"the journal cannot be written");
			} else {
				change = move.to() != null
						? store.rename(move.from(), move.to())
						: store.delete(move.from());
				if (move.to() == null && change instanceof
						ProjectFiles.Refused refused && refused.refusal() ==
						ProjectFiles.Refusal.ABSENT) {
					// Gone already: what is left to delete is the
					// project's part of it.
					change = new ProjectFiles.Done(null);
				}
				if (change instanceof ProjectFiles.Done) {
					if (move.to() != null) {
						noted = note;
					} else {
						unfollowed = follow(note);
					}
				} else if (((ProjectFiles.Refused) change).refusal() !=
						ProjectFiles.Refusal.PARTIAL) {
					// Nothing happened; half of it is looked at when
					// the project opens again.
					journal.setNote(null);
				}
			}
			ProjectFiles.Change made = change;
			main.execute(() -> done.done(made));
		});
	}

	/**
	 * Let the journals and histories follow a rename storage made, once
	 * main knows of it.
	 */
	void follow(Journal.Move move) {
		run(() -> {
			Journal.Note before = noted;
			noted = null;
			// Main knew the files by their old names until now: what it
			// had written there since the move was noted, a first
			// journal of one of them, is theirs and follows too.
			Journal.Note now = journal.noted(move);
			if (now != null && !now.equals(before)) {
				journal.setNote(now);
			}
			Journal.Note note = now != null ? now : before;
			unfollowed = note != null ? follow(note) : null;
		});
	}

	// Give the worker task, which first lets the journals follow a
	// rename they could not follow when it was made: until they did,
	// what the task is for is not where the task looks for it.
	private void run(Runnable task) {
		worker.execute(() -> {
			if (unfollowed != null) {
				unfollowed = follow(unfollowed);
			}
			task.run();
		});
	}

	private static final String ASTRAY = "the unsaved text cannot be " +
			"recovered: it cannot be read now";

	// Return whether the journal of path may still be under the name
	// its file had before a rename that the journals could not follow
	// yet: then what is under the name of path is not all there is.
	private boolean astray(ProjectPath path) {
		Journal.Move move = unfollowed != null ? unfollowed.move() : null;
		if (move == null || move.to() == null ||
				!new Journal.Move(move.to(), null).touches(path)) {
			return false;
		}
		for (Journal.Noted noted : unfollowed.files()) {
			if (!noted.followed()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Finish or drop a move the process died in: when storage made it,
	 * the journals and histories follow; when it did not, or has
	 * neither the old name nor the new anymore, nothing is to do, and
	 * what is under the old name stays there; while storage cannot
	 * tell, the note stays as it is. Return the move that was finished
	 * now, else null. On the worker, before the journals are looked at.
	 */
	Journal.Move recover() {
		Journal.Note note = journal.note();
		if (note == null) {
			return null;
		}
		Journal.Move move = note.move();
		Boolean from = exists(move.from());
		Boolean to = move.to() != null ? exists(move.to()) : Boolean.TRUE;
		if (Boolean.FALSE.equals(from) && Boolean.TRUE.equals(to)) {
			// Once more where a file could not be read or written
			// just then; else it stays noted for the next opening.
			Journal.Note left = follow(note);
			return left == null || follow(left) == null ? move : null;
		} else if (Boolean.TRUE.equals(from) ||
				Boolean.FALSE.equals(from) && Boolean.FALSE.equals(to)) {
			journal.setNote(null);
		}
		return null;
	}

	// Return whether a note that stands names the journal of path, of
	// identity id: one that could not be followed yet in this process,
	// or one a project before left. A note that is there and cannot be
	// read now may name it.
	private boolean noted(ProjectPath path, long id) {
		if (unfollowed != null) {
			return journal.names(unfollowed, path, id);
		} else if (!journal.moving()) {
			return false;
		}
		Journal.Note note = journal.note();
		return note == null || journal.names(note, path, id);
	}

	// Let the journals, the histories and what is kept by path follow
	// the move of note. Return what is left of it to follow, which
	// stays noted so that the next task, or the next opening, tries
	// again; null when nothing is.
	private Journal.Note follow(Journal.Note note) {
		if (note.move().to() == null &&
				Boolean.TRUE.equals(exists(note.move().from()))) {
			// Storage has a file there again: the deletion is over,
			// and what is under the name now is that file's.
			journal.setNote(null);
			return null;
		}
		Journal.Note left = journal.follow(note);
		histories.follow(note.move());
		boolean followed = left.files().isEmpty() &&
				(kept == null || kept.follow(note.move()));
		if (followed && journal.setNote(null)) {
			return null;
		} else if (!left.equals(note)) {
			// What is done is noted as done.
			journal.setNote(left);
		}
		return left;
	}

	/**
	 * A file found to have unsaved text when the journals were looked
	 * at: the header of its journal, what storage holds of it, and
	 * whether a note of a rename or a deletion names that journal.
	 */
	record Unsaved(Journal.Header header, Found found, String revision,
			boolean noted) {
	}

	/**
	 * What a look at the journals found: the files with unsaved text,
	 * how many journals have no header that can be read, and whether
	 * the folder and every journal in it could be read, so that none
	 * is missing for that alone.
	 */
	record Journals(List<Unsaved> unsaved, int damaged, boolean complete) {
	}

	/**
	 * Look at the journals of the session, but for those of the files
	 * known, which main has records of. One whose text storage holds
	 * exactly is no unsaved text and goes; text a reload replaced of a
	 * file that is gone and has no journal becomes its journal. On the
	 * worker.
	 */
	Journals journals(Set<ProjectPath> known) {
		List<Unsaved> unsaved = new ArrayList<>();
		Journal.Scan scan = journal.scan();
		Journal.Move pending = unfollowed != null ? unfollowed.move() : null;
		for (Journal.Header header : scan.headers().values()) {
			ProjectPath path = header.path();
			// Not one that is yet to follow its file to another name.
			if (known.contains(path) ||
					pending != null && pending.touches(path)) {
				continue;
			}
			Checked checked = check(path, header.base(), null, true);
			boolean stored = checked.found() == Found.SAME ||
					checked.found() == Found.CHANGED;
			if (checked.equal()) {
				// Storage holds the text: saved before the process died,
				// or written elsewhere.
				journal.remove(path);
			} else if (header.replaced() && stored &&
					journal.putAside(path, -1) >= 0) {
				// A journal of the form before this one that held text
				// a reload replaced: the file opens as stored, as the
				// user asked, and undo leads to the text.
			} else {
				unsaved.add(new Unsaved(header, checked.found(),
						checked.revision(), noted(path, header.id())));
			}
		}
		// Text a reload replaced, of a file that is gone and has no
		// journal: there is no stored text for undo to lead back from,
		// so the newest of it is the unsaved text of a file that is
		// not there, to be found where such text is listed.
		// Not while a move is noted that such a text may have yet to
		// follow: then it is of the file under its new name, and stays
		// what undo leads to there.
		Journal.Note noted = unfollowed != null ? unfollowed : journal.note();
		boolean moving = noted == null && journal.moving();
		Journal.Kept kept = journal.kept();
		for (ProjectPath path : kept.paths()) {
			if (!moving && (noted == null ||
					!noted.move().touches(path)) &&
					!known.contains(path) &&
					!scan.headers().containsKey(path) &&
					check(path, null, null, false).found() ==
							Found.MISSING && journal.promote(path)) {
				Journal.Header header = journal.header(path);
				if (header != null) {
					unsaved.add(new Unsaved(header, Found.MISSING, null,
							false));
				}
			}
		}
		return new Journals(unsaved, scan.damaged(), scan.complete() &&
				kept.complete());
	}

	/** {@link #journals(Set)}, as a task; done gets it. */
	void journals(Set<ProjectPath> known, Callback<Journals> done) {
		run(() -> {
			Journals journals = journals(known);
			main.execute(() -> done.done(journals));
		});
	}

	// Return whether storage has a file or folder at path, or null
	// when it cannot tell.
	private Boolean exists(ProjectPath path) {
		Read read = store.read(path, 0);
		if (read instanceof Content ||
				read instanceof ProjectFiles.Failed failed &&
						ProjectFiles.TOO_LARGE.equals(failed.reason())) {
			return true;
		}
		ProjectFiles.Listing listing = store.list(path);
		return listing instanceof ProjectFiles.Entries
				? Boolean.TRUE
				: read == null ? Boolean.FALSE : null;
	}

	/**
	 * Let the journal go once everything given before is written: what
	 * is asked of it later changes nothing.
	 */
	void release() {
		worker.execute(journal::release);
	}

	/**
	 * Keep header in the journal of its path, for a file not in memory.
	 */
	void header(Journal.Header header, Callback<Boolean> done) {
		run(() -> {
			boolean kept = journal.setHeader(header);
			main.execute(() -> done.done(kept));
		});
	}

	/**
	 * Make what the journal holds durable once everything given before
	 * is written; done gets whether it is.
	 */
	void sync(Callback<Boolean> done) {
		run(() -> {
			boolean synced = journal.sync();
			main.execute(() -> done.done(synced));
		});
	}

	/**
	 * Read path again for a document without unsaved text. done gets
	 * what storage holds compared with base, with the stored text when
	 * it changed.
	 */
	public void reread(ProjectPath path, String base,
			Callback<Checked> done) {
		run(() -> {
			Checked checked = check(path, base, null, false);
			main.execute(() -> done.done(checked));
		});
	}

	/**
	 * Compare what storage holds of path with base, for the unsaved
	 * text mine, or that of its journal when null; done gets what it
	 * found and whether storage holds exactly that text.
	 */
	public void compare(ProjectPath path, String base, Text mine,
			Callback<Checked> done) {
		run(() -> {
			Checked checked = check(path, base, mine, true);
			main.execute(() -> done.done(checked));
		});
	}

	/**
	 * Return what storage holds of path compared with base; on the
	 * worker. For unsaved text, mine or that of the journal when null,
	 * whether storage holds exactly that text; else the stored text when
	 * it changed.
	 */
	Checked check(ProjectPath path, String base, Text mine,
			boolean unsaved) {
		try {
			return compare(path, base, mine, unsaved);
		} catch (OutOfMemoryError e) {
			return new Checked(Found.TOO_LARGE, null, null, false);
		}
	}

	private Checked compare(ProjectPath path, String base, Text mine,
			boolean unsaved) {
		Read read = store.read(path, Document.MAX_BYTES);
		if (read == null) {
			return new Checked(Found.MISSING, null, null, false);
		}
		if (read instanceof ProjectFiles.Failed failed &&
				ProjectFiles.TOO_LARGE.equals(failed.reason())) {
			return new Checked(Found.TOO_LARGE, null, null, false);
		}
		if (!(read instanceof Content content)) {
			return new Checked(Found.UNAVAILABLE, null, null, false);
		}
		String revision = content.revision();
		if (revision.equals(base)) {
			// Text typed back to what is stored is saved too.
			return new Checked(Found.SAME, null, base, unsaved &&
					holds(content.bytes(), path, mine));
		} else if (content.bytes().length > Document.MAX_BYTES) {
			return new Checked(Found.TOO_LARGE, null, revision, false);
		} else if (unsaved) {
			// What is there now may be no text at all: then nothing of
			// it can be reloaded, and it is told as that.
			return decode(content.bytes()) == null
					? new Checked(Found.NOT_TEXT, null, revision, false)
					: new Checked(Found.CHANGED, null, revision,
							holds(content.bytes(), path, mine));
		}
		Text text = decode(content.bytes());
		return text != null
				? new Checked(Found.CHANGED, text, revision, false)
				: new Checked(Found.NOT_TEXT, null, revision, false);
	}

	// Return whether a file of bytes holds the unsaved text of path:
	// mine, or that of its journal when null.
	private boolean holds(byte[] bytes, ProjectPath path, Text mine) {
		Text text = mine;
		if (text == null) {
			Journal.Recovered recovered = journal.read(path);
			text = recovered != null ? recovered.text() : null;
		}
		return text != null && holds(bytes, text);
	}

	/**
	 * Return whether a file of bytes holds text: opening it gives
	 * exactly text. Not whether writing text gives bytes: a lone
	 * surrogate is written as '?', which is another text.
	 */
	static boolean holds(byte[] bytes, Text text) {
		Text stored = decode(bytes);
		return stored != null && same(stored, text);
	}

	/**
	 * Return why no file can hold text, so that opening it gives text
	 * again, or null when one can: a surrogate without its pair is
	 * written as '?', a NUL makes the file no text, and a byte order
	 * mark at the start is not part of the text when it is read.
	 */
	static String unstorable(Text text) {
		for (int n = 0, lines = text.lineCount(); n < lines; ++n) {
			String line = text.line(n);
			for (int i = 0, length = line.length(); i < length; ++i) {
				char c = line.charAt(i);
				boolean pair = Character.isHighSurrogate(c)
						? i + 1 < length &&
								Character.isLowSurrogate(line.charAt(i + 1))
						: !Character.isLowSurrogate(c) || i > 0 &&
								Character.isHighSurrogate(line.charAt(i - 1));
				if (!pair || c == 0 || c == 0xfeff && n == 0 && i == 0) {
					return "line " + (n + 1) + " has a character at " +
							(i + 1) + " that a file cannot hold";
				}
			}
		}
		return null;
	}

	private Save save(ProjectPath path, Text text, String base, int state) {
		Text written = text;
		if (written == null) {
			Journal.Recovered recovered = journal.read(path);
			if (recovered != null && recovered.text() == null) {
				return new Save(state, new OpenProject.Unreadable(
						recovered.problem(), recovered.damaged()), null);
			} else if (recovered == null && astray(path)) {
				return new Save(state, new OpenProject.Unreadable(ASTRAY,
						false), null);
			}
			written = recovered != null ? recovered.text() : null;
		}
		if (written == null) {
			return new Save(state, null, null);
		}
		// Not written as another text and then called saved.
		String problem = unstorable(written);
		if (problem != null) {
			return new Save(state, new OpenProject.Failed(problem), null);
		}
		Text bytes = written;
		ProjectFiles.Change change = store.write(path,
				out -> TextBytes.write(bytes, out), base);
		if (change instanceof ProjectFiles.Done done) {
			return new Save(state, new OpenProject.Saved(done.revision()),
					null);
		}
		ProjectFiles.Refused refused = (ProjectFiles.Refused) change;
		if (refused.refusal() != ProjectFiles.Refusal.CONFLICT) {
			return new Save(state, new OpenProject.Failed(refused.reason()),
					null);
		}
		return new Save(state, new OpenProject.Conflict(refused.reason()),
				check(path, base, null, false));
	}

	// Append when the journal continues where flush says, else write the
	// whole text, so the answer is final and nothing waits for main.
	private boolean write(ProjectPath path, FileRecord.Flush flush) {
		if (flush.removes()) {
			return journal.remove(path);
		}
		// What is appended to is the journal of this text; any other
		// one there is put aside by the snapshot.
		if (flush.from() >= 0) {
			List<Journal.Change> changes = new ArrayList<>();
			for (Document.Change change : flush.changes()) {
				changes.add(new Journal.Change(change.start(),
						change.removed().length(),
						change.inserted().toString()));
			}
			if (journal.append(path, flush.from(), flush.to(), changes) ==
					Journal.Appended.DONE) {
				return true;
			}
		}
		return journal.snapshot(flush.header(), flush.to(), flush.text());
	}

	// Give the document of opened the history kept for its text, if
	// there is one. Only where the text came from tells whether it is
	// saved. What reloads replaced of the file is always within reach
	// of undo: by the history kept when that leads to each of the
	// texts, else by one step for each, the newest first.
	private Opening withHistory(ProjectPath path, Opening opening) {
		if (!(opening instanceof Opened opened)) {
			return opening;
		}
		Document document = opened.document();
		String hash = histories.has(path)
				? Histories.hash(document.text())
				: null;
		Document.History kept = hash != null
				? histories.read(path, hash)
				: null;
		List<Journal.Replaced> replaced;
		try {
			replaced = journal.replaced(path);
		} catch (OutOfMemoryError e) {
			replaced = List.of();
		}
		List<FileRecord.Replaced> reached = kept != null
				? reached(document.text(), kept, replaced)
				: null;
		if (reached == null && !replaced.isEmpty()) {
			reached = new ArrayList<>();
			kept = leadingTo(document.text(), replaced, reached);
		}
		if (kept != null) {
			document.restore(kept);
		}
		document.markSaved(opened.unsaved() ? -1 : document.state());
		return reached == null || reached.isEmpty() ? opened : new Opened(
				document, opened.unsaved(), opened.revision(),
				opened.journal(), opened.id(), opened.noted(),
				opened.unreadable(), opened.damaged(), reached);
	}

	// Return, for each of replaced, the state at which undo or redo of
	// history, kept for text, find exactly its text; null when one of
	// them is not found.
	private static List<FileRecord.Replaced> reached(Text text,
			Document.History history, List<Journal.Replaced> replaced) {
		List<FileRecord.Replaced> reached = new ArrayList<>();
		if (replaced.isEmpty()) {
			return reached;
		}
		Document walked = new Document(text);
		if (!walked.restore(history)) {
			return null;
		}
		List<Journal.Replaced> sought = new ArrayList<>(replaced);
		int undone = 0;
		for (find(walked, sought, reached); walked.canUndo(); ++undone) {
			walked.undo();
			find(walked, sought, reached);
		}
		while (undone-- > 0) {
			walked.redo();
		}
		while (walked.canRedo()) {
			walked.redo();
			find(walked, sought, reached);
		}
		return sought.isEmpty() ? reached : null;
	}

	private static void find(Document walked, List<Journal.Replaced> sought,
			List<FileRecord.Replaced> reached) {
		for (int i = sought.size(); i-- > 0; ) {
			if (same(walked.text(), sought.get(i).text())) {
				reached.add(new FileRecord.Replaced(
						sought.remove(i).number(), walked.state()));
			}
		}
	}

	// Return a history for a document of text whose undo leads to each
	// of replaced, the newest first, and add to reached where.
	private static Document.History leadingTo(Text text,
			List<Journal.Replaced> replaced,
			List<FileRecord.Replaced> reached) {
		List<Document.History.Step> steps = new ArrayList<>();
		Document.Selection start = new Document.Selection(0, 0);
		int state = 0;
		for (int i = 0, n = replaced.size(); i < n; ++i) {
			Text from = replaced.get(i).text();
			Text to = i + 1 < n ? replaced.get(i + 1).text() : text;
			reached.add(new FileRecord.Replaced(replaced.get(i).number(),
					++state));
			steps.add(new Document.History.Step(List.of(
					new Document.Change(0, from, to)), start, start, state,
					state + 1));
		}
		return new Document.History(steps, List.of(), state + 1, state + 1,
				start);
	}

	private static boolean same(Text a, Text b) {
		if (a.length() != b.length() || a.lineCount() != b.lineCount()) {
			return false;
		}
		for (int i = a.lineCount(); i-- > 0; ) {
			if (!a.line(i).equals(b.line(i))) {
				return false;
			}
		}
		return true;
	}

	// Return the text recovered from the journal of path.
	private static Opening recovered(ProjectPath path,
			Journal.Recovered recovered, boolean noted) {
		if (recovered.tooLarge()) {
			return tooLarge(path, null, null);
		}
		Text text = recovered.text();
		return new Opened(new Document(text, Document.bytes(text)), true,
				recovered.header().base(), recovered.revision(),
				recovered.header().id(), noted, null, false, List.of());
	}

	// Why the journal of a file could not be read, and whether it is
	// damaged.
	private record Unread(String reason, boolean damaged) {
	}

	private static Unopened unopened(String reason, Unread unread,
			Found found, String revision) {
		return new Unopened(reason, unread != null ? unread.reason() : null,
				unread != null && unread.damaged(), found, revision);
	}

	// Return the stored text of path, telling unread, why its journal
	// could not be read, or null.
	private Opening read(ProjectPath path, long size, Unread unread) {
		if (size > Document.MAX_BYTES) {
			// Its first bytes tell a picture without reading it.
			byte[] head = store.head(path, FileKind.HEAD);
			return head != null && FileKind.picture(head) != null
					? picture(path, unread, null)
					: tooLarge(path, unread, null);
		}
		Read read = store.read(path, Document.MAX_BYTES);
		if (read instanceof ProjectFiles.Failed failed &&
				ProjectFiles.TOO_LARGE.equals(failed.reason())) {
			return tooLarge(path, unread, null);
		}
		if (!(read instanceof Content content)) {
			return unopened(CapturedFiles.problem(path, read), unread,
					read == null ? Found.MISSING : Found.UNAVAILABLE, null);
		}
		byte[] bytes = content.bytes();
		// What the file is, by its bytes; its name says nothing.
		if (FileKind.picture(bytes) != null) {
			return picture(path, unread, content.revision());
		}
		if (bytes.length > Document.MAX_BYTES) {
			return tooLarge(path, unread, content.revision());
		}
		Text text = decode(bytes);
		if (text == null) {
			return unopened(path + " is not a text file", unread,
					Found.NOT_TEXT, content.revision());
		}
		return new Opened(new Document(text, Document.bytes(text)), false,
				content.revision(), -1, 0, false,
				unread != null ? unread.reason() : null,
				unread != null && unread.damaged(), List.of());
	}

	// Return the text of bytes, or null when they are not text the
	// editor opens.
	private static Text decode(byte[] bytes) {
		String text = bytes.length <= Document.MAX_BYTES
				? SourceText.decode(bytes)
				: null;
		return text != null && text.indexOf('\0') < 0 ? Text.of(text) : null;
	}

	private static Unopened picture(ProjectPath path, Unread unread,
			String revision) {
		return unopened(path + " is a picture, not text", unread,
				Found.NOT_TEXT, revision);
	}

	// A file within the limit that this device has no memory for.
	private static Unopened tooLargeHere(ProjectPath path, Unread unread) {
		return unopened(path + " is too large for this device", unread,
				Found.TOO_LARGE, null);
	}

	private static Unopened tooLarge(ProjectPath path, Unread unread,
			String revision) {
		return unopened(path + " is larger than " +
				Document.MAX_BYTES / (1024 * 1024) + " MiB", unread,
				Found.TOO_LARGE, revision);
	}
}
