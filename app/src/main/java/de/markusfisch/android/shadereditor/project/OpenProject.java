package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.EditingSession.Outcome;
import de.markusfisch.android.shadereditor.project.EditingSession.Saved;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * The project open in the editor, for the screens that show it: its
 * folders, the text and editor state of every file opened, saving,
 * changes made elsewhere, what runs, prepared from that text, and the
 * diagnostics of its last build by file. Unsaved text is kept by an
 * {@link EditingSession}, so it survives the process. Methods that read
 * or write storage are called from one worker thread at a time; the
 * others only touch memory and may be called from any thread. One
 * listener hears when what the screens mark changes.
 */
public final class OpenProject {
	public static final ProjectPath MAIN = ProjectPath.parse("main.glsl")
			.value();

	/**
	 * A file or folder in a folder of the project, with its size and
	 * time of last change as the listing told them, or
	 * {@link ProjectFiles#UNKNOWN}.
	 */
	public record Item(ProjectPath path, boolean folder, long size,
			long modified) {
	}

	/** The items of a folder, folders first, or why it cannot be listed. */
	public record Folder(List<Item> items, String problem) {
	}

	/** Where a copy was written, or why it was not. */
	public record Copy(ProjectPath path, String problem) {
	}

	/**
	 * What a file or the files under a folder have: how many files have
	 * unsaved text, how many errors and warnings the last build found in
	 * them, and how many files changed elsewhere while they have unsaved
	 * text.
	 */
	public record Marks(int unsaved, int errors, int warnings,
			int conflicts) {
	}

	/** A file the last build read, and whether it is an image. */
	public record Used(ProjectPath path, boolean image) {
	}

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

	// What the listener hears about.
	private record State(Set<ProjectPath> unsaved, Set<ProjectPath> conflicts,
			ProjectPath running, List<Used> used,
			List<ProjectDiagnostic> diagnostics) {
	}

	private static final int MAX_TEXT = ProjectLimits.DEFAULT.sourceBytes();

	private final Map<ProjectPath, Doc> docs = new HashMap<>();
	private final Set<ProjectPath> keptEdits = new HashSet<>();
	private final ProjectFiles files = new Edited();
	private final String id;
	private final String name;
	private final String location;
	private final ProjectFiles store;
	private final ProjectSessions sessions;
	private final EditingSession session;
	private List<ProjectDiagnostic> diagnostics = List.of();
	private List<Used> used = List.of();
	private ProjectPath running;
	private ProjectPath shown;
	// The file shown as the session keeps it.
	private ProjectPath keptShown;
	private State announced;
	private Executor executor;
	private Runnable listener;
	private boolean pending;

	private OpenProject(String id, ProjectFiles store,
			ProjectSessions sessions, EditingSession session,
			ProjectSessions.Recent recent) {
		this.id = id;
		name = recent != null ? recent.name() : null;
		location = recent != null ? recent.location() : null;
		this.store = store;
		this.sessions = sessions;
		this.session = session;
	}

	/**
	 * Open session id of sessions over store with the drafts it kept,
	 * the file shown last and its name and location among the recent
	 * projects, and find what runs; return null when there is no such
	 * session.
	 */
	public static OpenProject open(String id, ProjectFiles store,
			ProjectSessions sessions) {
		DraftJournal journal = sessions.drafts(id);
		if (journal == null) {
			return null;
		}
		OpenProject project = new OpenProject(id, store, sessions,
				EditingSession.restore(store, journal), sessions.recent(id));
		project.shown = project.keptShown = sessions.shown(id);
		project.findRunning();
		return project;
	}

	/**
	 * Return the session that keeps the unsaved text, for editing files
	 * as {@link FileTexts}.
	 */
	public EditingSession session() {
		return session;
	}

	public String id() {
		return id;
	}

	/**
	 * Return the name of the project's folder as listed among the
	 * recent projects, or null when it is not listed.
	 */
	public String name() {
		return name;
	}

	/**
	 * Return where the project's folder is, as listed among the recent
	 * projects, or null when it is not listed.
	 */
	public String location() {
		return location;
	}

	/**
	 * Call listener through executor, such as the main thread's, when
	 * the files with unsaved text, the diagnostics, what runs, the files
	 * the last build read or the conflicts change; changes that come
	 * before the call is made are heard once. A listener set before
	 * replaces it; null stops listening.
	 */
	public synchronized void setListener(Executor executor,
			Runnable listener) {
		this.executor = executor;
		this.listener = listener;
		announced = state();
	}

	/**
	 * Return the files and folders in folder, the root when null; only
	 * that folder is listed.
	 */
	public Folder list(ProjectPath folder) {
		ProjectFiles.Listing listing = store.list(folder);
		if (!(listing instanceof ProjectFiles.Entries entries)) {
			return new Folder(List.of(), listing == null
					? "the folder does not exist anymore"
					: ((ProjectFiles.Failed) listing).reason());
		}
		List<Item> items = new ArrayList<>();
		for (ProjectFiles.Entry entry : entries.entries()) {
			String name = entry.name();
			ProjectParseResult<ProjectPath> path = ProjectPath.parse(
					folder != null ? folder + "/" + name : name);
			// A name no path can spell, like ".git", cannot be used.
			if (path.isSuccess() &&
					name.equals(path.value().getFileName())) {
				items.add(new Item(path.value(), entry.folder(),
						entry.size(), entry.modified()));
			}
		}
		Collections.sort(items, (a, b) -> a.folder() != b.folder()
				? a.folder() ? -1 : 1
				: compareNames(a.path().getFileName(),
						b.path().getFileName()));
		return new Folder(items, null);
	}

	/**
	 * Return the changes the storage offers for the file or folder at
	 * path, the root when null; none when there is no such file.
	 */
	public ProjectFiles.Offers offers(ProjectPath path) {
		return store.offers(path);
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
			doc.text = draft.text().toString();
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
	public void edit(ProjectPath path, String text) {
		synchronized (this) {
			Doc doc = docs.get(path);
			if (doc == null) {
				return;
			}
			boolean unsaved = !doc.text.equals(doc.stored);
			doc.text = text;
			if (unsaved == !text.equals(doc.stored)) {
				return;
			}
		}
		changed();
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

	/**
	 * Return the file shown in the editor, or null; after opening, the
	 * file shown last.
	 */
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
	 * Keep the text of every open file and the file shown durably where
	 * they changed since; text that is stored like this drops its draft.
	 * Return whether all was kept durably; it is kept in memory either
	 * way.
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
		ProjectPath show;
		synchronized (this) {
			show = shown;
		}
		boolean durable = true;
		if (show == null ? keptShown != null : !show.equals(keptShown)) {
			durable = sessions.setShown(id, show);
			if (durable) {
				keptShown = show;
			}
		}
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

	/**
	 * Save every file with unsaved text where it is and return the
	 * outcome by path. A file changed elsewhere is saved only when
	 * explicit and the user chose to keep their edits.
	 */
	public Map<ProjectPath, Outcome> save(boolean explicit) {
		persist();
		if (explicit) {
			Set<ProjectPath> kept;
			synchronized (this) {
				kept = new HashSet<>(keptEdits);
			}
			for (ProjectPath path : kept) {
				if (session.keepEdits(path) == null) {
					synchronized (this) {
						keptEdits.remove(path);
					}
				}
			}
		}
		Map<ProjectPath, Outcome> outcomes = session.save();
		synchronized (this) {
			for (Map.Entry<ProjectPath, Outcome> entry : outcomes.entrySet()) {
				Doc doc = docs.get(entry.getKey());
				if (doc != null && entry.getValue() instanceof Saved saved) {
					doc.stored = doc.journaled;
					doc.base = saved.revision();
				}
			}
		}
		changed();
		return outcomes;
	}

	/**
	 * Return why each file with unsaved text changed elsewhere, for the
	 * files the user has not decided about yet.
	 */
	public synchronized Map<ProjectPath, String> conflicts() {
		Map<ProjectPath, String> conflicts = session.conflicts();
		conflicts.keySet().removeAll(keptEdits);
		return conflicts;
	}

	/**
	 * Look for changes made elsewhere: keep the drafts, forget what
	 * listings told, find conflicts, read open files without unsaved
	 * text again when they changed, and find what runs. Return the files
	 * read again.
	 */
	public Set<ProjectPath> refresh() {
		persist();
		store.forgetListings();
		session.refresh();
		Map<ProjectPath, String> clean = new HashMap<>();
		synchronized (this) {
			for (Map.Entry<ProjectPath, Doc> entry : docs.entrySet()) {
				Doc doc = entry.getValue();
				if (doc.text.equals(doc.stored) &&
						session.draft(entry.getKey()) == null) {
					clean.put(entry.getKey(), doc.base);
				}
			}
		}
		Set<ProjectPath> reloaded = new HashSet<>();
		for (Map.Entry<ProjectPath, String> entry : clean.entrySet()) {
			if (!(store.read(entry.getKey()) instanceof Content content) ||
					content.revision().equals(entry.getValue())) {
				continue;
			}
			String text = text(content.bytes());
			synchronized (this) {
				Doc doc = docs.get(entry.getKey());
				// Unless it was edited meanwhile.
				if (text != null && doc != null &&
						doc.text.equals(doc.stored)) {
					doc.text = doc.stored = doc.journaled = text;
					doc.base = content.revision();
					doc.state = null;
					reloaded.add(entry.getKey());
				}
			}
		}
		findRunning();
		changed();
		return reloaded;
	}

	/**
	 * Drop the unsaved text of path and read it again from storage.
	 * Return null when done, or why it cannot be read.
	 */
	public String reload(ProjectPath path) {
		session.clearDraft(path);
		synchronized (this) {
			docs.remove(path);
			keptEdits.remove(path);
		}
		String problem = load(path);
		changed();
		return problem;
	}

	/**
	 * Keep the unsaved text of path although the file changed elsewhere;
	 * the next explicit save overwrites the file.
	 */
	public void keepEdits(ProjectPath path) {
		synchronized (this) {
			keptEdits.add(path);
		}
		changed();
	}

	/**
	 * Write the unsaved text of path to a new file next to it, open that
	 * file, and read path again from storage.
	 */
	public Copy saveCopy(ProjectPath path) {
		persist();
		Draft draft = session.draft(path);
		ProjectPath to = freeCopyPath(path);
		if (draft == null || to == null) {
			return new Copy(null, draft == null
					? "there are no edits of " + path
					: "there is no free name for a copy of " + path);
		}
		Outcome outcome = session.saveCopy(path, to);
		if (!(outcome instanceof Saved saved)) {
			return new Copy(null, ((EditingSession.Failed) outcome).reason());
		}
		Doc copy = new Doc();
		copy.text = copy.stored = copy.journaled = draft.text().toString();
		copy.base = saved.revision();
		synchronized (this) {
			docs.remove(path);
			keptEdits.remove(path);
			docs.put(to, copy);
		}
		load(path);
		changed();
		return new Copy(to, null);
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
		changed();
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
		changed();
		return sessions.setRunning(id, path);
	}

	/**
	 * Prepare what runs from the text in the editor and the drafts, or
	 * return null when nothing runs, and keep the files it read as those
	 * of the last build. Any thread.
	 */
	public Preparation prepare(Map<String, String> reserved) {
		ProjectPath entry = running();
		Preparation preparation = entry != null
				? Preparation.prepare(files, entry, ProjectLimits.DEFAULT,
						reserved)
				: null;
		List<Used> read = new ArrayList<>();
		if (preparation != null) {
			for (SourceAssembly.Dependency dependency :
					preparation.dependencies()) {
				ProjectPath path = dependency.path();
				if (dependency.revision() != null &&
						!ProjectManifest.FILE.equals(path)) {
					read.add(new Used(path,
							path.getKind() == ProjectPath.Kind.IMAGE));
				}
			}
		}
		synchronized (this) {
			used = Collections.unmodifiableList(read);
		}
		changed();
		return preparation;
	}

	/**
	 * Return the files the last build read, in the order it read them,
	 * without the manifest and files it could not read.
	 */
	public synchronized List<Used> used() {
		return used;
	}

	/** Return whether the last build of what runs read path. */
	public synchronized boolean uses(ProjectPath path) {
		for (Used file : used) {
			if (file.path().equals(path)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Return whether path, open in the editor, can run: a .glsl file
	 * whose own text defines main. Reads only that text, once.
	 */
	public boolean canRun(ProjectPath path) {
		String text = path.getKind() == ProjectPath.Kind.SHADER
				? text(path)
				: null;
		return text != null && definesMain(text);
	}

	/** Keep the diagnostics of the last build of what runs. */
	public void setDiagnostics(List<ProjectDiagnostic> diagnostics) {
		synchronized (this) {
			this.diagnostics = List.copyOf(diagnostics);
		}
		changed();
	}

	/** Return the diagnostics of the last build, of all files. */
	public synchronized List<ProjectDiagnostic> diagnostics() {
		return diagnostics;
	}

	/** Return the diagnostics of the last build located in file. */
	public synchronized List<ProjectDiagnostic> diagnostics(ProjectPath file) {
		List<ProjectDiagnostic> inFile = new ArrayList<>();
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (file != null && file.equals(diagnostic.file())) {
				inFile.add(diagnostic);
			}
		}
		return inFile;
	}

	/**
	 * Return the marks of path, a file or a folder with everything under
	 * it, or of the whole project with diagnostics of no file when null.
	 * Memory only.
	 */
	public synchronized Marks marks(ProjectPath path) {
		int unsaved = 0;
		for (ProjectPath file : unsaved()) {
			unsaved += under(file, path) ? 1 : 0;
		}
		int conflicts = 0;
		for (ProjectPath file : conflicts().keySet()) {
			conflicts += under(file, path) ? 1 : 0;
		}
		int errors = 0;
		int warnings = 0;
		for (ProjectDiagnostic diagnostic : diagnostics) {
			ProjectPath file = diagnostic.file();
			if (file == null ? path != null : !under(file, path)) {
				continue;
			} else if (diagnostic.severity() ==
					ProjectDiagnostic.Severity.ERROR) {
				++errors;
			} else {
				++warnings;
			}
		}
		return new Marks(unsaved, errors, warnings, conflicts);
	}

	/** Return how many diagnostics of the last build are errors. */
	public synchronized int errors() {
		int errors = 0;
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				++errors;
			}
		}
		return errors;
	}

	private synchronized State state() {
		return new State(unsaved(), conflicts().keySet(), running, used,
				diagnostics);
	}

	// Call the listener, unless nothing it hears about changed or a call
	// is on its way.
	private void changed() {
		Executor post;
		synchronized (this) {
			State state = state();
			if (state.equals(announced)) {
				return;
			}
			announced = state;
			if (listener == null || pending) {
				return;
			}
			pending = true;
			post = executor;
		}
		post.execute(this::announce);
	}

	private void announce() {
		Runnable call;
		synchronized (this) {
			pending = false;
			call = listener;
		}
		if (call != null) {
			call.run();
		}
	}

	private static boolean under(ProjectPath file, ProjectPath path) {
		return path == null || file.equals(path) ||
				file.toString().startsWith(path + "/");
	}

	private ProjectPath freeCopyPath(ProjectPath path) {
		String name = path.toString();
		int dot = name.lastIndexOf('.');
		if (dot <= name.lastIndexOf('/') + 1) {
			dot = name.length();
		}
		for (int i = 1; i < 100; ++i) {
			ProjectParseResult<ProjectPath> copy = ProjectPath.parse(
					name.substring(0, dot) + " copy" + (i > 1 ? " " + i : "") +
							name.substring(dot));
			if (copy.isSuccess() && store.read(copy.value()) == null &&
					session.draft(copy.value()) == null) {
				return copy.value();
			}
		}
		return null;
	}

	// Return the text of bytes, or null when they are not text the
	// editor can hold.
	private static String text(byte[] bytes) {
		String text = bytes.length <= MAX_TEXT
				? SourceText.decode(bytes)
				: null;
		return text != null && text.indexOf('\0') < 0 ? text : null;
	}

	// Whether text declares a function main outside any other block and
	// gives it a body, as the lexer reads it.
	static boolean definesMain(String text) {
		Tokens tokens = GlslLexer.lex(text);
		for (Outline.Declaration declaration :
				Outline.of(tokens, text).declarations()) {
			if (declaration.kind() != Outline.Kind.FUNCTION ||
					!"main".equals(declaration.name())) {
				continue;
			}
			int parens = 0;
			for (int i = tokens.indexAt(declaration.start()) + 1,
					size = tokens.size(); i < size; ++i) {
				if (tokens.style(i) == Style.COMMENT ||
						tokens.inDirective(i)) {
					continue;
				}
				char c = text.charAt(tokens.start(i));
				if (c == '(') {
					++parens;
				} else if (c == ')') {
					--parens;
				} else if (parens == 0) {
					if (c == '{') {
						return true;
					}
					break;
				}
			}
		}
		return false;
	}

	private static int compareNames(String a, String b) {
		int order = a.compareToIgnoreCase(b);
		return order != 0 ? order : a.compareTo(b);
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
