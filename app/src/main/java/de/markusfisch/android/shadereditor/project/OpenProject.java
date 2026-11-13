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

	/**
	 * A file the last build of what runs is made of, and whether it is
	 * an image.
	 */
	public record Used(ProjectPath path, boolean image) {
	}

	// What the listener hears about.
	private record State(Set<ProjectPath> unsaved, Set<ProjectPath> conflicts,
			ProjectPath running, List<Used> used,
			List<ProjectDiagnostic> diagnostics) {
	}

	// The files opened, being opened or worked on in storage.
	private final Map<ProjectPath, FileRecord> records = new HashMap<>();
	// The callbacks of the opens in flight, which later ones join.
	private final Map<ProjectPath, List<Callback<String>>> opening =
			new HashMap<>();
	private final Document.Listener updates = new Document.Listener() {
		@Override
		public void onUpdate() {
			changed();
		}
	};
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
		if (loaded(path) != null) {
			use(path);
			main.execute(() -> done.done(null));
			return;
		}
		List<Callback<String>> joined = opening.get(path);
		if (joined != null) {
			joined.add(done);
			return;
		}
		joined = new ArrayList<>();
		joined.add(done);
		opening.put(path, joined);
		FileRecord record = record(path);
		record.whenIdle(() -> open(record, size));
	}

	/**
	 * Return the document of path, with its text, selection and undo
	 * history, while it is in memory, else null.
	 */
	public Document document(ProjectPath path) {
		FileRecord record = records.get(path);
		return record != null ? record.document() : null;
	}

	/**
	 * Return what the view keeps for path, such as where it scrolled
	 * to, or null when it keeps nothing; it goes with the document.
	 */
	public Object editorState(ProjectPath path) {
		FileRecord record = loaded(path);
		return record != null ? record.state : null;
	}

	public void setEditorState(ProjectPath path, Object state) {
		FileRecord record = loaded(path);
		if (record != null) {
			record.state = state;
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
		if (loaded(path) != null) {
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
		for (FileRecord record : records.values()) {
			Document document = record.document();
			if (document == null) {
				continue;
			} else if (document.isModified()) {
				unsaved.add(record.path);
			} else {
				unsaved.remove(record.path);
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
		for (FileRecord record : records.values()) {
			Document document = record.document();
			if (document == null) {
				continue;
			}
			boolean modified = document.isModified();
			if (document.revision() != record.drafted ||
					modified != record.draftModified) {
				record.drafted = document.revision();
				record.draftModified = modified;
				texts.draft(record.path, document.text(), modified,
						record.base());
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
			if (loaded(path) == null && session.draft(path) == null) {
				continue;
			}
			outcomes.put(path, null);
			++left[0];
			FileRecord record = record(path);
			record.whenIdle(() -> save(record, save -> {
				outcomes.put(path, save != null ? save.outcome() : null);
				finished.run();
			}));
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
		for (FileRecord record : new ArrayList<>(records.values())) {
			Document document = record.document();
			if (document != null && !document.isModified()) {
				record.whenIdle(() -> check(record, reread));
			}
		}
		updateRunning(() -> done.done(reread));
	}

	/**
	 * Read path again from storage into its document, as one change that
	 * can be undone, and drop its unsaved text once the document took
	 * the stored text; text typed after the call is kept instead. done
	 * gets null when done, or why it cannot be read.
	 */
	public void reload(ProjectPath path, Callback<String> done) {
		keptEdits.remove(path);
		FileRecord record = record(path);
		record.whenIdle(() -> reload(record, done));
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
	 * Write the unsaved text of path to a new file next to it and open
	 * the copy; path keeps its unsaved text. done gets where the copy
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
				load(to, ProjectFiles.UNKNOWN, opened -> done.done(copy));
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
	 * and keep the files it is made of as those of the last build. done
	 * gets the preparation, or null when nothing runs.
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
						preparation.uses()) {
					ProjectPath path = dependency.path();
					if (dependency.revision() != null) {
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
	 * Return the files the last build of what runs is made of, in the
	 * order it read them, without files it could not read; see
	 * {@link Preparation#uses()}.
	 */
	public List<Used> used() {
		return used;
	}

	/** Return whether the last build of what runs is made of path. */
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
		FileRecord record = path.getKind() == ProjectPath.Kind.SHADER
				? loaded(path)
				: null;
		return record != null &&
				definesMain(record.document().text().toString());
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

	// Return the record of path, made when there is none.
	private FileRecord record(ProjectPath path) {
		FileRecord record = records.get(path);
		if (record == null) {
			record = new FileRecord(path);
			records.put(path, record);
		}
		return record;
	}

	// Return the record of path while its document is in memory.
	private FileRecord loaded(ProjectPath path) {
		FileRecord record = records.get(path);
		return record != null && record.document() != null ? record : null;
	}

	// End request of record and forget the record when it has nothing
	// left to keep.
	private void end(FileRecord record, FileRecord.Request request) {
		record.end(request);
		if (record.document() == null && record.pending() == null &&
				records.get(record.path) == record) {
			records.remove(record.path);
		}
	}

	private void open(FileRecord record, long size) {
		FileRecord.Request request = record.start(FileRecord.Kind.OPEN);
		ProjectPath path = record.path;
		texts.open(path, size, opened -> {
			String problem = null;
			if (opened instanceof FileTexts.Unopened unopened) {
				problem = unopened.reason();
			} else {
				take(record, request, (FileTexts.Opened) opened);
			}
			List<Callback<String>> joined = opening.remove(path);
			end(record, request);
			changed();
			if (joined != null) {
				for (Callback<String> done : joined) {
					done.done(problem);
				}
			}
		});
	}

	// Give record a document of opened, unless it has one; return
	// whether it took it.
	private boolean take(FileRecord record, FileRecord.Request request,
			FileTexts.Opened opened) {
		Document document = new Document(opened.text());
		if (opened.unsaved()) {
			document.markSaved(-1);
		}
		if (records.get(record.path) != record ||
				!record.adoptOpened(request, document, opened.revision())) {
			return false;
		}
		record.draftModified = opened.unsaved();
		document.addListener(updates);
		use(record.path);
		return true;
	}

	// Save the text the document of record or its draft has now; done
	// gets the save, or null when there was nothing to save.
	private void save(FileRecord record, Callback<FileTexts.Save> done) {
		FileRecord.Request request = record.start(FileRecord.Kind.SAVE);
		ProjectPath path = record.path;
		Document document = record.document();
		Draft draft = document == null ? session.draft(path) : null;
		if (document == null && draft == null) {
			end(record, request);
			done.done(null);
			return;
		}
		texts.save(path, document != null ? document.text() : draft.text(),
				document != null ? document.state() : -1, request.base(),
				save -> {
					if (save.outcome() instanceof Saved saved &&
							records.get(path) == record) {
						record.adoptSaved(request, save.state(),
								saved.revision());
					}
					end(record, request);
					done.done(save);
				});
	}

	// Give the document of record the stored text when it changed and
	// the document is not edited before it arrives.
	private void check(FileRecord record, Set<ProjectPath> reread) {
		FileRecord.Request request = record.start(FileRecord.Kind.CHECK);
		Document document = record.document();
		if (document == null || document.isModified()) {
			end(record, request);
			return;
		}
		texts.reread(record.path, request.base(), changed -> {
			if (changed != null && records.get(record.path) == record &&
					record.adoptStored(request, changed.text(),
							changed.revision())) {
				reread.add(record.path);
			}
			end(record, request);
		});
	}

	private void reload(FileRecord record, Callback<String> done) {
		FileRecord.Request request = record.start(FileRecord.Kind.RELOAD);
		ProjectPath path = record.path;
		texts.openStored(path, opening -> {
			boolean took = false;
			String problem = null;
			if (opening instanceof FileTexts.Opened opened) {
				// Typing that came after the request stays, with its
				// draft.
				took = record.document() == null
						? take(record, request, opened)
						: records.get(path) == record &&
								record.adoptStored(request, opened.text(),
										opened.revision());
			} else {
				problem = ((FileTexts.Unopened) opening).reason();
			}
			String reason = problem;
			Runnable finish = () -> {
				end(record, request);
				changed();
				done.done(reason);
			};
			if (took) {
				// The draft goes only now that the stored text is taken.
				texts.draft(path, Text.EMPTY, false, null);
				worker.execute(() -> main.execute(finish));
			} else {
				finish.run();
			}
		});
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
			FileRecord record = loaded(path);
			// One whose draft is not dropped yet waits for persist, one
			// with an operation in flight for its outcome.
			if (record == null || record.document().isModified() ||
					record.draftModified || path.equals(shown) ||
					++clean <= KEPT_CLEAN || record.pending() != null) {
				continue;
			}
			records.remove(path);
			recent.remove(path);
			record.drop().removeListener(updates);
		}
	}

	private Map<ProjectPath, Text> snapshot() {
		Map<ProjectPath, Text> snapshot = new HashMap<>();
		for (FileRecord record : records.values()) {
			if (record.document() != null) {
				snapshot.put(record.path, record.document().text());
			}
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
