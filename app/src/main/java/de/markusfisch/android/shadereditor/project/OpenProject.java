package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.EditingSession.Outcome;
import de.markusfisch.android.shadereditor.project.EditingSession.Saved;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * The project open in the editor, for the screens that show it: its
 * folders, a {@link Document} for every file opened, saving, changes
 * made elsewhere, what runs, prepared from the documents, and the
 * diagnostics of its last build by file. Unsaved text is kept by an
 * {@link EditingSession}, so it survives the process. The project
 * belongs to the main thread, like its documents: call every method
 * there, except open, list and offers, which read storage and are
 * called on a worker. Work on storage runs on the worker given at
 * opening, one task at a time in the order asked, and its result is
 * posted to main. One listener hears when what the screens mark
 * changes.
 */
public final class OpenProject {
	public static final ProjectPath MAIN = ProjectPath.parse("main.glsl")
			.value();

	/**
	 * How many documents without unsaved text stay in memory: those
	 * opened or shown most recently, besides the one shown. Every
	 * document with unsaved text stays; the others are dropped with
	 * their undo history and opened again on demand.
	 */
	static final int KEPT_CLEAN = 4;

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

	// A file opened in the editor: its document, what the view keeps for
	// it, and the revision and modified state its draft was last kept
	// at.
	private final class Doc implements Document.Listener {
		private final Document document;
		private Object state;
		private int drafted;
		private boolean draftModified;

		private Doc(Document document) {
			this.document = document;
		}

		@Override
		public void onUpdate() {
			changed();
		}
	}

	// What the listener hears about.
	private record State(Set<ProjectPath> unsaved, Set<ProjectPath> conflicts,
			ProjectPath running, List<Used> used,
			List<ProjectDiagnostic> diagnostics) {
	}

	private final Map<ProjectPath, Doc> docs = new HashMap<>();
	// The paths of the documents, opened or shown most recently last.
	private final Set<ProjectPath> recent = new LinkedHashSet<>();
	private final Set<ProjectPath> keptEdits = new HashSet<>();
	private final String id;
	private final String name;
	private final String location;
	private final ProjectFiles store;
	private final ProjectSessions sessions;
	private final EditingSession session;
	private final FileTexts texts;
	private final Executor worker;
	private final Executor main;
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
			ProjectSessions.Recent recent, Executor worker, Executor main) {
		this.id = id;
		name = recent != null ? recent.name() : null;
		location = recent != null ? recent.location() : null;
		this.store = store;
		this.sessions = sessions;
		this.session = session;
		this.worker = worker;
		this.main = main;
		texts = new FileTexts(session, worker, main);
	}

	/**
	 * Open session id of sessions over store with the drafts it kept,
	 * the file shown last and its name and location among the recent
	 * projects, and find what runs; return null when there is no such
	 * session. Storage work runs on worker, one task at a time, and
	 * results are posted to main.
	 */
	public static OpenProject open(String id, ProjectFiles store,
			ProjectSessions sessions, Executor worker, Executor main) {
		DraftJournal journal = sessions.drafts(id);
		if (journal == null) {
			return null;
		}
		OpenProject project = new OpenProject(id, store, sessions,
				EditingSession.restore(store, journal), sessions.recent(id),
				worker, main);
		project.shown = project.keptShown = sessions.shown(id);
		project.running = project.findRunning(Map.of());
		return project;
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
	public void setListener(Executor executor, Runnable listener) {
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
	 * Open path for the editor as a document of its draft, which starts
	 * with unsaved text, or of its stored text, unless it is in memory
	 * already. size is its size as the listing told it, or
	 * {@link ProjectFiles#UNKNOWN}. done gets null once
	 * {@link #document} has it, or why it cannot be edited.
	 */
	public void load(ProjectPath path, long size, Callback<String> done) {
		if (docs.containsKey(path)) {
			use(path);
			main.execute(() -> done.done(null));
			return;
		}
		texts.open(path, size, opening -> {
			if (opening instanceof FileTexts.Unopened unopened) {
				done.done(unopened.reason());
				return;
			}
			if (!docs.containsKey(path)) {
				add(path, (FileTexts.Opened) opening);
			}
			use(path);
			changed();
			done.done(null);
		});
	}

	/**
	 * Return the document of path, with its text, selection and undo
	 * history, while it is in memory, else null.
	 */
	public Document document(ProjectPath path) {
		Doc doc = docs.get(path);
		return doc != null ? doc.document : null;
	}

	/**
	 * Return what the view keeps for path, such as where it scrolled
	 * to, or null when it keeps nothing; it goes with the document.
	 */
	public Object editorState(ProjectPath path) {
		Doc doc = docs.get(path);
		return doc != null ? doc.state : null;
	}

	public void setEditorState(ProjectPath path, Object state) {
		Doc doc = docs.get(path);
		if (doc != null) {
			doc.state = state;
		}
	}

	/**
	 * Return the file shown in the editor, or null; after opening, the
	 * file shown last.
	 */
	public ProjectPath shown() {
		return shown;
	}

	/**
	 * Show path in the editor and return its document, or null when it
	 * is not in memory; nothing else changes.
	 */
	public Document show(ProjectPath path) {
		shown = path;
		if (docs.containsKey(path)) {
			use(path);
		}
		return document(path);
	}

	/**
	 * Return every file with unsaved text: the modified documents and
	 * the drafts of files not in memory.
	 */
	public Set<ProjectPath> unsaved() {
		Set<ProjectPath> unsaved = new LinkedHashSet<>(session.unsaved());
		for (Map.Entry<ProjectPath, Doc> entry : docs.entrySet()) {
			if (entry.getValue().document.isModified()) {
				unsaved.add(entry.getKey());
			} else {
				unsaved.remove(entry.getKey());
			}
		}
		return unsaved;
	}

	/**
	 * Keep the text of every document and the file shown durably where
	 * they changed since: a modified document as a draft, one without
	 * unsaved text by dropping its draft.
	 */
	public void persist() {
		ProjectPath show = shown;
		if (show == null ? keptShown != null : !show.equals(keptShown)) {
			keptShown = show;
			worker.execute(() -> sessions.setShown(id, show));
		}
		for (Map.Entry<ProjectPath, Doc> entry : docs.entrySet()) {
			Doc doc = entry.getValue();
			boolean modified = doc.document.isModified();
			if (doc.document.revision() != doc.drafted ||
					modified != doc.draftModified) {
				doc.drafted = doc.document.revision();
				doc.draftModified = modified;
				texts.draft(entry.getKey(), doc.document.text(), modified);
			}
		}
	}

	/**
	 * Save every file with unsaved text where it is, and mark each
	 * document saved as it was when its text was taken. done gets the
	 * outcome by path. A file changed elsewhere is saved only when
	 * explicit and the user chose to keep their edits.
	 */
	public void save(boolean explicit, Callback<Map<ProjectPath, Outcome>> done) {
		persist();
		if (explicit && !keptEdits.isEmpty()) {
			List<ProjectPath> kept = new ArrayList<>(keptEdits);
			worker.execute(() -> {
				List<ProjectPath> resolved = new ArrayList<>();
				for (ProjectPath path : kept) {
					if (session.keepEdits(path) == null) {
						resolved.add(path);
					}
				}
				main.execute(() -> keptEdits.removeAll(resolved));
			});
		}
		Map<ProjectPath, Outcome> outcomes = new LinkedHashMap<>();
		int[] left = {1};
		Runnable finished = () -> {
			if (--left[0] == 0) {
				trim();
				changed();
				done.done(outcomes);
			}
		};
		for (ProjectPath path : unsaved()) {
			Doc doc = docs.get(path);
			Draft draft = doc == null ? session.draft(path) : null;
			if (doc == null && draft == null) {
				continue;
			}
			outcomes.put(path, null);
			++left[0];
			texts.save(path, doc != null ? doc.document.text() : draft.text(),
					doc != null ? doc.document.state() : -1, save -> {
						if (save.outcome() instanceof Saved &&
								doc != null && docs.get(path) == doc) {
							doc.document.markSaved(save.state());
						}
						outcomes.put(path, save.outcome());
						finished.run();
					});
		}
		main.execute(finished);
	}

	/**
	 * Return why each file with unsaved text changed elsewhere, for the
	 * files the user has not decided about yet.
	 */
	public Map<ProjectPath, String> conflicts() {
		Map<ProjectPath, String> conflicts = session.conflicts();
		conflicts.keySet().removeAll(keptEdits);
		return conflicts;
	}

	/**
	 * Look for changes made elsewhere: keep the drafts, forget what
	 * listings told, find conflicts, give documents without unsaved
	 * text the stored text when it changed, as one change that can be
	 * undone, and find what runs. done gets the files read again.
	 */
	public void refresh(Callback<Set<ProjectPath>> done) {
		persist();
		worker.execute(() -> {
			store.forgetListings();
			session.refresh();
		});
		Set<ProjectPath> reread = new HashSet<>();
		for (Map.Entry<ProjectPath, Doc> entry : docs.entrySet()) {
			ProjectPath path = entry.getKey();
			Doc doc = entry.getValue();
			int revision = doc.document.revision();
			if (doc.document.isModified()) {
				continue;
			}
			texts.reread(path, changed -> {
				// Unless it was edited meanwhile.
				if (changed != null && docs.get(path) == doc &&
						doc.document.revision() == revision) {
					takeStored(doc, changed.text());
					texts.rebase(path, changed.revision());
					reread.add(path);
				}
			});
		}
		updateRunning(() -> done.done(reread));
	}

	/**
	 * Drop the unsaved text of path and read it again from storage, into
	 * its document as one change that can be undone. done gets null
	 * when done, or why it cannot be read.
	 */
	public void reload(ProjectPath path, Callback<String> done) {
		keptEdits.remove(path);
		Doc doc = docs.get(path);
		if (doc != null) {
			doc.drafted = doc.document.revision();
			doc.draftModified = false;
		}
		texts.draft(path, Text.EMPTY, false);
		texts.open(path, ProjectFiles.UNKNOWN, opening -> {
			String problem = null;
			if (opening instanceof FileTexts.Opened opened) {
				Doc current = docs.get(path);
				if (current == null) {
					add(path, opened);
					use(path);
				} else {
					takeStored(current, opened.text());
				}
			} else {
				problem = ((FileTexts.Unopened) opening).reason();
			}
			changed();
			done.done(problem);
		});
	}

	/**
	 * Keep the unsaved text of path although the file changed elsewhere;
	 * the next explicit save overwrites the file.
	 */
	public void keepEdits(ProjectPath path) {
		keptEdits.add(path);
		changed();
	}

	/**
	 * Write the unsaved text of path to a new file next to it, read path
	 * again from storage and open the copy. done gets where the copy
	 * is, or why it was not written.
	 */
	public void saveCopy(ProjectPath path, Callback<Copy> done) {
		persist();
		worker.execute(() -> {
			Draft draft = session.draft(path);
			ProjectPath to = draft != null ? freeCopyPath(path) : null;
			Copy copy;
			if (to == null) {
				copy = new Copy(null, draft == null
						? "there are no edits of " + path
						: "there is no free name for a copy of " + path);
			} else if (session.saveCopy(path, to) instanceof
					EditingSession.Failed failed) {
				copy = new Copy(null, failed.reason());
			} else {
				copy = new Copy(to, null);
			}
			main.execute(() -> {
				if (copy.path() == null) {
					done.done(copy);
					return;
				}
				keptEdits.remove(path);
				reload(path, problem -> load(to, ProjectFiles.UNKNOWN,
						opened -> done.done(copy)));
			});
		});
	}

	/** Return the file that runs, or null when nothing runs. */
	public ProjectPath running() {
		return running;
	}

	/**
	 * Run path from now on, also after reopening, until another file is
	 * chosen; the project's files stay as they are.
	 */
	public void runThisFile(ProjectPath path) {
		running = path;
		changed();
		worker.execute(() -> sessions.setRunning(id, path));
	}

	/**
	 * Prepare what runs from the text of the documents and the drafts,
	 * and keep the files it read as those of the last build. done gets
	 * the preparation, or null when nothing runs.
	 */
	public void prepare(Map<String, String> reserved,
			Callback<Preparation> done) {
		ProjectPath entry = running;
		Map<ProjectPath, Text> snapshot = snapshot();
		worker.execute(() -> {
			Preparation preparation = entry != null
					? Preparation.prepare(new Edited(snapshot), entry,
							ProjectLimits.DEFAULT, reserved)
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
			main.execute(() -> {
				used = Collections.unmodifiableList(read);
				changed();
				done.done(preparation);
			});
		});
	}

	/**
	 * Return the files the last build read, in the order it read them,
	 * without the manifest and files it could not read.
	 */
	public List<Used> used() {
		return used;
	}

	/** Return whether the last build of what runs read path. */
	public boolean uses(ProjectPath path) {
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
		Doc doc = path.getKind() == ProjectPath.Kind.SHADER
				? docs.get(path)
				: null;
		return doc != null && definesMain(doc.document.text().toString());
	}

	/** Keep the diagnostics of the last build of what runs. */
	public void setDiagnostics(List<ProjectDiagnostic> diagnostics) {
		this.diagnostics = List.copyOf(diagnostics);
		changed();
	}

	/** Return the diagnostics of the last build, of all files. */
	public List<ProjectDiagnostic> diagnostics() {
		return diagnostics;
	}

	/** Return the diagnostics of the last build located in file. */
	public List<ProjectDiagnostic> diagnostics(ProjectPath file) {
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
	public Marks marks(ProjectPath path) {
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
	public int errors() {
		int errors = 0;
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				++errors;
			}
		}
		return errors;
	}

	private void add(ProjectPath path, FileTexts.Opened opened) {
		Doc doc = new Doc(new Document(opened.text()));
		if (opened.unsaved()) {
			doc.document.markSaved(-1);
			doc.draftModified = true;
		}
		doc.document.addListener(doc);
		docs.put(path, doc);
	}

	// Count path as opened or shown last and drop the documents beyond
	// those kept.
	private void use(ProjectPath path) {
		recent.remove(path);
		recent.add(path);
		trim();
	}

	private void trim() {
		List<ProjectPath> paths = new ArrayList<>(recent);
		int clean = 0;
		for (int i = paths.size(); i-- > 0; ) {
			ProjectPath path = paths.get(i);
			Doc doc = docs.get(path);
			// One whose draft is not dropped yet waits for persist.
			if (doc == null || doc.document.isModified() ||
					doc.draftModified || path.equals(shown) ||
					++clean <= KEPT_CLEAN) {
				continue;
			}
			docs.remove(path);
			recent.remove(path);
			doc.document.removeListener(doc);
		}
	}

	// Give the document the stored text as one change that can be
	// undone, keeping the selection where it can.
	private void takeStored(Doc doc, Text text) {
		Document document = doc.document;
		Document.Selection selection = document.selection();
		document.beginBatch();
		document.replace(0, document.text().length(), text);
		document.select(Math.min(selection.anchor(), text.length()),
				Math.min(selection.head(), text.length()));
		document.markSaved(document.state());
		document.endBatch();
		doc.drafted = document.revision();
		doc.draftModified = false;
	}

	private Map<ProjectPath, Text> snapshot() {
		Map<ProjectPath, Text> snapshot = new HashMap<>();
		for (Map.Entry<ProjectPath, Doc> entry : docs.entrySet()) {
			snapshot.put(entry.getKey(), entry.getValue().document.text());
		}
		return snapshot;
	}

	private void updateRunning(Runnable then) {
		Map<ProjectPath, Text> snapshot = snapshot();
		worker.execute(() -> {
			ProjectPath found = findRunning(snapshot);
			main.execute(() -> {
				running = found;
				changed();
				then.run();
			});
		});
	}

	// Find what runs by the one rule: the file last chosen with Run this
	// file if it still exists, else the entry of a valid manifest, else
	// main.glsl when present, else nothing. On the worker.
	private ProjectPath findRunning(Map<ProjectPath, Text> snapshot) {
		ProjectFiles files = new Edited(snapshot);
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
		return found;
	}

	private State state() {
		return new State(unsaved(), conflicts().keySet(), running, used,
				diagnostics);
	}

	// Call the listener, unless nothing it hears about changed or a call
	// is on its way.
	private void changed() {
		State state = state();
		if (state.equals(announced)) {
			return;
		}
		announced = state;
		if (listener == null || pending) {
			return;
		}
		pending = true;
		executor.execute(this::announce);
	}

	private void announce() {
		pending = false;
		if (listener != null) {
			listener.run();
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

	// The project as the editor had it when the snapshot was taken:
	// files in memory with the text of their documents, the others with
	// their drafts or as stored.
	private final class Edited implements ProjectFiles {
		private final Map<ProjectPath, Text> snapshot;

		private Edited(Map<ProjectPath, Text> snapshot) {
			this.snapshot = snapshot;
		}

		@Override
		public Read read(ProjectPath path) {
			Text text = snapshot.get(path);
			if (text == null) {
				return session.files().read(path);
			}
			byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
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
