package de.markusfisch.android.shadereditor.project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * at a time, in the order given; results are posted to main.
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
	 * The text of a file for the editor: its journal's when unsaved,
	 * else the stored text; the revision of the stored text it is based
	 * on, null for none; the revision of the journal it came from, -1
	 * for none; its bytes, as {@link Document#bytes} counts them; why
	 * its journal could not be read, so the stored text was opened
	 * instead, else null; and the undo history kept for this text, else
	 * null. A document of unsaved text starts modified, with
	 * {@code markSaved(-1)}, whatever its history.
	 */
	public record Opened(Text text, boolean unsaved, String revision,
			int journal, long bytes, String unreadable,
			Document.History history) implements Opening {
	}

	/** A file that cannot be edited; reason tells the user why. */
	public record Unopened(String reason, String unreadable)
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
			Opening opening = withHistory(path,
					recovered != null && recovered.text() != null
							? recovered(path, recovered)
							: read(path, size, recovered != null
									? recovered.problem()
									: null));
			main.execute(() -> done.done(opening));
		});
	}

	/** Open path for the editor as storage has it. */
	public void openStored(ProjectPath path, Callback<Opening> done) {
		worker.execute(() -> {
			Opening opening = read(path, ProjectFiles.UNKNOWN, null);
			main.execute(() -> done.done(opening));
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
	 * worker. For unsaved text, mine or, when storage changed, that of
	 * the journal when null, whether storage holds exactly that text;
	 * else the stored text when it changed.
	 */
	Checked check(ProjectPath path, String base, Text mine,
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
			// Text typed back to what is stored is saved too; a journal
			// is not read to find that.
			return new Checked(Found.SAME, null, base, mine != null &&
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

	// Return whether bytes are exactly the unsaved text of path: mine,
	// or that of its journal when null.
	private boolean holds(byte[] bytes, ProjectPath path, Text mine) {
		Text text = mine;
		if (text == null) {
			Journal.Recovered recovered = journal.read(path);
			text = recovered != null ? recovered.text() : null;
		}
		try {
			return text != null && TextBytes.equal(text, bytes);
		} catch (IOException e) {
			return false;
		}
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

	// Return opened with the history kept for its text, if there is one.
	private Opening withHistory(ProjectPath path, Opening opening) {
		if (!(opening instanceof Opened opened) || !histories.has(path)) {
			return opening;
		}
		String hash = Histories.hash(opened.text());
		Document.History kept = hash != null
				? histories.read(path, hash)
				: null;
		return kept == null ? opened : new Opened(opened.text(),
				opened.unsaved(), opened.revision(), opened.journal(),
				opened.bytes(), opened.unreadable(), kept);
	}

	// Return the text recovered from the journal of path.
	private static Opening recovered(ProjectPath path,
			Journal.Recovered recovered) {
		if (recovered.tooLarge()) {
			return tooLarge(path, null);
		}
		return new Opened(recovered.text(), true, recovered.header().base(),
				recovered.revision(), Document.bytes(recovered.text()), null,
				null);
	}

	// Return the stored text of path, telling unreadable, why its
	// journal could not be read, or null.
	private Opening read(ProjectPath path, long size, String unreadable) {
		if (path.getKind() == ProjectPath.Kind.IMAGE) {
			return new Unopened(path + " is an image, not text",
					unreadable);
		}
		if (size > Document.MAX_BYTES) {
			return tooLarge(path, unreadable);
		}
		Read read = store.read(path, Document.MAX_BYTES);
		if (read instanceof ProjectFiles.Failed failed &&
				ProjectFiles.TOO_LARGE.equals(failed.reason())) {
			return tooLarge(path, unreadable);
		}
		if (!(read instanceof Content content)) {
			return new Unopened(CapturedFiles.problem(path, read),
					unreadable);
		}
		byte[] bytes = content.bytes();
		if (bytes.length > Document.MAX_BYTES) {
			return tooLarge(path, unreadable);
		}
		Text text = decode(bytes);
		if (text == null) {
			return new Unopened(path + " is not a text file", unreadable);
		}
		return new Opened(text, false, content.revision(), -1,
				Document.bytes(text), unreadable, null);
	}

	// Return the text of bytes, or null when they are not text the
	// editor opens.
	private static Text decode(byte[] bytes) {
		String text = bytes.length <= Document.MAX_BYTES
				? SourceText.decode(bytes)
				: null;
		return text != null && text.indexOf('\0') < 0 ? Text.of(text) : null;
	}

	private static Unopened tooLarge(ProjectPath path, String unreadable) {
		return new Unopened(path + " is larger than " +
				Document.MAX_BYTES / (1024 * 1024) + " MiB", unreadable);
	}
}
