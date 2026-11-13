package de.markusfisch.android.shadereditor.project;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Change;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Done;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refusal;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refused;

/**
 * The unsaved edits of one opened project: drafts over its store that
 * a journal keeps across restarts, saving with an outcome per file, and
 * conflicts with changes made elsewhere. Unsaved text is only dropped
 * when the caller asks for it; a file in conflict is not saved until
 * the caller resolves it. Use it off the main thread.
 */
public final class EditingSession {
	/**
	 * What saving a file did: {@link Saved}, {@link Conflict} or
	 * {@link Failed}.
	 */
	public interface Outcome {
	}

	/**
	 * The file was written and its draft dropped; revision is the
	 * revision of the stored file now.
	 */
	public record Saved(String revision) implements Outcome {
	}

	/**
	 * The stored file changed or vanished since the draft was based on
	 * it. Nothing was written and the draft is kept.
	 */
	public record Conflict(String reason) implements Outcome {
	}

	/** The file was not written; the draft is kept for a retry. */
	public record Failed(String reason) implements Outcome {
	}

	private final Map<ProjectPath, String> conflicts = new LinkedHashMap<>();
	private final DraftFiles files;
	private final DraftJournal journal;

	private EditingSession(ProjectFiles store, DraftJournal journal) {
		this.files = new DraftFiles(store);
		this.journal = journal;
	}

	/**
	 * Open a session over store with the drafts journal kept, then
	 * refresh, so a draft whose base changed meanwhile is a conflict.
	 */
	public static EditingSession restore(ProjectFiles store,
			DraftJournal journal) {
		EditingSession session = new EditingSession(store, journal);
		for (Map.Entry<ProjectPath, Draft> entry :
				journal.drafts().entrySet()) {
			session.files.put(entry.getKey(), entry.getValue());
		}
		session.refresh();
		return session;
	}

	/** Return the project's files as the editor has them. */
	public ProjectFiles files() {
		return files;
	}

	/** Return the draft of path, or null when it has none. */
	public Draft draft(ProjectPath path) {
		return files.get(path);
	}

	/** Return the paths of every file with unsaved text. */
	public Set<ProjectPath> unsaved() {
		return files.paths();
	}

	/** Return why each file in conflict is, by path. */
	public synchronized Map<ProjectPath, String> conflicts() {
		return new LinkedHashMap<>(conflicts);
	}

	/**
	 * Set the unsaved text of path. base is the revision of the stored
	 * file the text came from, null for a new file; it is used only
	 * when path has no draft yet. Return whether the draft was kept
	 * durably; it is kept in memory either way.
	 */
	public boolean setDraft(ProjectPath path, String text, String base) {
		return setDraft(path, Text.of(text), base);
	}

	/**
	 * Set the unsaved text of path as {@link #setDraft(ProjectPath,
	 * String, String)} does; the journal writes it line by line.
	 */
	public synchronized boolean setDraft(ProjectPath path, Text text,
			String base) {
		Draft old = files.get(path);
		return keep(path, new Draft(text, old != null ? old.base() : base));
	}

	/**
	 * Drop the draft of path, also to reload a file in conflict from
	 * storage. Return whether the journal forgot it.
	 */
	public synchronized boolean clearDraft(ProjectPath path) {
		files.remove(path);
		conflicts.remove(path);
		return journal.remove(path);
	}

	/**
	 * Write every draft that is not in conflict against its base
	 * revision, in the order the files were first edited, and return
	 * the outcome by path. A file in conflict is skipped and reported
	 * as {@link Conflict}.
	 */
	public Map<ProjectPath, Outcome> save() {
		Map<ProjectPath, Outcome> outcomes = new LinkedHashMap<>();
		Map<ProjectPath, Draft> pending = new LinkedHashMap<>();
		synchronized (this) {
			for (ProjectPath path : files.paths()) {
				String conflict = conflicts.get(path);
				if (conflict != null) {
					outcomes.put(path, new Conflict(conflict));
				} else {
					pending.put(path, files.get(path));
				}
			}
		}
		for (Map.Entry<ProjectPath, Draft> entry : pending.entrySet()) {
			Draft draft = entry.getValue();
			outcomes.put(entry.getKey(), saved(entry.getKey(), draft,
					files.store().write(entry.getKey(), draft::writeTo,
							draft.base())));
		}
		return outcomes;
	}

	/**
	 * Compare the base of every draft with the stored file and mark
	 * the ones that changed or vanished as in conflict. A file that
	 * cannot be read is left as it is. Return all conflicts by path.
	 */
	public Map<ProjectPath, String> refresh() {
		Map<ProjectPath, Draft> pending = new LinkedHashMap<>();
		synchronized (this) {
			for (ProjectPath path : files.paths()) {
				if (!conflicts.containsKey(path)) {
					pending.put(path, files.get(path));
				}
			}
		}
		for (Map.Entry<ProjectPath, Draft> entry : pending.entrySet()) {
			ProjectPath path = entry.getKey();
			String base = entry.getValue().base();
			String reason = changed(base, files.store().read(path));
			synchronized (this) {
				Draft draft = files.get(path);
				if (reason != null && draft != null &&
						Objects.equals(base, draft.base())) {
					conflicts.put(path, reason);
				}
			}
		}
		return conflicts();
	}

	/**
	 * Keep the draft of path over the stored file as it is now, so the
	 * next save overwrites it, or creates it again when it was deleted.
	 * Return null when done, or why not.
	 */
	public String keepEdits(ProjectPath path) {
		if (draft(path) == null) {
			return "there are no edits of " + path;
		}
		Read read = files.store().read(path);
		if (read != null && !(read instanceof Content)) {
			return CapturedFiles.problem(path, read);
		}
		synchronized (this) {
			Draft draft = files.get(path);
			if (draft == null) {
				return "there are no edits of " + path;
			}
			keep(path, new Draft(draft.text(), read != null
					? ((Content) read).revision()
					: null));
			conflicts.remove(path);
		}
		return null;
	}

	/**
	 * Write the draft of path to the new file to, then drop the draft,
	 * leaving the stored file of path as it is. Nothing is dropped when
	 * the copy is not written.
	 */
	public Outcome saveCopy(ProjectPath path, ProjectPath to) {
		Draft draft = draft(path);
		if (draft == null) {
			return new Failed("there are no edits of " + path);
		}
		Change change = files.store().write(to, draft::writeTo, null);
		if (!(change instanceof Done done)) {
			return new Failed(((Refused) change).reason());
		}
		synchronized (this) {
			if (files.get(path) == draft) {
				clearDraft(path);
			}
		}
		return new Saved(done.revision());
	}

	private synchronized Outcome saved(ProjectPath path, Draft draft,
			Change change) {
		if (change instanceof Done done) {
			Draft current = files.get(path);
			if (current == draft) {
				clearDraft(path);
			} else if (current != null) {
				// Edited while saving: the newer text is based on what
				// was just written.
				keep(path, new Draft(current.text(), done.revision()));
			}
			return new Saved(done.revision());
		}
		Refused refused = (Refused) change;
		if (refused.refusal() == Refusal.CONFLICT) {
			conflicts.put(path, refused.reason());
			return new Conflict(refused.reason());
		}
		return new Failed(refused.reason());
	}

	private boolean keep(ProjectPath path, Draft draft) {
		files.put(path, draft);
		return journal.put(path, draft);
	}

	// Return why the stored file is not the base anymore, or null when
	// it still is or cannot be read.
	private static String changed(String base, Read read) {
		if (read instanceof Content content) {
			if (content.revision().equals(base)) {
				return null;
			}
			return base == null ? "the file exists" : "the file has changed";
		}
		return read == null && base != null ? "the file was deleted" : null;
	}
}
