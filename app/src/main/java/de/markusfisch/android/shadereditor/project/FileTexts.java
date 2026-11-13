package de.markusfisch.android.shadereditor.project;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.EditingSession.Outcome;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;

/**
 * Files of a project opened as {@link Text} for the editor, and saved
 * and drafted from it, with the drafts and conflicts of an
 * {@link EditingSession}. Everything that costs time in proportion to
 * a file, reading, decoding, splitting into lines, encoding and
 * writing, runs on the worker; the main thread only hands over and
 * receives immutable values. The worker runs one task at a time, in
 * the order given; results are posted to main.
 */
public final class FileTexts {
	/** The largest file, in bytes, that is opened for editing. */
	public static final long MAX_BYTES = 16L * 1024 * 1024;

	public interface Callback<T> {
		void done(T result);
	}

	/** What opening a file found: {@link Opened} or {@link Unopened}. */
	public interface Opening {
	}

	/**
	 * The text of a file for the editor: its draft when unsaved, else
	 * the stored text. A document of unsaved text starts modified, with
	 * {@code markSaved(-1)}.
	 */
	public record Opened(Text text, boolean unsaved) implements Opening {
	}

	/** A file that cannot be edited; reason tells the user why. */
	public record Unopened(String reason) implements Opening {
	}

	/**
	 * What a save did, and the state of the document it took the text
	 * at, which a {@link EditingSession.Saved} outcome marks as saved.
	 */
	public record Save(int state, Outcome outcome) {
	}

	/**
	 * The text of a file that changed elsewhere and the revision it was
	 * read at, for {@link #rebase} once a document took the text.
	 */
	public record Changed(Text text, String revision) {
	}

	// The revision each opened file was read or saved at; worker only.
	private final Map<ProjectPath, String> bases = new HashMap<>();
	private final EditingSession session;
	private final Executor worker;
	private final Executor main;

	public FileTexts(EditingSession session, Executor worker,
			Executor main) {
		this.session = session;
		this.worker = worker;
		this.main = main;
	}

	/**
	 * Open path for the editor. size is its size in bytes as the listing
	 * told it, or {@link ProjectFiles#UNKNOWN}; a file larger than
	 * {@link #MAX_BYTES} is refused before it is read when the size is
	 * known, after it is read otherwise.
	 */
	public void open(ProjectPath path, long size, Callback<Opening> done) {
		worker.execute(() -> {
			Opening opening = read(path, size);
			main.execute(() -> done.done(opening));
		});
	}

	/**
	 * Save text, taken from the document at state, to path. Call with
	 * the document's current text and state; the text is immutable, so
	 * editing on meanwhile changes nothing of what is written.
	 */
	public void save(ProjectPath path, Text text, int state,
			Callback<Save> done) {
		worker.execute(() -> {
			Outcome outcome = session.save(path, text, bases.get(path));
			if (outcome instanceof EditingSession.Saved saved) {
				bases.put(path, saved.revision());
			}
			Save save = new Save(state, outcome);
			main.execute(() -> done.done(save));
		});
	}

	/**
	 * Keep text as the draft of path when it is modified, else drop the
	 * draft, so the text survives the process.
	 */
	public void draft(ProjectPath path, Text text, boolean modified) {
		worker.execute(() -> {
			if (modified) {
				session.setDraft(path, text, bases.get(path));
			} else {
				session.clearDraft(path);
			}
		});
	}

	/**
	 * Read path again for a document without unsaved text. done gets
	 * the stored text when the file changed since it was opened or
	 * saved, or null when it did not, has a draft or cannot be opened.
	 * Saves stay based on what was opened until {@link #rebase}, so a
	 * document edited meanwhile meets a conflict instead of
	 * overwriting the change.
	 */
	public void reread(ProjectPath path, Callback<Changed> done) {
		worker.execute(() -> {
			Changed changed = null;
			if (session.draft(path) == null &&
					session.store().read(path) instanceof Content content &&
					!content.revision().equals(bases.get(path))) {
				Text text = decode(content.bytes());
				changed = text != null
						? new Changed(text, content.revision())
						: null;
			}
			Changed result = changed;
			main.execute(() -> done.done(result));
		});
	}

	/** Base the next save of path on revision, as reread gave it. */
	public void rebase(ProjectPath path, String revision) {
		worker.execute(() -> bases.put(path, revision));
	}

	private Opening read(ProjectPath path, long size) {
		if (path.getKind() == ProjectPath.Kind.IMAGE) {
			return new Unopened(path + " is an image, not text");
		}
		Draft draft = session.draft(path);
		if (draft != null) {
			return new Opened(draft.text(), true);
		}
		if (size > MAX_BYTES) {
			return tooLarge(path);
		}
		Read read = session.store().read(path);
		if (!(read instanceof Content content)) {
			return new Unopened(CapturedFiles.problem(path, read));
		}
		byte[] bytes = content.bytes();
		if (bytes.length > MAX_BYTES) {
			return tooLarge(path);
		}
		Text text = decode(bytes);
		if (text == null) {
			return new Unopened(path + " is not a text file");
		}
		bases.put(path, content.revision());
		return new Opened(text, false);
	}

	// Return the text of bytes, or null when they are not text the
	// editor opens.
	private static Text decode(byte[] bytes) {
		String text = bytes.length <= MAX_BYTES
				? SourceText.decode(bytes)
				: null;
		return text != null && text.indexOf('\0') < 0 ? Text.of(text) : null;
	}

	private static Unopened tooLarge(ProjectPath path) {
		return new Unopened(path + " is larger than " +
				MAX_BYTES / (1024 * 1024) + " MiB");
	}
}
