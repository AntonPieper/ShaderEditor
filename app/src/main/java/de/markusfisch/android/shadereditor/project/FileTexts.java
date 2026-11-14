package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
	 * null.
	 */
	public interface Opening {
		String unreadable();
	}

	/**
	 * A file for the editor: a document nobody else has yet, of its
	 * journal's text when unsaved, else of the stored text, with the
	 * undo history kept for that text; the revision of the stored text
	 * it is based on, null for none; the revision of the journal it came
	 * from, -1 for none; and why its journal could not be read, so the
	 * stored text was opened instead, else null. A document of unsaved
	 * text starts modified, whatever its history; replaced tells that
	 * it is text a reload replaced, see {@link Journal.Header}. It is
	 * made on the worker, so a device without the memory for it is told
	 * there.
	 */
	public record Opened(Document document, boolean unsaved,
			String revision, int journal, String unreadable,
			boolean replaced) implements Opening {
		public Text text() {
			return document.text();
		}
	}

	/**
	 * A file that cannot be edited; reason tells the user why, and
	 * found, never SAME or CHANGED, what storage holds in its place,
	 * at revision when one is known.
	 */
	public record Unopened(String reason, String unreadable, Found found,
			String revision) implements Opening {
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
		worker.execute(() -> {
			Journal.Recovered recovered = journal.read(path);
			String unreadable = recovered != null && recovered.text() == null
					? recovered.problem()
					: null;
			Opening opening;
			try {
				opening = withHistory(path, unreadable == null &&
						recovered != null
						? recovered(path, recovered)
						: read(path, size, unreadable));
			} catch (OutOfMemoryError e) {
				opening = tooLargeHere(path, unreadable);
			}
			Opening opened = opening;
			main.execute(() -> done.done(opened));
		});
	}

	/**
	 * What a reload read, and whether the journal of the file says now
	 * that a reload replaced its text.
	 */
	public record Reloaded(Opening opening, boolean marked) {
	}

	/**
	 * Open path for the editor as storage has it, to take the place of
	 * its unsaved text when mark; when expect, only if storage is at
	 * seen. Before main is told, so before it takes the stored text,
	 * the journal is marked as holding text a reload replaced: from
	 * then on it goes only into a history, whatever happens to main.
	 */
	public void reload(ProjectPath path, boolean mark, boolean expect,
			String seen, Callback<Reloaded> done) {
		worker.execute(() -> {
			Opening opening;
			try {
				opening = read(path, ProjectFiles.UNKNOWN, null);
			} catch (OutOfMemoryError e) {
				opening = tooLargeHere(path, null);
			}
			Reloaded reloaded = new Reloaded(opening, mark &&
					opening instanceof Opened opened && (!expect ||
					Objects.equals(seen, opened.revision())) &&
					journal.markReplaced(path));
			main.execute(() -> done.done(reloaded));
		});
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
		worker.execute(() -> {
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
		worker.execute(() -> {
			boolean written = write(path, flush);
			main.execute(() -> done.done(written));
		});
	}

	/**
	 * Keep history, of text, for path; nothing but undo depends on it
	 * being written.
	 */
	void keepHistory(ProjectPath path, Text text,
			Document.History history) {
		worker.execute(() -> {
			String hash = Histories.hash(text);
			if (hash != null) {
				histories.write(path, hash, history);
			}
		});
	}

	/** Forget the history of path. */
	void forgetHistory(ProjectPath path) {
		worker.execute(() -> histories.remove(path));
	}

	/**
	 * Delete the journal of path if it still cannot be read; done gets
	 * whether none that cannot be read is kept now.
	 */
	void dismiss(ProjectPath path, Callback<Boolean> done) {
		worker.execute(() -> {
			boolean removed = journal.dismiss(path);
			main.execute(() -> done.done(removed));
		});
	}

	/** Count the journals whose header cannot be read. */
	void damaged(Callback<Integer> done) {
		worker.execute(() -> {
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
		worker.execute(() -> {
			ProjectFiles.Change change;
			if (!journal.setMove(move)) {
				change = new ProjectFiles.Refused(
						ProjectFiles.Refusal.FAILED,
						"the journal cannot be written");
			} else {
				change = move.to() != null
						? store.rename(move.from(), move.to())
						: store.delete(move.from());
				if (change instanceof ProjectFiles.Done) {
					if (move.to() == null) {
						followed(move);
					}
				} else if (((ProjectFiles.Refused) change).refusal() !=
						ProjectFiles.Refusal.PARTIAL) {
					// Nothing happened; half of it is looked at when
					// the project opens again.
					journal.setMove(null);
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
		worker.execute(() -> followed(move));
	}

	/**
	 * Finish or drop a move the process died in: when storage made it,
	 * the journals and histories follow, when it did not, nothing is to
	 * do; while storage cannot tell, it stays as it is. Return the move
	 * that was finished now, else null. On the worker, before the
	 * journals are looked at.
	 */
	Journal.Move recover() {
		Journal.Move move = journal.move();
		if (move == null) {
			return null;
		}
		Boolean from = exists(move.from());
		Boolean to = move.to() != null ? exists(move.to()) : Boolean.TRUE;
		if (Boolean.FALSE.equals(from) && Boolean.TRUE.equals(to)) {
			return followed(move) ? move : null;
		} else if (Boolean.TRUE.equals(from)) {
			journal.setMove(null);
		}
		return null;
	}

	private boolean followed(Journal.Move move) {
		boolean followed = journal.follow(move);
		histories.follow(move);
		return followed && journal.setMove(null);
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
		worker.execute(() -> {
			boolean kept = journal.setHeader(header);
			main.execute(() -> done.done(kept));
		});
	}

	/**
	 * Make what the journal holds durable once everything given before
	 * is written; done gets whether it is.
	 */
	void sync(Callback<Boolean> done) {
		worker.execute(() -> {
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
		worker.execute(() -> {
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
		worker.execute(() -> {
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
			return new Checked(Found.CHANGED, null, revision,
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
		if (stored == null || stored.length() != text.length() ||
				stored.lineCount() != text.lineCount()) {
			return false;
		}
		for (int i = text.lineCount(); i-- > 0; ) {
			if (!stored.line(i).equals(text.line(i))) {
				return false;
			}
		}
		return true;
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
						recovered.problem()), null);
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
			// What a reload replaced is given up to its history only:
			// here, where the journal goes, whoever asks and whatever
			// they remember. Without a history it goes only when
			// storage holds its text.
			if (flush.history() != null) {
				String hash = Histories.hash(flush.text());
				if (hash == null ||
						!histories.write(path, hash, flush.history())) {
					return false;
				}
			} else if (!removable(path)) {
				return false;
			}
			return journal.remove(path);
		}
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

	// Return whether the journal of path may go without a history: it
	// does not hold text a reload replaced, or storage holds that text.
	private boolean removable(ProjectPath path) {
		Journal.Header header = journal.header(path);
		if (header == null || !header.replaced()) {
			return true;
		}
		Journal.Recovered recovered = journal.read(path);
		Read read = store.read(path, Document.MAX_BYTES);
		return recovered == null || recovered.text() != null &&
				read instanceof Content content &&
				holds(content.bytes(), recovered.text());
	}

	// Give the document of opened the history kept for its text, if
	// there is one. Only where the text came from tells whether it is
	// saved.
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
		if (kept != null) {
			document.restore(kept);
		}
		document.markSaved(opened.unsaved() ? -1 : document.state());
		return opened;
	}

	// Return the text recovered from the journal of path.
	private static Opening recovered(ProjectPath path,
			Journal.Recovered recovered) {
		if (recovered.tooLarge()) {
			return tooLarge(path, null, null);
		}
		Text text = recovered.text();
		return new Opened(new Document(text, Document.bytes(text)), true,
				recovered.header().base(), recovered.revision(), null,
				recovered.header().replaced());
	}

	// Return the stored text of path, telling unreadable, why its
	// journal could not be read, or null.
	private Opening read(ProjectPath path, long size, String unreadable) {
		if (path.getKind() == ProjectPath.Kind.IMAGE) {
			return new Unopened(path + " is an image, not text",
					unreadable, Found.NOT_TEXT, null);
		}
		if (size > Document.MAX_BYTES) {
			return tooLarge(path, unreadable, null);
		}
		Read read = store.read(path, Document.MAX_BYTES);
		if (read instanceof ProjectFiles.Failed failed &&
				ProjectFiles.TOO_LARGE.equals(failed.reason())) {
			return tooLarge(path, unreadable, null);
		}
		if (!(read instanceof Content content)) {
			return new Unopened(CapturedFiles.problem(path, read),
					unreadable, read == null
							? Found.MISSING
							: Found.UNAVAILABLE, null);
		}
		byte[] bytes = content.bytes();
		if (bytes.length > Document.MAX_BYTES) {
			return tooLarge(path, unreadable, content.revision());
		}
		Text text = decode(bytes);
		if (text == null) {
			return new Unopened(path + " is not a text file", unreadable,
					Found.NOT_TEXT, content.revision());
		}
		return new Opened(new Document(text, Document.bytes(text)), false,
				content.revision(), -1, unreadable, false);
	}

	// Return the text of bytes, or null when they are not text the
	// editor opens.
	private static Text decode(byte[] bytes) {
		String text = bytes.length <= Document.MAX_BYTES
				? SourceText.decode(bytes)
				: null;
		return text != null && text.indexOf('\0') < 0 ? Text.of(text) : null;
	}

	// A file within the limit that this device has no memory for.
	private static Unopened tooLargeHere(ProjectPath path,
			String unreadable) {
		return new Unopened(path + " is too large for this device",
				unreadable, Found.TOO_LARGE, null);
	}

	private static Unopened tooLarge(ProjectPath path, String unreadable,
			String revision) {
		return new Unopened(path + " is larger than " +
				Document.MAX_BYTES / (1024 * 1024) + " MiB", unreadable,
				Found.TOO_LARGE, revision);
	}
}
