package de.markusfisch.android.shadereditor.project;

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

	/** What opening a file found: {@link Opened} or {@link Unopened}. */
	public interface Opening {
	}

	/**
	 * The text of a file for the editor: its journal's when unsaved,
	 * else the stored text; the revision of the stored text it is based
	 * on, null for none; the revision of the journal it came from, -1
	 * for none; its bytes, as {@link Document#bytes} counts them; and
	 * why its journal could not be read, so the stored text was opened
	 * instead, else null. A document of unsaved text starts modified,
	 * with {@code markSaved(-1)}.
	 */
	public record Opened(Text text, boolean unsaved, String revision,
			int journal, long bytes, String unreadable) implements Opening {
	}

	/** A file that cannot be edited; reason tells the user why. */
	public record Unopened(String reason) implements Opening {
	}

	/**
	 * What a save did, the state of the document it took the text at,
	 * which a {@link OpenProject.Saved} outcome marks as saved, and the
	 * revision storage holds after a {@link OpenProject.Conflict}, null
	 * when none.
	 */
	public record Save(int state, Outcome outcome, String seen) {
	}

	/** What storage holds of a file, compared with what it was. */
	public enum Found {
		SAME, CHANGED, MISSING, UNAVAILABLE, TOO_LARGE, NOT_TEXT
	}

	/**
	 * What a check of a file found, with the stored text and its
	 * revision when it changed.
	 */
	public record Checked(Found found, Text text, String revision) {
	}

	private final ProjectFiles store;
	private final Journal journal;
	private final Executor worker;
	private final Executor main;

	FileTexts(ProjectFiles store, Journal journal, Executor worker,
			Executor main) {
		this.store = store;
		this.journal = journal;
		this.worker = worker;
		this.main = main;
	}

	/**
	 * Open path for the editor, from its journal when journaled and it
	 * can be read. size is its size in bytes as the listing told it, or
	 * {@link ProjectFiles#UNKNOWN}; a file larger than
	 * {@link Document#MAX_BYTES} is refused before it is read when the
	 * size is known, after it is read otherwise.
	 */
	public void open(ProjectPath path, long size, boolean journaled,
			Callback<Opening> done) {
		worker.execute(() -> {
			Journal.Recovered recovered = journaled
					? journal.read(path)
					: null;
			Opening opening = recovered != null && recovered.text() != null
					? recovered(path, recovered)
					: read(path, size, recovered != null
							? recovered.problem()
							: null);
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
	 * text the journal of path holds, which goes once it is written;
	 * the outcome is null when there is none, and failed when it cannot
	 * be read. The text is immutable,
	 * so editing on meanwhile changes nothing of what is written.
	 */
	public void save(ProjectPath path, Text text, int state, String base,
			Callback<Save> done) {
		worker.execute(() -> {
			Save save = save(path, text, base, state);
			main.execute(() -> done.done(save));
		});
	}

	/** Do what flush asks of the journal of path. */
	void journal(ProjectPath path, FileRecord.Flush flush,
			Callback<Journal.Appended> done) {
		worker.execute(() -> {
			Journal.Appended appended = write(path, flush);
			main.execute(() -> done.done(appended));
		});
	}

	/**
	 * Delete the journal of path; done gets whether none is kept now.
	 */
	void forget(ProjectPath path, Callback<Boolean> done) {
		worker.execute(() -> {
			boolean removed = journal.remove(path);
			main.execute(() -> done.done(removed));
		});
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
			Checked checked = check(path, base, true);
			main.execute(() -> done.done(checked));
		});
	}

	/**
	 * Compare what storage holds of path with base, for unsaved text;
	 * done gets what it found, without the text.
	 */
	public void compare(ProjectPath path, String base,
			Callback<Checked> done) {
		worker.execute(() -> {
			Checked checked = check(path, base, false);
			main.execute(() -> done.done(checked));
		});
	}

	/**
	 * Return what storage holds of path compared with base, with the
	 * stored text when it changed and decode; on the worker.
	 */
	Checked check(ProjectPath path, String base, boolean decode) {
		Read read = store.read(path, Document.MAX_BYTES);
		if (read == null) {
			return new Checked(Found.MISSING, null, null);
		}
		if (read instanceof ProjectFiles.Failed failed &&
				ProjectFiles.TOO_LARGE.equals(failed.reason())) {
			return new Checked(Found.TOO_LARGE, null, null);
		}
		if (!(read instanceof Content content)) {
			return new Checked(Found.UNAVAILABLE, null, null);
		}
		if (content.revision().equals(base)) {
			return new Checked(Found.SAME, null, base);
		} else if (content.bytes().length > Document.MAX_BYTES) {
			return new Checked(Found.TOO_LARGE, null, content.revision());
		} else if (!decode) {
			return new Checked(Found.CHANGED, null, content.revision());
		}
		Text text = decode(content.bytes());
		return text != null
				? new Checked(Found.CHANGED, text, content.revision())
				: new Checked(Found.NOT_TEXT, null, content.revision());
	}

	private Save save(ProjectPath path, Text text, String base, int state) {
		Text written = text;
		if (written == null) {
			Journal.Recovered recovered = journal.read(path);
			if (recovered != null && recovered.text() == null) {
				return new Save(state, new OpenProject.Failed(
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
			if (text == null) {
				journal.remove(path);
			}
			return new Save(state, new OpenProject.Saved(done.revision()),
					null);
		}
		ProjectFiles.Refused refused = (ProjectFiles.Refused) change;
		if (refused.refusal() != ProjectFiles.Refusal.CONFLICT) {
			return new Save(state, new OpenProject.Failed(refused.reason()),
					null);
		}
		Read read = store.read(path, Document.MAX_BYTES);
		return new Save(state, new OpenProject.Conflict(refused.reason()),
				read instanceof Content content ? content.revision() : null);
	}

	private Journal.Appended write(ProjectPath path, FileRecord.Flush flush) {
		if (flush.write() == FileRecord.Write.SNAPSHOT) {
			return journal.snapshot(flush.header(), flush.to(), flush.text())
					? Journal.Appended.DONE
					: Journal.Appended.FAILED;
		} else if (flush.write() == FileRecord.Write.REMOVE) {
			return journal.remove(path)
					? Journal.Appended.DONE
					: Journal.Appended.FAILED;
		}
		List<Journal.Change> changes = new ArrayList<>();
		for (Document.Change change : flush.changes()) {
			changes.add(new Journal.Change(change.start(),
					change.removed().length(), change.inserted().toString()));
		}
		return journal.append(path, flush.from(), flush.to(), changes);
	}

	// Return the text recovered from the journal of path.
	private static Opening recovered(ProjectPath path,
			Journal.Recovered recovered) {
		if (recovered.tooLarge()) {
			return tooLarge(path);
		}
		return new Opened(recovered.text(), true, recovered.header().base(),
				recovered.revision(), Document.bytes(recovered.text()), null);
	}

	// Return the stored text of path, telling unreadable, why its
	// journal could not be read, or null.
	private Opening read(ProjectPath path, long size, String unreadable) {
		if (path.getKind() == ProjectPath.Kind.IMAGE) {
			return new Unopened(path + " is an image, not text");
		}
		if (size > Document.MAX_BYTES) {
			return tooLarge(path);
		}
		Read read = store.read(path, Document.MAX_BYTES);
		if (read instanceof ProjectFiles.Failed failed &&
				ProjectFiles.TOO_LARGE.equals(failed.reason())) {
			return tooLarge(path);
		}
		if (!(read instanceof Content content)) {
			return new Unopened(CapturedFiles.problem(path, read));
		}
		byte[] bytes = content.bytes();
		if (bytes.length > Document.MAX_BYTES) {
			return tooLarge(path);
		}
		Text text = decode(bytes);
		if (text == null) {
			return new Unopened(path + " is not a text file");
		}
		return new Opened(text, false, content.revision(), -1,
				Document.bytes(text), unreadable);
	}

	// Return the text of bytes, or null when they are not text the
	// editor opens.
	private static Text decode(byte[] bytes) {
		String text = bytes.length <= Document.MAX_BYTES
				? SourceText.decode(bytes)
				: null;
		return text != null && text.indexOf('\0') < 0 ? Text.of(text) : null;
	}

	private static Unopened tooLarge(ProjectPath path) {
		return new Unopened(path + " is larger than " +
				Document.MAX_BYTES / (1024 * 1024) + " MiB");
	}
}
