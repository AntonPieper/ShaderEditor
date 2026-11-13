package de.markusfisch.android.shadereditor.project;

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
	 * the stored text, and the revision of the stored text it is based
	 * on, null for none. A document of unsaved text starts modified,
	 * with {@code markSaved(-1)}.
	 */
	public record Opened(Text text, boolean unsaved, String revision)
			implements Opening {
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

	/** The text of a file that changed elsewhere and its revision. */
	public record Changed(Text text, String revision) {
	}

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
			Opening opening = read(path, size, true);
			main.execute(() -> done.done(opening));
		});
	}

	/**
	 * Open path for the editor as storage has it, also when it has a
	 * draft.
	 */
	public void openStored(ProjectPath path, Callback<Opening> done) {
		worker.execute(() -> {
			Opening opening = read(path, ProjectFiles.UNKNOWN, false);
			main.execute(() -> done.done(opening));
		});
	}

	/**
	 * Save text, taken from the document at state, to path, expecting
	 * base, the revision the text came from, when there is no draft.
	 * Call with the document's current text and state, or with null
	 * for the text of the draft of path; the outcome is null when there
	 * is none. The text is immutable, so editing on meanwhile changes
	 * nothing of what is written.
	 */
	public void save(ProjectPath path, Text text, int state, String base,
			Callback<Save> done) {
		worker.execute(() -> {
			Draft draft = text == null ? session.draft(path) : null;
			Outcome outcome = text != null || draft != null
					? session.save(path, text != null ? text : draft.text(),
							base)
					: null;
			Save save = new Save(state, outcome);
			main.execute(() -> done.done(save));
		});
	}

	/**
	 * Keep text as the draft of path when it is modified, else drop the
	 * draft, so the text survives the process. base is the revision the
	 * text came from, kept with a new draft. done gets whether the
	 * journal took it.
	 */
	public void draft(ProjectPath path, Text text, boolean modified,
			String base, Callback<Boolean> done) {
		worker.execute(() -> {
			boolean kept = modified
					? session.setDraft(path, text, base)
					: session.clearDraft(path);
			main.execute(() -> done.done(kept));
		});
	}

	/**
	 * Read path again for a document without unsaved text. done gets
	 * the stored text when it is not at base anymore, or null when it
	 * is, path has a draft or cannot be opened.
	 */
	public void reread(ProjectPath path, String base,
			Callback<Changed> done) {
		worker.execute(() -> {
			Changed changed = null;
			if (session.draft(path) == null &&
					session.store().read(path) instanceof Content content &&
					!content.revision().equals(base)) {
				Text text = decode(content.bytes());
				changed = text != null
						? new Changed(text, content.revision())
						: null;
			}
			Changed result = changed;
			main.execute(() -> done.done(result));
		});
	}

	private Opening read(ProjectPath path, long size, boolean drafted) {
		if (path.getKind() == ProjectPath.Kind.IMAGE) {
			return new Unopened(path + " is an image, not text");
		}
		Draft draft = drafted ? session.draft(path) : null;
		if (draft != null) {
			return new Opened(draft.text(), true, draft.base());
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
		return new Opened(text, false, content.revision());
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
