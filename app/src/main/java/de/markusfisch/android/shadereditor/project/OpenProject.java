package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * The project open in the editor, for the screens that show it: the
 * text and editor state of every file opened, and what runs, prepared
 * from that text. Unsaved text is kept by an {@link EditingSession}, so
 * it survives the process. Methods that read or write storage are called
 * from one worker thread at a time; the others only touch memory and
 * may be called from any thread.
 */
public final class OpenProject {
	public static final ProjectPath MAIN = ProjectPath.parse("main.glsl")
			.value();

	// A file opened in the editor: its text there, the text stored when
	// it was read, the revision its draft is based on, the text the
	// journal has, and what the editor keeps for it.
	private static final class Doc {
		private String text;
		private String stored;
		private String base;
		private String journaled;
		private Object state;
	}

	private static final int MAX_TEXT = ProjectLimits.DEFAULT.sourceBytes();

	private final Map<ProjectPath, Doc> docs = new HashMap<>();
	private final ProjectFiles files = new Edited();
	private final String id;
	private final ProjectFiles store;
	private final ProjectSessions sessions;
	private final EditingSession session;
	private ProjectPath running;
	private ProjectPath shown;

	private OpenProject(String id, ProjectFiles store,
			ProjectSessions sessions, EditingSession session) {
		this.id = id;
		this.store = store;
		this.sessions = sessions;
		this.session = session;
	}

	/**
	 * Open session id of sessions over store with the drafts it kept and
	 * find what runs; return null when there is no such session.
	 */
	public static OpenProject open(String id, ProjectFiles store,
			ProjectSessions sessions) {
		DraftJournal journal = sessions.drafts(id);
		if (journal == null) {
			return null;
		}
		OpenProject project = new OpenProject(id, store, sessions,
				EditingSession.restore(store, journal));
		project.findRunning();
		return project;
	}

	public String id() {
		return id;
	}

	/**
	 * Read path for the editor, its draft or the stored text, unless it
	 * is open already. Return null when it is open, or why it cannot be
	 * edited.
	 */
	public String load(ProjectPath path) {
		synchronized (this) {
			if (docs.containsKey(path)) {
				return null;
			}
		}
		if (path.getKind() == ProjectPath.Kind.IMAGE) {
			return path + " is an image, not text";
		}
		Read read = store.read(path);
		Draft draft = session.draft(path);
		Doc doc = new Doc();
		if (read instanceof Content content) {
			doc.stored = text(content.bytes());
			if (doc.stored == null) {
				return path + " is not a text file of at most " +
						MAX_TEXT / 1024 + " KiB";
			}
			doc.base = content.revision();
		} else if (read != null || draft == null) {
			return CapturedFiles.problem(path, read);
		}
		if (draft != null) {
			doc.text = draft.text();
			doc.base = draft.base();
		} else {
			doc.text = doc.stored;
		}
		doc.journaled = doc.text;
		synchronized (this) {
			if (!docs.containsKey(path)) {
				docs.put(path, doc);
			}
		}
		return null;
	}

	/** Return the text of path in the editor, null when not open. */
	public synchronized String text(ProjectPath path) {
		Doc doc = docs.get(path);
		return doc != null ? doc.text : null;
	}

	/** Set the text of path, open in the editor, as it is there now. */
	public synchronized void edit(ProjectPath path, String text) {
		Doc doc = docs.get(path);
		if (doc != null) {
			doc.text = text;
		}
	}

	/**
	 * Return what the editor keeps for path, such as its undo history,
	 * or null when it keeps nothing; reading path again drops it.
	 */
	public synchronized Object editorState(ProjectPath path) {
		Doc doc = docs.get(path);
		return doc != null ? doc.state : null;
	}

	public synchronized void setEditorState(ProjectPath path, Object state) {
		Doc doc = docs.get(path);
		if (doc != null) {
			doc.state = state;
		}
	}

	/** Return the file shown in the editor, or null. */
	public synchronized ProjectPath shown() {
		return shown;
	}

	public synchronized void show(ProjectPath path) {
		shown = path;
	}

	/** Return every file with unsaved text. */
	public synchronized Set<ProjectPath> unsaved() {
		Set<ProjectPath> unsaved = new LinkedHashSet<>(session.unsaved());
		for (Map.Entry<ProjectPath, Doc> entry : docs.entrySet()) {
			Doc doc = entry.getValue();
			if (doc.text.equals(doc.stored)) {
				unsaved.remove(entry.getKey());
			} else {
				unsaved.add(entry.getKey());
			}
		}
		return unsaved;
	}

	/**
	 * Keep the text of every open file durably where it changed since;
	 * text that is stored like this drops its draft. Return whether
	 * every draft was kept durably; it is kept in memory either way.
	 */
	public boolean persist() {
		record Change(ProjectPath path, Doc doc, String text) {
		}
		List<Change> changes = new ArrayList<>();
		synchronized (this) {
			for (Map.Entry<ProjectPath, Doc> entry : docs.entrySet()) {
				Doc doc = entry.getValue();
				if (!doc.text.equals(doc.journaled)) {
					changes.add(new Change(entry.getKey(), doc, doc.text));
				}
			}
		}
		boolean durable = true;
		for (Change change : changes) {
			Doc doc = change.doc();
			durable &= change.text().equals(doc.stored)
					? session.clearDraft(change.path())
					: session.setDraft(change.path(), change.text(), doc.base);
			synchronized (this) {
				doc.journaled = change.text();
			}
		}
		return durable;
	}

	/** Return the file that runs, or null when nothing runs. */
	public synchronized ProjectPath running() {
		return running;
	}

	/**
	 * Find what runs by the one rule: the file last chosen with Run this
	 * file if it still exists, else the entry of a valid manifest, else
	 * main.glsl when present, else nothing. Return it, or null.
	 */
	public ProjectPath findRunning() {
		ProjectPath found = sessions.running(id);
		if (found == null || !(files.read(found) instanceof Content)) {
			found = null;
			ProjectParseResult<ProjectManifest> manifest = null;
			if (files.read(ProjectManifest.FILE) instanceof Content content) {
				String text = SourceText.decode(content.bytes());
				manifest = text != null ? ProjectManifest.parse(text) : null;
			}
			if (manifest != null && manifest.isSuccess()) {
				found = manifest.value().entry();
			} else if (files.read(MAIN) instanceof Content) {
				found = MAIN;
			}
		}
		synchronized (this) {
			running = found;
		}
		return found;
	}

	/**
	 * Run path from now on, also after reopening, until another file is
	 * chosen; the project's files stay as they are. Return whether the
	 * choice was kept durably.
	 */
	public boolean runThisFile(ProjectPath path) {
		synchronized (this) {
			running = path;
		}
		return sessions.setRunning(id, path);
	}

	/**
	 * Prepare what runs from the text in the editor and the drafts, or
	 * return null when nothing runs. Any thread.
	 */
	public Preparation prepare(Map<String, String> reserved) {
		ProjectPath entry = running();
		return entry != null
				? Preparation.prepare(files, entry, ProjectLimits.DEFAULT,
						reserved)
				: null;
	}

	// Return the text of bytes, or null when they are not text the
	// editor can hold.
	private static String text(byte[] bytes) {
		String text = bytes.length <= MAX_TEXT
				? SourceText.decode(bytes)
				: null;
		return text != null && text.indexOf('\0') < 0 ? text : null;
	}

	// The project as the editor has it: open files with their text in
	// the editor, the others with their drafts or as stored.
	private final class Edited implements ProjectFiles {
		@Override
		public Read read(ProjectPath path) {
			String text = text(path);
			if (text == null) {
				return session.files().read(path);
			}
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			String revision = StoredFiles.revision(bytes);
			return revision != null
					? new Content(bytes, "editor:" + revision)
					: new Failed("SHA-256 is not available");
		}

		@Override
		public Listing list(ProjectPath folder) {
			return store.list(folder);
		}
	}
}
