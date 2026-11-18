package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * The project open in the editor, for the screens that show it: its
 * folders, a {@link Document} for every file opened, saving, changes
 * made elsewhere, what runs, prepared from the documents, and the
 * diagnostics of its last build by file. Unsaved text is kept in a
 * journal a moment after it is typed, so it survives the process, and
 * made durable by {@link #flush}. The project belongs to the main
 * thread, like its documents: call every method there, except open,
 * list and offers, which read storage and are called on a worker.
 * Work on storage runs on the worker given at opening, one task at a
 * time in the order asked, and its result is posted to main. One
 * listener hears when what the screens mark changes.
 */
public final class OpenProject {
	/** The file that runs when nothing else was chosen. */
	public static final ProjectPath MAIN = ProjectPath.parse("main.frag")
			.value();

	/**
	 * The fewest and the most bytes the documents in memory may take
	 * besides the one shown, see {@link #budget}. Beyond them documents
	 * are dropped with their undo history and opened again on demand,
	 * the one used least recently first, with unsaved text or without.
	 */
	static final long MIN_BUDGET = 8L * 1024 * 1024;
	static final long MAX_BUDGET = 32L * 1024 * 1024;

	/**
	 * How long after the last change of a file its text goes to the
	 * journal, and at the latest after the first change not there yet,
	 * in milliseconds.
	 */
	static final long FLUSH_DELAY = 2000;
	static final long FLUSH_LIMIT = 5000;

	/** Whether the project's files can be read and changed. */
	public enum Access {
		AVAILABLE, READ_ONLY, UNAVAILABLE
	}

	/**
	 * The state of the project's storage, and why it is not available,
	 * else null.
	 */
	public record Storage(Access access, String reason) {
	}

	/**
	 * What runs, prepared from the text of the documents at one moment,
	 * see {@link #prepare}.
	 */
	public static final class Build {
		private final Preparation preparation;
		private final List<ProjectDiagnostic> nothingRuns;
		private final int generation;

		private Build(Preparation preparation,
				List<ProjectDiagnostic> nothingRuns, int generation) {
			this.preparation = preparation;
			this.nothingRuns = nothingRuns;
			this.generation = generation;
		}

		/** Return the preparation, or null when nothing runs. */
		public Preparation preparation() {
			return preparation;
		}

		/**
		 * Return what is wrong with the manifest when nothing runs, so
		 * that it shows all the same.
		 */
		public List<ProjectDiagnostic> nothingRuns() {
			return nothingRuns;
		}
	}

	/**
	 * One range of one document's text with other text in its place,
	 * for a build only: from start to end of the text of path, as the
	 * document has it when the build is asked for. The document, the
	 * journal and the stored file never see it.
	 */
	public record Replacement(ProjectPath path, int start, int end,
			String text) {
	}

	/** Whether a file's own text defines main, as far as known. */
	public enum Runs {
		YES, NO, UNKNOWN
	}

	/** How the user resolves a conflict. */
	public enum Choice {
		RELOAD, KEEP
	}

	/**
	 * What saving a file did: {@link Saved}, {@link Conflict},
	 * {@link Failed} or {@link Unreadable}.
	 */
	public interface Outcome {
	}

	/**
	 * The file was written; revision is the revision of the stored file
	 * now.
	 */
	public record Saved(String revision) implements Outcome {
	}

	/**
	 * The stored file changed or vanished since the text was based on
	 * it. Nothing was written and the text stays unsaved.
	 */
	public record Conflict(String reason) implements Outcome {
	}

	/** The file was not written; the text stays unsaved for a retry. */
	public record Failed(String reason) implements Outcome {
	}

	/**
	 * The file shown had no unsaved text and was not in storage
	 * anymore: it was written again, at revision.
	 */
	public record Created(String revision) implements Outcome {
	}

	/**
	 * The file shown had no unsaved text and changed elsewhere: its
	 * document took the stored text, and nothing was written.
	 */
	public record Reread() implements Outcome {
	}

	/**
	 * The unsaved text of the file cannot be read, for reason. Nothing
	 * was written, and the journal stays until {@link #dismiss}, which
	 * gives it up only when it is damaged.
	 */
	public record Unreadable(String reason, boolean damaged)
			implements Outcome {
	}

	/**
	 * What renaming or deleting a file or folder did: {@link Renamed},
	 * {@link Deleted}, {@link Unsaved} or {@link Refused}.
	 */
	public interface Moved {
	}

	/**
	 * The file or folder at from is at to now, in storage and for the
	 * project: the documents of the files it holds are the same, under
	 * the new paths, with their unsaved text and undo.
	 */
	public record Renamed(ProjectPath from, ProjectPath to)
			implements Moved {
	}

	/**
	 * The file or folder at path is deleted, in storage and for the
	 * project: the documents of the files it held there are closed and
	 * their unsaved text, as far as its deletion was asked for, is gone
	 * with its journal and undo history.
	 */
	public record Deleted(ProjectPath path) implements Moved {
	}

	/**
	 * Nothing was deleted: these files at or under the path have
	 * unsaved text, or unsaved text that cannot be read. texts names
	 * for each the text it has now; passed to {@link #delete} as it is,
	 * it deletes them with that text, unless one changed meanwhile.
	 */
	public record Unsaved(Map<ProjectPath, Integer> texts)
			implements Moved {
	}

	/**
	 * Nothing was renamed or deleted, for reason: the target exists or
	 * the project holds a file there (EXISTS), there is nothing at the
	 * path anymore (ABSENT), the storage does not offer the change
	 * (UNSUPPORTED) or failed (FAILED), or failed halfway (PARTIAL):
	 * then look again, see {@link #refresh}.
	 */
	public record Refused(ProjectFiles.Refusal refusal, String reason)
			implements Moved {
	}

	/**
	 * A rename or deletion that happened, numbered in the order they
	 * did, see {@link #events}.
	 */
	public record Event(int number, Moved change) {
	}

	/** Runs a task on the main thread after a delay in milliseconds. */
	interface Later {
		void post(Runnable task, long millis);
	}

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
	 * A file the last build of what runs is made of, whether it is an
	 * image, and what it is to the running shader, null when not known.
	 */
	public record Used(ProjectPath path, boolean image, FileRole role) {
		public Used(ProjectPath path, boolean image) {
			this(path, image, null);
		}
	}

	// The file shown, the file chosen to run, and when the project was
	// opened as the one open last, 0 for not at all: what the session
	// keeps of it.
	private record Selection(ProjectPath shown, ProjectPath chosen,
			long opened) {
	}

	// What the listener hears about.
	private record State(Set<ProjectPath> unsaved,
			Map<ProjectPath, String> conflicts,
			ProjectPath running, List<Used> used,
			List<ProjectDiagnostic> diagnostics, String unprotected,
			String unkept,
			Map<ProjectPath, FileTexts.Found> found,
			Map<ProjectPath, Runs> runs, Map<ProjectPath, String> unreadable,
			int damaged, int moves) {
	}

	// The files opened, being opened or worked on in storage.
	private final Map<ProjectPath, FileRecord> records = new HashMap<>();
	// The callbacks of the opens in flight, which later ones join.
	private final Map<ProjectPath, List<Callback<String>>> opening =
			new HashMap<>();
	// The paths of the documents, opened or shown most recently last.
	private final Set<ProjectPath> recent = new LinkedHashSet<>();
	private final String id;
	private final String name;
	private final String location;
	private final ProjectFiles store;
	private final ProjectSessions sessions;
	// Used on the worker only.
	private final Journal journal;
	private final Histories histories;
	private final FileTexts texts;
	private final Executor worker;
	private final Executor main;
	// Where what only looks at a file runs, as a tap asking whether it
	// is a picture: never on the queue every project saves on, so that a
	// slow provider holds up no save, here or elsewhere.
	private static final Executor LOOKS = Executors.newCachedThreadPool(
			job -> {
				Thread thread = new Thread(job, "looks");
				thread.setDaemon(true);
				return thread;
			});
	private Executor looks = LOOKS;
	// The same, also once closed: for what was asked on behalf of
	// someone the project does not outlive, such as a copy.
	private final Executor anyWorker;
	private final Executor anyMain;
	private final Later later;
	// The only file of a project of one document, else null.
	private final ProjectPath lone;
	private final Storage storage;
	private final long budget;
	private Runnable release;
	// Why the journal could not be made durable, or null.
	private String unsynced;
	// Whether the journals could not all be read when they were looked
	// at, so a file may have unsaved text that no record tells of.
	private boolean unscanned;
	private List<ProjectDiagnostic> diagnostics = List.of();
	private List<Used> used = List.of();
	// What the files of the last build know of each other.
	private ProjectKnowledge knowledge = ProjectKnowledge.NONE;
	// See wayBack().
	private Object wayBack;
	// The lines of the last build of what runs that name its files.
	private List<HostDirectives.Reference> references = List.of();
	// What ran when the files used were built.
	private ProjectPath usedFor;
	private ProjectPath running;
	private ProjectPath shown;
	// The file chosen to run, and when the project was opened as the
	// one open last: with shown what the session is to keep.
	private ProjectPath chosen;
	private long opened;
	// What the session keeps, as the worker said; the write on its way,
	// or null; and why the last could not keep all, or null.
	private Selection kept = new Selection(null, null, 0);
	private Selection sending;
	private String unkept;
	private State announced;
	private Executor executor;
	private Runnable listener;
	// What was renamed or deleted last, and how many were.
	private final List<Event> events = new ArrayList<>();
	private int moves;
	private boolean pending;
	private boolean closed;
	// How many journals have a header that cannot be read.
	private int damaged;
	// Counts the changes of text and of what runs, and the choices of
	// what runs alone; a result asked for before is stale.
	private int generation;
	// The generation the diagnostics are of, -1 before the first.
	private int diagnosed = -1;
	private int choice;

	private OpenProject(String id, ProjectFiles store,
			ProjectSessions sessions, Journal journal, Histories histories,
			ProjectSessions.Recent recent, Executor worker, Executor main,
			Later later, ProjectPath lone, Storage storage, long budget) {
		this.id = id;
		this.budget = budget;
		this.lone = lone;
		this.storage = storage;
		name = recent != null ? recent.name() : null;
		location = recent != null ? recent.location() : null;
		this.store = store;
		this.sessions = sessions;
		this.journal = journal;
		this.histories = histories;
		anyWorker = worker;
		anyMain = main;
		// Once closed, nothing more is asked of the worker, and what
		// arrives late does nothing.
		this.worker = task -> {
			if (!closed) {
				worker.execute(task);
			}
		};
		this.main = task -> main.execute(() -> {
			if (!closed) {
				task.run();
			}
		});
		this.later = (task, millis) -> later.post(() -> {
			if (!closed) {
				task.run();
			}
		}, millis);
		texts = new FileTexts(store, journal, histories, this.worker,
				this.main);
	}

	/**
	 * Open session id of sessions over store with the unsaved text its
	 * journal kept, the file shown last and its name and location among
	 * the recent projects, and find what runs; return null when there
	 * is no such session. A file whose stored text changed since its
	 * unsaved text was based on it is in conflict, unless storage holds
	 * exactly that text: then it is not unsaved anymore. Storage work runs on
	 * worker, one task at a time, results are posted to main, and later
	 * runs the timers that keep unsaved text in the journal; call it on
	 * worker, so it reads after what was written there before. lone is
	 * the only file of a project of one document, which
	 * runs, else null. A store of null cannot be reached for problem:
	 * the project opens with its unsaved text and tells that through
	 * {@link #storage}. budget is how many bytes the documents in
	 * memory may take besides the one shown.
	 */
	static OpenProject open(String id, ProjectFiles store, ProjectPath lone,
			String problem, ProjectSessions sessions, Executor worker,
			Executor main, Later later, long budget) {
		return open(id, store, lone, problem, sessions, worker, main, later,
				budget, null);
	}

	/**
	 * Open a project as above; kept follows its files when one is
	 * renamed or deleted, or is null.
	 */
	static OpenProject open(String id, ProjectFiles store, ProjectPath lone,
			String problem, ProjectSessions sessions, Executor worker,
			Executor main, Later later, long budget, FileTexts.Kept kept) {
		Journal journal = sessions.journal(id);
		if (journal == null) {
			return null;
		}
		// From here the session is not forgotten under the project, and
		// the project that had it before writes nothing more.
		journal.own();
		if (sessions.locator(id) == null) {
			journal.release();
			return null;
		}
		Storage storage;
		if (store == null) {
			storage = new Storage(Access.UNAVAILABLE, problem);
			store = new ProjectFiles() {
				@Override
				public Read read(ProjectPath path) {
					return new Failed(problem);
				}

				@Override
				public Listing list(ProjectPath folder) {
					return new Failed(problem);
				}
			};
		} else {
			storage = new Storage(store.offers(lone).any()
					? Access.AVAILABLE
					: Access.READ_ONLY, null);
		}
		OpenProject project = new OpenProject(id, store, sessions, journal,
				sessions.histories(id), sessions.recent(id), worker, main,
				later, lone, storage, budget);
		project.texts.kept = kept;
		journal.removeLeftovers();
		// A rename or deletion the process died in is finished or
		// dropped before the journals are looked at.
		follow(sessions, id, project.texts.recover());
		project.histories.trim();
		project.take(project.texts.journals(Set.of()));
		project.shown = sessions.shown(id);
		project.kept = new Selection(project.shown, null, 0);
		project.running = project.findRunning(Map.of(),
				project.journaled());
		return project;
	}

	// Take what a look at the journals found: a record for each file
	// with unsaved text that has none, and whether the look was whole.
	private void take(FileTexts.Journals journals) {
		damaged = journals.damaged();
		unscanned = !journals.complete();
		for (FileTexts.Unsaved unsaved : journals.unsaved()) {
			ProjectPath path = unsaved.header().path();
			if (!records.containsKey(path)) {
				record(path).recover(unsaved.header(), unsaved.found(),
						unsaved.revision(), unsaved.noted());
			}
		}
	}

	// Let the file shown and the file chosen to run of session id
	// follow move, when there was one. On the worker.
	private static void follow(ProjectSessions sessions, String id,
			Journal.Move move) {
		if (move == null) {
			return;
		}
		ProjectPath shown = sessions.shown(id);
		if (shown != null && move.touches(shown)) {
			sessions.setShown(id, move.moved(shown));
		}
		ProjectPath running = sessions.running(id);
		if (running != null && move.touches(running)) {
			sessions.setRunning(id, move.moved(running));
		}
	}

	public String id() {
		return id;
	}

	/**
	 * Return how many bytes documents may take on a heap of heap bytes:
	 * an eighth of it, within {@link #MIN_BUDGET} and
	 * {@link #MAX_BUDGET}.
	 */
	public static long budget(long heap) {
		return Math.max(MIN_BUDGET, Math.min(MAX_BUDGET, heap / 8));
	}

	/** Return whether the project's files can be read and changed. */
	public Storage storage() {
		return storage;
	}

	/**
	 * Keep everything typed in the journal, durably, and let the project
	 * go: its timers stop, results that arrive later do nothing, and the
	 * next opening of its session makes a new one. Its documents stay
	 * as they are, but nothing of the project follows them anymore, and
	 * no method does anything from now on.
	 */
	public void close() {
		if (closed) {
			return;
		}
		persist(true);
		keepHistories();
		texts.sync(synced -> {
		});
		texts.release();
		closed = true;
		listener = null;
		for (FileRecord record : records.values()) {
			if (record.document() != null) {
				record.document().removeListener(record.listener);
			}
		}
		records.clear();
		opening.clear();
		recent.clear();
		if (release != null) {
			release.run();
			release = null;
		}
	}

	// Call release when the project is closed.
	void onClose(Runnable release) {
		this.release = release;
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
	 * Return the first count bytes of the file at path as stored, or
	 * null when it cannot be read. Reads storage, as {@link #list}
	 * does: off the main thread.
	 */
	public byte[] head(ProjectPath path, int count) {
		return store.head(path, count);
	}

	/** What the manifest at the root says: the file it runs, or why not. */
	public record Manifest(ProjectPath entry, String problem) {
	}

	/**
	 * Return what the manifest at the root says, or null where there is
	 * none. Reads storage, as {@link #list} does: off the main thread.
	 */
	public Manifest manifest() {
		if (lone != null || !(store.read(ProjectManifest.FILE,
				Document.MAX_BYTES) instanceof Content content)) {
			return null;
		}
		String text = SourceText.decode(content.bytes());
		if (text == null) {
			return new Manifest(null, "it is not text");
		}
		ProjectParseResult<ProjectManifest> manifest =
				ProjectManifest.parse(text);
		return manifest.isSuccess()
				? new Manifest(manifest.value().entry(), null)
				: new Manifest(null, manifest.diagnostic().message());
	}

	/**
	 * Return how the editor treats the file at path: as its language
	 * says, and the manifest of a folder as the manifest, which
	 * completes the names of the files in the root that may be shaders
	 * once they are listed.
	 */
	public Syntax syntax(ProjectPath path) {
		if (lone != null || !ProjectManifest.FILE.equals(path)) {
			return ShaderLanguage.of(path.toString()).syntax();
		}
		ManifestSyntax syntax = new ManifestSyntax();
		worker.execute(() -> {
			List<String> names = new ArrayList<>();
			for (Item item : list(null).items()) {
				String name = item.path().toString();
				if (!item.folder() &&
						FileKind.hint(name, null) == FileKind.TEXT &&
						ShaderLanguage.of(name).runs()) {
					names.add(name);
				}
			}
			main.execute(() -> syntax.setFiles(names));
		});
		return syntax;
	}

	/**
	 * Return the changes the storage offers for the file or folder at
	 * path, the root when null; none when there is no such file.
	 */
	public ProjectFiles.Offers offers(ProjectPath path) {
		return store.offers(path);
	}

	/**
	 * Open path for the editor as a document of the text its journal
	 * kept, which starts unsaved, or of its stored text, unless it is
	 * in memory already. size is its size as the listing told it, or
	 * {@link ProjectFiles#UNKNOWN}. done gets null once
	 * {@link #document} has it, or why it cannot be edited. The
	 * document stays at least until done, and that of everyone else
	 * who asked for the file meanwhile, returned, whatever they call:
	 * {@link #show} it there, or it may leave memory again for the
	 * budget. A document in memory is opened as storage holds it now:
	 * it is looked at first, as when the app returns, so one changed
	 * elsewhere is read again, and one gone elsewhere without unsaved
	 * text is opened as any file that is gone, which it cannot be.
	 */
	public void load(ProjectPath path, long size, Callback<String> done) {
		FileRecord kept = loaded(path);
		if (kept != null) {
			kept.whenIdle(() -> check(kept, new HashSet<>(), () -> {
				// What was found gone leaves memory, unless shown.
				trim();
				opened(path, size, done);
			}));
			return;
		}
		opened(path, size, done);
	}

	private void opened(ProjectPath path, long size, Callback<String> done) {
		FileRecord present = loaded(path);
		if (present != null) {
			recent.remove(path);
			recent.add(path);
			++present.awaited;
			main.execute(() -> {
				--present.awaited;
				done.done(null);
				trim();
			});
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
	 * Tell done whether the file at path is a picture, by the signature
	 * its first bytes start with; its name plays no part. A file with a
	 * document or unsaved text here is text, whatever storage holds.
	 * The bytes are read off the queue saves wait on.
	 */
	/** Look at files on looks instead, as a test steps through it. */
	void looksOn(Executor looks) {
		this.looks = looks;
	}

	public void picture(ProjectPath path, Callback<Boolean> done) {
		if (loaded(path) != null || unsaved().contains(path)) {
			main.execute(() -> done.done(false));
			return;
		}
		looks.execute(() -> {
			byte[] head = store.head(path, FileKind.HEAD);
			boolean picture = head != null && FileKind.picture(head) != null;
			main.execute(() -> done.done(picture));
		});
	}

	/**
	 * Return the files of the project to look at off its queue, as
	 * {@link #list} does: they read only, and refuse every change. For
	 * what only looks and may take long, such as reading an image, so
	 * that it holds up no save.
	 */
	public ProjectFiles reading() {
		ProjectFiles files = store;
		return new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				return files.read(path);
			}

			@Override
			public Read read(ProjectPath path, long limit) {
				return files.read(path, limit);
			}

			@Override
			public byte[] head(ProjectPath path, int count) {
				return files.head(path, count);
			}

			@Override
			public android.graphics.Bitmap thumbnail(ProjectPath path,
					int size, android.os.CancellationSignal cancel) {
				return files.thumbnail(path, size, cancel);
			}
		};
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
	 * is not in memory. When another file was shown, everything typed
	 * goes to the journal now.
	 */
	public Document show(ProjectPath path) {
		if (!Objects.equals(path, shown)) {
			shown = path;
			persist();
		}
		if (loaded(path) != null) {
			use(path);
		}
		return document(path);
	}

	/**
	 * Return every file with unsaved text: the modified documents and
	 * the files not in memory that have a journal.
	 */
	public Set<ProjectPath> unsaved() {
		Set<ProjectPath> unsaved = new HashSet<>();
		for (FileRecord record : records.values()) {
			if (record.unsaved()) {
				unsaved.add(record.path);
			}
		}
		return unsaved;
	}

	/**
	 * Keep the text of every document, the file shown and the file
	 * chosen to run where they changed since, now instead of after
	 * {@link #FLUSH_DELAY}: a modified document in the journal, one
	 * without unsaved text by removing its journal. What the session
	 * could not keep is sent again by the next call.
	 */
	public void persist() {
		persist(false);
	}

	// At a fence, close and flush(done), everything is sent that is
	// not acknowledged, also what is on its way: the fence does not
	// depend on main having heard how an earlier write went.
	private void persist(boolean fence) {
		keepSelection(fence);
		for (FileRecord record : new ArrayList<>(records.values())) {
			flush(record, fence);
		}
	}

	/**
	 * Count the project as the one open last, opened now, for the list
	 * of recent projects and the next start. A failure is told by
	 * {@link #unkept} and sent again by the next {@link #persist}.
	 */
	public void remember() {
		opened = System.currentTimeMillis();
		keepSelection(false);
	}

	/**
	 * Return why the file shown, the file chosen to run or that this
	 * project is the one open last is not kept for the next start, or
	 * null when they are.
	 */
	public String unkept() {
		return unkept;
	}

	// Keep the file shown, the file chosen to run and that the project
	// is the one open last in the session: what differs from what it
	// keeps, each kept once the worker says it is. One write at a time,
	// except at a fence, which also writes what the write on its way
	// would make other than it is now.
	private void keepSelection(boolean fence) {
		if (sending != null && !fence) {
			return;
		}
		Selection sent = new Selection(shown, chosen, opened);
		Selection on = sending != null ? sending : kept;
		boolean showing = !Objects.equals(sent.shown(), kept.shown()) ||
				!Objects.equals(sent.shown(), on.shown());
		boolean choosing = !Objects.equals(sent.chosen(), kept.chosen()) ||
				!Objects.equals(sent.chosen(), on.chosen());
		boolean lasting = sent.opened() != kept.opened() ||
				sent.opened() != on.opened();
		if (!showing && !choosing && !lasting) {
			return;
		}
		sending = sent;
		worker.execute(() -> {
			boolean shownKept = !showing ||
					sessions.setShown(id, sent.shown());
			boolean chosenKept = !choosing ||
					sessions.setRunning(id, sent.chosen());
			// A project that is not listed has no time to keep.
			boolean lastKept = !lasting || sessions.setLast(id) &&
					(sessions.recent(id) == null ||
							sessions.touch(id, sent.opened()));
			main.execute(() -> {
				// The answer to the last one sent tells.
				if (sending != sent) {
					return;
				}
				sending = null;
				kept = new Selection(
						shownKept ? sent.shown() : kept.shown(),
						chosenKept ? sent.chosen() : kept.chosen(),
						lastKept ? sent.opened() : kept.opened());
				unkept = shownKept && chosenKept && lastKept
						? null
						: "the session cannot be written";
				changed();
			});
		});
	}

	/**
	 * {@link #persist} and call done once the journal acknowledged the
	 * text every document has now and it is durable, or
	 * {@link #unprotected} tells why not; call it when the app goes to
	 * the background.
	 */
	public void flush(Runnable done) {
		persist(true);
		keepHistories();
		texts.sync(synced -> {
			unsynced = synced ? null : "the journal cannot be synced";
			changed();
			done.run();
		});
	}

	/**
	 * Return why unsaved text is not kept in the journal, or null when
	 * all of it that was written is. Also when the journals could not
	 * all be read when they were looked at: then a file may have
	 * unsaved text that nothing shows, until {@link #refresh} could
	 * read them.
	 */
	public String unprotected() {
		for (FileRecord record : records.values()) {
			if (record.failure() != null) {
				return "the unsaved text of " + record.path +
						" is not protected: " + record.failure();
			}
		}
		return unsynced != null ? unsynced : unscanned
				? "unsaved text may be missing: the journal cannot be " +
						"read now"
				: null;
	}

	/**
	 * Return whether the journal of path is, as the worker acknowledged,
	 * as it should be now: holding exactly the unsaved text the file
	 * has, or gone when it has none. False while typing waits for its
	 * flush, while a write is on its way, and after one failed.
	 */
	public boolean settled(ProjectPath path) {
		FileRecord record = records.get(path);
		return record == null || record.settled();
	}

	/**
	 * Save every file with unsaved text where it is, and mark each
	 * document saved as it was when its text was taken. done gets the
	 * outcome by path. A file changed elsewhere is saved only when
	 * explicit and the user chose to keep their edits. A text no file
	 * can hold, so that opening the file would give another text, as
	 * one with half of a surrogate pair, fails with the place of the
	 * character and stays unsaved; nothing is written for it. What is
	 * done with each file is decided at the moment its save can start,
	 * after whatever else worked on it. A file
	 * without unsaved text is never written, with one exception: when
	 * explicit, the file shown is looked at in storage first, and when
	 * it is gone it is written again, which done is told as
	 * {@link Created}; when it changed, its document takes the stored
	 * text ({@link Reread}); when it cannot be read, that fails. Every
	 * other file without unsaved text is left as storage has it, gone
	 * or renamed too: no save brings it back.
	 */
	public void save(boolean explicit, Callback<Map<ProjectPath, Outcome>> done) {
		persist();
		Map<ProjectPath, Outcome> outcomes = new LinkedHashMap<>();
		int[] left = {1};
		Runnable finished = () -> {
			if (--left[0] == 0) {
				trim();
				changed();
				done.done(outcomes);
			}
		};
		for (FileRecord record : new ArrayList<>(records.values())) {
			ProjectPath path = record.path;
			++left[0];
			// What a save does with a file is decided when it can
			// start, from what the file is then: not when it was asked
			// for behind another operation, which may have found the
			// file changed, or behind an undo.
			record.whenIdle(() -> save(record, explicit, false, outcome -> {
				if (outcome != null) {
					outcomes.put(path, outcome);
				}
				finished.run();
			}));
		}
		afterWorker(finished);
	}

	/**
	 * Return why each file with unsaved text changed elsewhere, for the
	 * files the user has not decided about yet.
	 */
	public Map<ProjectPath, String> conflicts() {
		Map<ProjectPath, String> conflicts = new HashMap<>();
		for (FileRecord record : records.values()) {
			String conflict = record.conflict();
			if (conflict != null) {
				conflicts.put(record.path, conflict);
			}
		}
		return conflicts;
	}

	/**
	 * Return why the unsaved text of path changed elsewhere, as
	 * {@link #conflicts} has it, or null; what was found in its place
	 * is {@link #found}.
	 */
	public String conflict(ProjectPath path) {
		FileRecord record = records.get(path);
		return record != null ? record.conflict() : null;
	}

	/**
	 * Return the files with unsaved text that no listing of storage can
	 * be counted on to show: storage was last found not to hold them
	 * (deleted or renamed elsewhere, or under a folder that was), or
	 * could not be asked at all (the provider is gone, its permission
	 * was taken back, or the folder opened was renamed elsewhere). It
	 * comes from what was found of each file, not from a listing.
	 */
	public Set<ProjectPath> missing() {
		Set<ProjectPath> missing = new HashSet<>();
		for (FileRecord record : records.values()) {
			if (record.edited() &&
					(record.found == FileTexts.Found.MISSING ||
							record.found == FileTexts.Found.UNAVAILABLE)) {
				missing.add(record.path);
			}
		}
		return missing;
	}

	/**
	 * Look for changes made elsewhere: keep the unsaved text, forget
	 * what listings told, and see what storage holds of every file in
	 * memory or with unsaved text: unsaved text storage does not hold
	 * the base of anymore is in conflict, at the revision seen now;
	 * unsaved text storage holds exactly is saved; a document without
	 * unsaved text gets the stored text when it changed, as one change
	 * that can be undone. Then find what runs. A build prepared before
	 * is not of this storage anymore. done gets the files read again.
	 */
	public void refresh(Callback<Set<ProjectPath>> done) {
		persist();
		++generation;
		worker.execute(store::forgetListings);
		Set<ProjectPath> reread = new HashSet<>();
		// What runs is found once every check was taken.
		int[] left = {1};
		Runnable checked = () -> {
			if (--left[0] == 0) {
				++generation;
				// What was found gone leaves memory.
				trim();
				updateRunning(() -> done.done(reread));
			}
		};
		for (FileRecord record : new ArrayList<>(records.values())) {
			++left[0];
			record.whenIdle(() -> check(record, reread, checked));
		}
		if (unscanned) {
			// The journals could not all be read before: looked at
			// again, for the files nothing is known of yet.
			++left[0];
			texts.journals(new HashSet<>(records.keySet()), journals -> {
				take(journals);
				changed();
				checked.run();
			});
		}
		afterWorker(checked);
	}

	/**
	 * Read path again from storage into its document, as one change that
	 * can be undone, and drop its unsaved text once the document took
	 * the stored text; text typed after the call is kept instead, also
	 * when the read waits for a save or a check of the file. Unsaved
	 * text not in memory is opened first, so undo brings it back; it is
	 * replaced as the journal had it, so what anyone typed once it was
	 * in memory stays too. Unsaved text is written to the journal
	 * first, and that journal is put aside before the stored text is
	 * taken: it is kept for as long as undo or redo lead to the text,
	 * also when the process ends, and the file has no journal from
	 * then on, so what is typed afterwards is kept like any unsaved
	 * text. When the journal cannot hold the text first, nothing is
	 * replaced. done gets null when done, or why the text is as it
	 * was; what was found in the file's place is seen.
	 */
	public void reload(ProjectPath path, Callback<String> done) {
		reload(path, false, null, done);
	}

	private void reload(ProjectPath path, boolean expect, String seen,
			Callback<String> done) {
		FileRecord record = record(path);
		if (record.document() == null && record.edited()) {
			// The user consents to losing the unsaved text as the
			// journal has it: what is typed once it is in memory, also
			// by whoever asked for it before, stays.
			load(path, ProjectFiles.UNKNOWN, problem -> {
				if (problem != null) {
					done.done(problem);
					return;
				}
				Document asked = record.document();
				int at = record.opened();
				record.whenIdle(() -> reload(record, asked, at, expect,
						seen, done));
			});
			return;
		}
		// The user consents to losing the text the document has now:
		// what is typed from here on stays, however long the read waits.
		Document asked = record.document();
		int at = asked != null ? asked.revision() : -1;
		record.whenIdle(() -> reload(record, asked, at, expect, seen,
				done));
	}

	/**
	 * Resolve the conflict of path, as the user saw it at revision seen,
	 * see {@link #seen}: reload the stored text, or keep the unsaved
	 * text so the next explicit save overwrites that revision. Nothing
	 * happens when the file is not in conflict at seen anymore, or when
	 * what storage holds in its place is no text: a picture, or a file
	 * too large, is neither reloaded nor written over by consent.
	 */
	public void resolve(ProjectPath path, Choice choice, String seen) {
		resolve(path, choice, seen, problem -> {
		});
	}

	/**
	 * The same; done gets why a reload changed nothing, else null.
	 */
	public void resolve(ProjectPath path, Choice choice, String seen,
			Callback<String> done) {
		FileRecord record = records.get(path);
		// Only what the user can have seen and can decide: a text at
		// the revision named, or no file. Never a revision of what is
		// no text, which nothing showed them.
		if (record == null || !record.decides() ||
				!Objects.equals(seen, record.seen)) {
			done.done(null);
			return;
		} else if (choice == Choice.RELOAD) {
			reload(path, true, seen, done);
			return;
		}
		done.done(null);
		record.consent(seen);
		if (record.document() != null) {
			flush(record);
		} else {
			texts.header(new Journal.Header(path, seen, true, seen, false,
					record.lineage()), kept -> {
						record.headerKept(kept);
						changed();
					});
		}
		changed();
	}

	/**
	 * Return why the unsaved text of path could not be read when it was
	 * opened, or null. The file then counts among those with unsaved
	 * text, its document has the stored text, and the journal that
	 * could not be read stays until {@link #dismiss}. When the text is
	 * edited, that journal is put aside as it is before the text typed
	 * is kept under its name: undo leads to it once the file is opened
	 * again and it can be read.
	 */
	public String unreadable(ProjectPath path) {
		FileRecord record = records.get(path);
		return record != null ? record.unreadable() : null;
	}

	/**
	 * Return whether the unsaved text of path that could not be read,
	 * see {@link #unreadable}, is damaged, so that reading it again
	 * finds the same and {@link #dismiss} gives it up. Else the device
	 * had no memory to read it: it may be whole, and is only ever read
	 * again, by {@link #reread}.
	 */
	public boolean damaged(ProjectPath path) {
		FileRecord record = records.get(path);
		return record != null && record.damaged();
	}

	/**
	 * Read the unsaved text of path that could not be read once more:
	 * its document, which has the stored text and no unsaved text of
	 * its own, leaves memory and the file is opened again. done gets
	 * what {@link #load} tells; nothing happens to a document that was
	 * edited since.
	 */
	public void reread(ProjectPath path, Callback<String> done) {
		FileRecord record = records.get(path);
		if (record == null || record.unreadable() == null ||
				record.edited() || record.pending() != null) {
			main.execute(() -> done.done(unreadable(path)));
			return;
		}
		if (record.document() != null) {
			drop(record);
		}
		load(path, ProjectFiles.UNKNOWN, done);
	}

	/**
	 * Give up the unsaved text of path that could not be read: its
	 * journal is deleted when it is damaged, and the file no longer
	 * counts as unsaved for it. One that only could not be read stays,
	 * and so does one that was put aside by text typed since.
	 */
	public void dismiss(ProjectPath path) {
		FileRecord record = records.get(path);
		if (record == null || !record.damaged()) {
			return;
		}
		texts.dismiss(path, removed -> {
			if (removed) {
				record.dismissed();
				if (!record.needed() && records.get(path) == record) {
					records.remove(path);
				}
			}
			changed();
		});
		texts.damaged(count -> {
			damaged = count;
			changed();
		});
	}

	/**
	 * Return how many journals of the session have a header that cannot
	 * be read, so their unsaved text is lost and the file it was of is
	 * not known: each stays until its file is opened, which then tells
	 * {@link #unreadable}, and dismissed.
	 */
	public int damagedJournals() {
		return damaged;
	}

	/**
	 * Return the revision storage was last seen to hold of path while
	 * it is in conflict, null when the file was absent or path is not
	 * in conflict. The listener hears when it moves.
	 */
	public String seen(ProjectPath path) {
		FileRecord record = records.get(path);
		return record != null && record.conflict() != null
				? record.seen
				: null;
	}

	/**
	 * Return what the last check found in storage for path while its
	 * document is in memory, else null. A document without unsaved text
	 * whose file is not there or not readable as text keeps its text,
	 * but what runs is made of the file as stored.
	 */
	public FileTexts.Found found(ProjectPath path) {
		FileRecord record = loaded(path);
		return record != null ? record.found : null;
	}

	/**
	 * Write the current text of each of paths, as its document, else its
	 * journal, else storage has it, to the same path in destination,
	 * where it must not exist yet; nothing of this project changes. A
	 * stored file larger than a document may be is not read, and a text
	 * no file can hold, see {@link #save}, is not written as another. done gets
	 * by path null when it was written, else why not. A copy asked for
	 * is made and answered also when the project closes meanwhile; one
	 * asked of a closed project is answered with why it was not made.
	 */
	public void copyTo(ProjectFiles destination, Collection<ProjectPath> paths,
			Callback<Map<ProjectPath, String>> done) {
		if (closed) {
			Map<ProjectPath, String> problems = new LinkedHashMap<>();
			for (ProjectPath path : paths) {
				problems.put(path, "the folder was closed");
			}
			anyMain.execute(() -> done.done(problems));
			return;
		}
		Map<ProjectPath, Text> texts = new HashMap<>();
		for (ProjectPath path : paths) {
			FileRecord record = loaded(path);
			if (record != null) {
				texts.put(path, record.document().text());
			}
		}
		List<ProjectPath> copied = new ArrayList<>(paths);
		ProjectFiles current = new Edited(Map.of(), journaled(), true);
		anyWorker.execute(() -> {
			Map<ProjectPath, String> problems = new LinkedHashMap<>();
			for (ProjectPath path : copied) {
				Text text = texts.get(path);
				Read read = text == null ? current.read(path) : null;
				String unstorable = text != null
						? FileTexts.unstorable(text)
						: null;
				ProjectFiles.Change change;
				if (unstorable != null) {
					problems.put(path, unstorable);
					continue;
				} else if (text != null) {
					change = destination.write(path,
							out -> TextBytes.write(text, out), null);
				} else if (read instanceof Content content) {
					change = destination.write(path, content.bytes(), null);
				} else {
					problems.put(path, CapturedFiles.problem(path, read));
					continue;
				}
				problems.put(path, change instanceof ProjectFiles.Refused
						refused ? refused.reason() : null);
			}
			anyMain.execute(() -> done.done(problems));
		});
	}

	/**
	 * Rename the file or folder at from to to, which must not exist, in
	 * storage and for the project, whether the project holds files
	 * there or not. Every file it holds at or under from keeps its
	 * document, with unsaved text, undo and selection, under the new
	 * path; its journal and undo history follow, and so do the file
	 * shown and the file chosen to run. A save, a reload or a check of
	 * such a file that is under way is finished first. Storage is not
	 * read: a file that changed elsewhere is renamed as it is, and
	 * looked at afterwards as {@link #refresh} looks at it. done gets
	 * {@link Renamed} or {@link Refused}; everyone else learns of it
	 * through the listener and {@link #events}.
	 */
	public void rename(ProjectPath from, ProjectPath to,
			Callback<Moved> done) {
		Journal.Move move = new Journal.Move(from, to);
		Refused refused = refusal(move);
		if (refused != null) {
			main.execute(() -> done.done(refused));
			return;
		}
		// What is typed is with the journal that is about to follow.
		persist();
		Map<FileRecord, FileRecord.Request> held = new LinkedHashMap<>();
		hold(move, held, () -> {
			Refused late = refusal(move);
			if (late != null) {
				release(held, null);
				done.done(late);
				return;
			}
			texts.move(move, change -> {
				if (change instanceof ProjectFiles.Refused failed) {
					release(held, failed.refusal());
					done.done(new Refused(failed.refusal(),
							failed.reason()));
				} else {
					renamed(move, held, done);
				}
			});
		});
	}

	/**
	 * Delete the file or folder at path, in storage and for the
	 * project, whether the project holds files there or not. A save, a
	 * reload or a check of a held file that is under way is finished
	 * first. Unsaved text is not deleted without being asked for: when a
	 * file at or under path has some that discard does not name as it
	 * is now, nothing is deleted and done gets {@link Unsaved}; show
	 * those files to the user and pass its texts as discard to delete
	 * them. Then the journals and undo histories go with the stored
	 * files, in the same task, and the documents are closed; one that
	 * was typed in after the call stays, as unsaved text of a file that
	 * is not there. A file that is gone already is deleted for the
	 * project all the same. done gets {@link Deleted}, {@link Unsaved}
	 * or {@link Refused}; everyone else learns of a deletion through
	 * the listener and {@link #events}.
	 */
	public void delete(ProjectPath path, Map<ProjectPath, Integer> discard,
			Callback<Moved> done) {
		Journal.Move move = new Journal.Move(path, null);
		Refused refused = refusal(move);
		if (refused != null) {
			main.execute(() -> done.done(refused));
			return;
		}
		persist();
		Map<FileRecord, FileRecord.Request> held = new LinkedHashMap<>();
		hold(move, held, () -> {
			Map<ProjectPath, Integer> unsaved = new LinkedHashMap<>();
			boolean asked = true;
			for (FileRecord record : held.keySet()) {
				if (record.unsaved()) {
					unsaved.put(record.path, record.stamp());
					asked &= Integer.valueOf(record.stamp()).equals(
							discard.get(record.path));
				}
			}
			if (!asked) {
				release(held, null);
				done.done(new Unsaved(unsaved));
				return;
			}
			// From here on none of this text is written again.
			for (FileRecord record : held.keySet()) {
				record.discard(true);
			}
			texts.move(move, change -> {
				if (change instanceof ProjectFiles.Refused failed) {
					for (FileRecord record : held.keySet()) {
						record.discard(false);
					}
					release(held, null);
					done.done(new Refused(failed.refusal(),
							failed.reason()));
				} else {
					deleted(move, held, done);
				}
			});
		});
	}

	// Storage made the deletion and the journals went with it: the
	// files held are closed, but for those typed in since.
	private void deleted(Journal.Move move,
			Map<FileRecord, FileRecord.Request> held,
			Callback<Moved> done) {
		for (Map.Entry<FileRecord, FileRecord.Request> entry :
				held.entrySet()) {
			FileRecord record = entry.getKey();
			if (record.discarded()) {
				Document document = record.document();
				if (document != null) {
					document.removeListener(record.listener);
					record.drop();
				}
				record.deleted();
				records.remove(record.path);
				recent.remove(record.path);
				// What waited for it finds no file.
				record.end(entry.getValue());
			} else {
				record.orphan();
				end(record, entry.getValue());
				flush(record);
			}
		}
		// The session forgets them; a document that stayed is shown.
		if (shown != null && move.touches(shown)) {
			kept = new Selection(null, kept.chosen(), kept.opened());
			shown = loaded(shown) != null ? shown : null;
		}
		if (chosen != null && move.touches(chosen)) {
			kept = new Selection(kept.shown(), null, kept.opened());
			chosen = null;
		}
		++generation;
		worker.execute(() -> follow(sessions, id, move));
		Deleted deleted = new Deleted(move.from());
		event(deleted);
		keepSelection(false);
		changed();
		trim();
		updateRunning(() -> done.done(deleted));
	}

	/**
	 * Return what was renamed or deleted after the event numbered
	 * after, 0 for all that is remembered, oldest first; the listener
	 * hears of each. Only the last few are remembered: who finds a gap
	 * in the numbers looks at everything again.
	 */
	public List<Event> events(int after) {
		List<Event> since = new ArrayList<>();
		for (Event event : events) {
			if (event.number() > after) {
				since.add(event);
			}
		}
		return since;
	}

	// Return why move cannot be made, as far as the project knows, or
	// null.
	private Refused refusal(Journal.Move move) {
		ProjectPath to = move.to();
		if (lone != null) {
			return new Refused(ProjectFiles.Refusal.UNSUPPORTED,
					"the project is this one file");
		} else if (to == null) {
			return null;
		} else if (move.touches(to)) {
			return new Refused(ProjectFiles.Refusal.FAILED,
					"a file cannot move to where it is");
		}
		Journal.Move target = new Journal.Move(to, null);
		for (FileRecord record : records.values()) {
			if (target.touches(record.path)) {
				return new Refused(ProjectFiles.Refusal.EXISTS,
						record.path + " is open or has unsaved text");
			}
		}
		return null;
	}

	// Start an operation of every file the project holds at or under
	// the source of move, each once nothing else works on it, also of
	// those it comes to hold while waiting; then run then.
	private void hold(Journal.Move move,
			Map<FileRecord, FileRecord.Request> held, Runnable then) {
		for (FileRecord record : new ArrayList<>(records.values())) {
			if (move.touches(record.path) && !held.containsKey(record)) {
				record.whenIdle(() -> {
					if (records.get(record.path) == record) {
						held.put(record, record.start(
								FileRecord.Kind.MOVE));
					}
					hold(move, held, then);
				});
				return;
			}
		}
		then.run();
	}

	// End the operations of held; when storage had nothing at the path,
	// that is what was seen of every file there.
	private void release(Map<FileRecord, FileRecord.Request> held,
			ProjectFiles.Refusal refusal) {
		for (Map.Entry<FileRecord, FileRecord.Request> entry :
				held.entrySet()) {
			if (refusal == ProjectFiles.Refusal.ABSENT) {
				entry.getKey().observe(entry.getValue(),
						FileTexts.Found.MISSING, null);
			}
			end(entry.getKey(), entry.getValue());
		}
		changed();
	}

	// Storage made move: the files held go by their new paths.
	private void renamed(Journal.Move move,
			Map<FileRecord, FileRecord.Request> held,
			Callback<Moved> done) {
		for (FileRecord record : held.keySet()) {
			records.remove(record.path);
			record.path = move.moved(record.path);
			records.put(record.path, record);
		}
		List<ProjectPath> used = new ArrayList<>(recent);
		recent.clear();
		for (ProjectPath path : used) {
			recent.add(move.moved(path));
		}
		for (ProjectPath path : new ArrayList<>(opening.keySet())) {
			if (move.touches(path)) {
				opening.put(move.moved(path), opening.remove(path));
			}
		}
		if (shown != null) {
			shown = move.moved(shown);
		}
		// The session follows; the file shown is sent again all the
		// same.
		if (chosen != null) {
			if (chosen.equals(kept.chosen())) {
				kept = new Selection(kept.shown(), move.moved(chosen),
						kept.opened());
			}
			chosen = move.moved(chosen);
		}
		if (running != null && move.touches(running)) {
			running = move.moved(running);
			++choice;
		}
		List<Used> built = new ArrayList<>();
		for (Used file : this.used) {
			FileRole role = file.role();
			if (role != null) {
				List<ProjectPath> of = new ArrayList<>();
				for (ProjectPath to : role.of()) {
					ProjectPath moved = move.moved(to);
					if (moved != null) {
						of.add(moved);
					}
				}
				role = new FileRole(role.kind(), of);
			}
			built.add(new Used(move.moved(file.path()), file.image(), role));
		}
		this.used = Collections.unmodifiableList(built);
		List<HostDirectives.Reference> named = new ArrayList<>();
		for (HostDirectives.Reference reference : references) {
			ProjectPath path = move.moved(reference.path());
			ProjectPath file = move.moved(reference.file());
			if (path != null && file != null) {
				named.add(new HostDirectives.Reference(path, file,
						reference.line()));
			}
		}
		references = Collections.unmodifiableList(named);
		usedFor = usedFor != null ? move.moved(usedFor) : null;
		// What was built is of the old paths.
		++generation;
		// The journals and histories follow, behind what was written
		// to them meanwhile, then what the session shows and runs.
		texts.follow(move);
		worker.execute(() -> follow(sessions, id, move));
		Renamed renamed = new Renamed(move.from(), move.to());
		event(renamed);
		for (Map.Entry<FileRecord, FileRecord.Request> entry :
				held.entrySet()) {
			FileRecord record = entry.getKey();
			end(record, entry.getValue());
			// Renamed as it was: what it is, is seen now.
			if (records.get(record.path) == record) {
				record.whenIdle(() -> check(record, new HashSet<>(),
						() -> {
						}));
			}
		}
		keepSelection(false);
		changed();
		updateRunning(() -> done.done(renamed));
	}

	private void event(Moved change) {
		events.add(new Event(++moves, change));
		if (events.size() > 32) {
			events.remove(0);
		}
	}

	/** Return the file that runs, or null when nothing runs. */
	public ProjectPath running() {
		return running;
	}

	/**
	 * Run path from now on, also after reopening, until another file is
	 * chosen; the project's files stay as they are. Everything typed
	 * goes to the journal now.
	 */
	public void runThisFile(ProjectPath path) {
		running = chosen = path;
		++choice;
		++generation;
		changed();
		persist();
	}

	/**
	 * Prepare what runs from the text of the documents and the journal,
	 * which gets everything typed now, reading no file beyond the
	 * largest text a document may have,
	 * and keep the files it is made of as those of the last build. done
	 * gets the preparation, or null when nothing runs.
	 */
	public void prepare(Builtins builtins, Callback<Build> done) {
		prepare(builtins, null, done);
	}

	/**
	 * Prepare what runs as {@link #prepare(Builtins, Callback)} does,
	 * with replacement in the text of its document, when not null. Such
	 * a preparation is {@link Preparation#temporary()}: it runs while a
	 * number is tuned, and is never kept, as the wallpaper is. When path
	 * has no document a build would read, done gets nothing to run.
	 */
	public void prepare(Builtins builtins, Replacement replacement,
			Callback<Build> done) {
		Map<ProjectPath, Text> snapshot = snapshot();
		int generation = this.generation;
		if (replacement != null) {
			Text text = snapshot.get(replacement.path());
			if (text == null) {
				main.execute(() -> done.done(new Build(null,
						List.of(new ProjectDiagnostic(
								ProjectDiagnostic.Severity.ERROR,
								"not open in the editor",
								replacement.path(), 0)),
						generation)));
				return;
			}
			snapshot.put(replacement.path(), text.replace(
					replacement.start(), replacement.end(),
					Text.of(replacement.text())));
		}
		persist();
		ProjectPath entry = running;
		Edited edited = new Edited(snapshot, journaled());
		boolean temporary = replacement != null;
		ProjectKnowledge known = knowledge;
		worker.execute(() -> {
			Preparation prepared = entry != null
					? Preparation.prepare(edited, entry,
							ProjectLimits.DEFAULT, builtins)
					: null;
			Preparation preparation = prepared != null && temporary
					? prepared.asTemporary()
					: prepared;
			List<ProjectDiagnostic> nothingRuns = entry == null
					? Preparation.checkManifest(edited)
					: List.of();
			List<Used> read = new ArrayList<>();
			Map<ProjectPath, byte[]> texts = new LinkedHashMap<>();
			if (preparation != null) {
				for (SourceAssembly.Dependency dependency :
						preparation.uses()) {
					ProjectPath path = dependency.path();
					ProjectFiles.Content content =
							preparation.files().get(path);
					if (dependency.revision() != null && content != null) {
						// By its bytes, as the build told it.
						boolean image = FileKind.picture(
								content.bytes()) != null;
						read.add(new Used(path, image,
								preparation.roles().get(path)));
						if (!image) {
							texts.put(path, content.bytes());
						}
					}
				}
			}
			// The texts the build read, as their languages read them;
			// only a file that changed since the last build is read. A
			// build for tuning has one number replaced and declares
			// what the build before it declared: nothing is read for
			// it, and what is known stays.
			ProjectKnowledge built = temporary
					? null
					: ProjectKnowledge.of(texts, known);
			List<HostDirectives.Reference> named = preparation != null
					? preparation.references()
					: List.of();
			main.execute(() -> {
				// Not delivered when the text or what runs changed.
				if (generation != this.generation) {
					return;
				}
				if (built != null) {
					knowledge = built;
				}
				used = Collections.unmodifiableList(read);
				references = named;
				usedFor = entry;
				changed();
				done.done(new Build(preparation, nothingRuns, generation));
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

	/**
	 * The texts of the folder's GLSL files as the editor has them, by
	 * path, the files each names as its language reads them, and how
	 * many files or folders could not be read.
	 */
	public record FolderTexts(Map<ProjectPath, Text> texts,
			Map<ProjectPath, List<ProjectPath>> names, int unread) {
	}

	/**
	 * Tell done the text of every file of the folder and its folders
	 * that is read as GLSL: its document's, else its journal's, else
	 * the stored one, as a build reads it. Pictures are left out. Read
	 * on the worker.
	 */
	public void folderTexts(Callback<FolderTexts> done) {
		Map<ProjectPath, Text> snapshot = snapshot();
		Set<ProjectPath> journaled = journaled();
		Edited files = new Edited(snapshot, journaled);
		worker.execute(() -> {
			Map<ProjectPath, Text> texts = new LinkedHashMap<>();
			Map<ProjectPath, List<ProjectPath>> names = new HashMap<>();
			List<ProjectPath> paths = new ArrayList<>();
			int unread = lone != null ? 0 : collect(null, paths);
			if (lone != null) {
				paths.add(lone);
			}
			for (ProjectPath path : paths) {
				if (!snapshot.containsKey(path) &&
						!journaled.contains(path)) {
					byte[] head = store.head(path, FileKind.HEAD);
					if (head != null && FileKind.picture(head) != null) {
						continue;
					}
				}
				String text = files.read(path) instanceof Content content
						? SourceText.decode(content.bytes())
						: null;
				if (text != null) {
					Text read = Text.of(text);
					texts.put(path, read);
					names.put(path, ShaderLanguage.of(path.toString())
							.named(path, read));
				} else {
					++unread;
				}
			}
			int count = unread;
			main.execute(() -> done.done(new FolderTexts(texts, names,
					count)));
		});
	}

	// Adds the GLSL files under folder, the root for null; returns how
	// many folders could not be listed.
	private int collect(ProjectPath folder, List<ProjectPath> paths) {
		Folder listed = list(folder);
		int unlisted = listed.problem() != null ? 1 : 0;
		for (Item item : listed.items()) {
			if (item.folder()) {
				unlisted += collect(item.path(), paths);
			} else if (ShaderLanguage.of(item.path().toString()) ==
					ShaderLanguage.GLSL) {
				paths.add(item.path());
			}
		}
		return unlisted;
	}

	/**
	 * Return what the last build of what runs knows of the text of path,
	 * or null where it did not read it; see {@link ProjectKnowledge#of}.
	 */
	public Knowledge knowledge(ProjectPath path) {
		return knowledge.of(path);
	}

	/**
	 * Return what the screen keeps of its way back while this project
	 * is open, or null: the screen made again after a configuration
	 * change takes it. Nothing here reads it.
	 */
	public Object wayBack() {
		return wayBack;
	}

	/** Keep way as the way back, see {@link #wayBack}. */
	public void setWayBack(Object way) {
		wayBack = way;
	}

	/**
	 * Return what the file at path sees of the other files of the last
	 * build of what runs: the names the files it includes declare, and
	 * what a word refers to there. It answers for the last build at the
	 * moment it is asked, on the thread the project is used on; a file
	 * the build did not read sees nothing. A file that is renamed, or
	 * moved with a folder above it, stays the file that is asked about.
	 */
	public Knowledge.Scope scope(ProjectPath path) {
		return new Knowledge.Scope() {
			private ProjectPath at = path;
			private int heard = moves;

			// Where the file is now.
			private ProjectPath path() {
				if (heard != moves) {
					for (Event event : events) {
						if (event.number() > heard && event.change()
								instanceof Renamed renamed) {
							ProjectPath moved = new Journal.Move(
									renamed.from(), renamed.to()).moved(at);
							at = moved != null ? moved : at;
						}
					}
					heard = moves;
				}
				return at;
			}

			@Override
			public List<String> names() {
				return knowledge.names(path());
			}

			@Override
			public List<Knowledge.Symbol> find(CharSequence word) {
				return knowledge.find(path(), word);
			}

			@Override
			public List<Knowledge.Symbol> all(CharSequence word) {
				return knowledge.all(path(), word);
			}

			@Override
			public int line(Knowledge.Symbol symbol) {
				return knowledge.line(symbol);
			}
		};
	}

	/**
	 * Return whether the build of what runs now is made of path; false
	 * while what runs changed and was not built yet, as the last build
	 * is of what ran before.
	 */
	public boolean reads(ProjectPath path) {
		return running != null && running.equals(usedFor) && uses(path);
	}

	/**
	 * Return the lines of the files of the last build of what runs that
	 * name path, in the order it read them; none when it read no line
	 * that names it.
	 */
	public List<HostDirectives.Reference> references(ProjectPath path) {
		List<HostDirectives.Reference> naming = new ArrayList<>();
		for (HostDirectives.Reference reference : references) {
			if (reference.path().equals(path)) {
				naming.add(reference);
			}
		}
		return naming;
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
	 * Return whether path, open in the editor, can run: a text file
	 * whose own text defines what its language starts at. Until that is
	 * known for its text, the answer for its text before, or UNKNOWN
	 * before the first, and the text is read on the worker, once per
	 * revision; the listener hears when the answer changed.
	 */
	public Runs runs(ProjectPath path) {
		FileRecord record = loaded(path);
		if (record == null) {
			return Runs.NO;
		}
		Document document = record.document();
		int revision = document.revision();
		if (record.runsRevision != revision &&
				record.runsAsked != revision) {
			record.runsAsked = revision;
			Text text = document.text();
			worker.execute(() -> {
				Runs runs = ShaderLanguage.of(path.toString())
						.runnable(text) ? Runs.YES : Runs.NO;
				main.execute(() -> {
					if (record.document() == document &&
							document.revision() == revision) {
						record.runs = runs;
						record.runsRevision = revision;
						changed();
					}
				});
			});
		}
		return record.runs;
	}

	/** Keep the diagnostics of the last build of what runs. */
	public void setDiagnostics(Build build,
			List<ProjectDiagnostic> diagnostics) {
		if (build.generation != generation) {
			return;
		}
		this.diagnostics = List.copyOf(diagnostics);
		diagnosed = generation;
		changed();
	}

	/**
	 * Return a number for the texts and what runs as they are now; it
	 * changes with every change of them.
	 */
	public int generation() {
		return generation;
	}

	/**
	 * Return whether the diagnostics are those of a build of the texts
	 * as they are now.
	 */
	public boolean diagnosedNow() {
		return diagnosed == generation;
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
		if (!record.needed() && records.get(record.path) == record) {
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
			// A journal that cannot be read is left as it is until it
			// is dismissed or the next snapshot replaces it; that is
			// told also when the stored file cannot be opened in its
			// place.
			if (records.get(path) == record &&
					opened.unreadable() != null) {
				record.unread(opened.unreadable(), opened.damaged());
			}
			List<Callback<String>> joined = opening.remove(path);
			end(record, request);
			changed();
			// The document stays until everyone who asked for it was
			// told, whatever they do meanwhile.
			FileRecord told = loaded(path);
			if (told != null) {
				++told.awaited;
			}
			if (joined != null) {
				for (Callback<String> done : joined) {
					done.done(problem);
				}
			}
			if (told != null) {
				--told.awaited;
			}
			// Only now: a callback may have shown the document.
			trim();
		});
	}

	// Give record a document of opened, unless it has one; return
	// whether it took it.
	private boolean take(FileRecord record, FileRecord.Request request,
			FileTexts.Opened opened) {
		Document document = opened.document();
		if (records.get(record.path) != record ||
				!record.adoptOpened(request, document, opened.revision(),
						opened.journal(), opened.id(), opened.noted(),
						opened.replaced())) {
			return false;
		}
		record.historyAt = document.revision();
		record.historySelection = document.selection();
		record.listener = new Document.Listener() {
			private int revision = document.revision();

			@Override
			public void onChange(Document.Change change, Text before) {
				record.collect(change);
			}

			@Override
			public void onUpdate() {
				if (document.revision() != revision) {
					revision = document.revision();
					++generation;
					// Clean again, it is dropped first; not while its
					// listeners are called.
					if (!document.isModified()) {
						main.execute(OpenProject.this::trim);
					}
				}
				changed();
				schedule(record);
			}
		};
		document.addListener(record.listener);
		recent.remove(record.path);
		recent.add(record.path);
		return true;
	}

	// Write the text the document of record or its journal has now,
	// or, to create, the text of a document without unsaved text whose
	// file is gone; done gets the save.
	private void write(FileRecord record, boolean create,
			Callback<FileTexts.Save> done) {
		// A file made again expects none there; any other the revision
		// its text is based on. Which of the two was decided by the
		// caller just now, never from what the file looks like later.
		String expected = create ? null : record.base();
		FileRecord.Request request = record.start(FileRecord.Kind.SAVE);
		ProjectPath path = record.path;
		Document document = request.document();
		texts.save(path, document != null ? document.text() : null,
				request.state(), expected, save -> {
					if (records.get(path) != record) {
						// Closed since.
					} else if (save.outcome() instanceof Saved saved) {
						record.adoptSaved(request, saved.revision());
					} else if (save.outcome() instanceof Unreadable damage) {
						record.unread(damage.reason(), damage.damaged());
					} else if (save.found() != null) {
						record.observe(request, save.found().found(),
								save.found().revision());
					}
					end(record, request);
					flush(record);
					done.done(save);
				});
	}

	// Do with record what a save does with it as it is now: nothing
	// else works on it, and nothing is decided before. looked tells
	// that storage was just looked at for the file shown; done gets the
	// outcome, null when there was nothing to save.
	private void save(FileRecord record, boolean explicit, boolean looked,
			Callback<Outcome> done) {
		ProjectPath path = record.path;
		if (records.get(path) != record) {
			done.done(null);
		} else if (!record.edited() && record.unreadable() != null) {
			done.done(new Unreadable(record.unreadable(),
					record.damaged()));
		} else if (!record.edited()) {
			// Without unsaved text only the file shown is written, on
			// the user's word, and only where it was just seen gone.
			if (!explicit || record.document() == null ||
					!path.equals(shown)) {
				done.done(null);
			} else if (!looked) {
				Set<ProjectPath> reread = new HashSet<>();
				check(record, reread, () -> {
					if (reread.contains(path)) {
						done.done(new Reread());
					} else {
						record.whenIdle(() -> save(record, true, true,
								done));
					}
				});
			} else if (record.found == FileTexts.Found.MISSING) {
				write(record, true, save -> done.done(
						save.outcome() instanceof Saved saved
								? new Created(saved.revision())
								: save.outcome()));
			} else if (record.found != FileTexts.Found.SAME) {
				done.done(new Failed("it cannot be read"));
			} else {
				done.done(null);
			}
		} else if (record.conflict() != null) {
			done.done(new Conflict(record.conflict()));
		} else if (!explicit && record.kept()) {
			done.done(new Conflict("the file changed elsewhere"));
		} else {
			write(record, false, save -> done.done(save.outcome()));
		}
	}

	// See what storage holds of record: give its document the stored
	// text when it changed and the document has no unsaved text and is
	// not edited before it arrives; mark unsaved text storage holds
	// exactly as saved; else keep what was seen.
	private void check(FileRecord record, Set<ProjectPath> reread,
			Runnable then) {
		FileRecord.Request request = record.start(FileRecord.Kind.CHECK);
		ProjectPath path = record.path;
		boolean unsaved = record.edited();
		Callback<FileTexts.Checked> adopt = checked -> {
			if (records.get(path) != record) {
				// Closed since.
			} else if (checked.equal()) {
				record.adoptSaved(request, checked.revision());
			} else if (checked.text() != null && record.adoptStored(
					request, request.document(), request.revision(),
					checked.text(), checked.revision(), -1, -1)) {
				reread.add(path);
			} else {
				record.observe(request, checked.found(),
						checked.revision());
			}
			end(record, request);
			flush(record);
			changed();
			then.run();
		};
		if (unsaved) {
			texts.compare(path, request.base(), request.document() != null
					? request.document().text()
					: null, adopt);
		} else {
			texts.reread(path, request.base(), adopt);
		}
	}

	// Reload record when its document is still asked at revision at;
	// when expect, only if storage is still at seen.
	private void reload(FileRecord record, Document asked, int at,
			boolean expect, String seen, Callback<String> done) {
		FileRecord.Request request = record.start(FileRecord.Kind.RELOAD);
		ProjectPath path = record.path;
		// Unsaved text is held before it is replaced: its journal is
		// written now, ahead of the reload on the worker, which puts
		// it aside only if it holds exactly this write.
		Document document = record.document();
		boolean typed = document != null && document == asked &&
				document.revision() == at && document.isModified();
		if (typed) {
			flush(record);
		}
		int held = typed ? record.holding() : -1;
		texts.reload(path, held, expect, seen, reloaded -> {
			FileTexts.Opening opening = reloaded.opening();
			boolean took = false;
			String problem = null;
			if (records.get(path) != record) {
				// Closed since.
			} else if (opening instanceof FileTexts.Unopened unopened) {
				// What stands in place of the text is seen all the same.
				problem = unopened.reason();
				record.observe(request, unopened.found(),
						unopened.revision());
			} else if (!(opening instanceof FileTexts.Opened opened)) {
				problem = "it cannot be read";
			} else if (expect && !Objects.equals(seen, opened.revision())) {
				problem = "the file changed again";
			} else if (typed && reloaded.aside() < 0) {
				problem = "its unsaved text cannot be kept first";
			} else {
				// Typing that came after the request stays, with its
				// journal.
				took = asked == null && record.document() == null &&
						!record.edited()
						? take(record, request, opened)
						: record.adoptStored(request, asked, at,
								opened.text(), opened.revision(),
								reloaded.aside(), held);
			}
			// What was read and not taken is what storage was seen to
			// hold.
			if (!took && opening instanceof FileTexts.Opened opened) {
				record.observe(request, FileTexts.Found.CHANGED,
						opened.revision());
			}
			if (!took && reloaded.aside() >= 0 &&
					records.get(path) == record) {
				// Not replaced after all: the journal is put back.
				record.putBack();
				texts.putBack(path, reloaded.aside());
			}
			end(record, request);
			flush(record);
			if (took) {
				keepHistory(record);
			}
			changed();
			done.done(problem);
			trim();
		});
	}

	// Count path as opened or shown last and drop the documents beyond
	// the budget.
	private void use(ProjectPath path) {
		recent.remove(path);
		recent.add(path);
		trim();
	}

	// Drop documents while those in memory besides the one shown take
	// more than the budget, the one used least recently first. One with
	// an operation in flight waits for its outcome, one the journal does
	// not hold as it is yet for the journal, one a caller is about to be
	// told of for that call. A document without unsaved text whose file
	// is gone from storage goes whatever the budget, once it is not
	// shown: it is no file of the folder anymore, and nothing writes it
	// back.
	private void trim() {
		List<ProjectPath> paths = new ArrayList<>(recent);
		for (ProjectPath path : paths) {
			FileRecord record = loaded(path);
			if (record != null && !path.equals(shown) &&
					record.found == FileTexts.Found.MISSING &&
					!record.unsaved() && record.awaited == 0 &&
					record.pending() == null && record.settled()) {
				drop(record);
			}
		}
		long used = 0;
		for (ProjectPath path : paths) {
			FileRecord record = loaded(path);
			if (record != null && !path.equals(shown)) {
				used += cost(record.document());
			}
		}
		for (ProjectPath path : paths) {
			FileRecord record = loaded(path);
			if (used <= budget) {
				break;
			} else if (record == null || path.equals(shown) ||
					record.awaited > 0 || record.pending() != null ||
					!record.settled()) {
				continue;
			}
			used -= cost(record.document());
			drop(record);
		}
	}

	// Drop the document of record; a file with unsaved text keeps its
	// record, which opens it again from the journal.
	private void drop(FileRecord record) {
		keepHistory(record);
		recent.remove(record.path);
		record.state = null;
		record.runs = Runs.UNKNOWN;
		record.runsRevision = record.runsAsked = -1;
		record.drop().removeListener(record.listener);
		if (!record.needed()) {
			records.remove(record.path);
		}
	}

	// Keep the history of every document that changed since it was kept.
	private void keepHistories() {
		for (FileRecord record : records.values()) {
			keepHistory(record);
		}
	}

	// Keep the undo history of the document of record, with what it
	// selects, when either changed since it was kept, so it is there
	// when the file is opened again, also by another process.
	private void keepHistory(FileRecord record) {
		Document document = record.document();
		if (document == null ||
				document.revision() == record.historyAt &&
						document.selection().equals(
								record.historySelection)) {
			return;
		}
		record.historyAt = document.revision();
		record.historySelection = document.selection();
		if (document.historyChars() > Histories.MAX_CHARS) {
			texts.forgetHistory(record.path);
		} else {
			texts.keepHistory(record.path, document.text(),
					document.history());
		}
	}

	/**
	 * Return about how many bytes document takes in memory: two for each
	 * character of its text and of its undo history, one for each line.
	 */
	static long cost(Document document) {
		Text text = document.text();
		return 2L * text.length() + text.lineCount() +
				2L * document.historyChars();
	}

	// Flush record after the delay without a change, and at the latest
	// after the limit since the first one not flushed. A key posts no
	// timer: one waits for the delay at a time, and posts the next when
	// a change came meanwhile.
	private void schedule(FileRecord record) {
		int timer = ++record.timer;
		if (record.limit == 0) {
			int limit = record.limit = timer;
			later.post(() -> {
				if (record.limit == limit) {
					flush(record);
				}
			}, FLUSH_LIMIT);
			delay(record, limit, timer);
		}
	}

	private void delay(FileRecord record, int limit, int timer) {
		later.post(() -> {
			if (record.limit != limit) {
				return;
			} else if (record.timer == timer) {
				flush(record);
			} else {
				delay(record, limit, record.timer);
			}
		}, FLUSH_DELAY);
	}

	// Send what changed in the document of record since it was last
	// sent to the journal, or the removal of the journal once it has no
	// unsaved text. It counts as kept once the journal took it.
	private void flush(FileRecord record) {
		flush(record, false);
	}

	// The same; at a fence also what a write on its way was to do,
	// whose answer may never be heard.
	private void flush(FileRecord record, boolean fence) {
		++record.timer;
		record.limit = 0;
		if (records.get(record.path) != record) {
			return;
		}
		// What a reload replaced is kept while undo or redo lead to it.
		for (int number : record.unreachable()) {
			texts.forget(record.path, number);
		}
		FileRecord.Flush flush = record.flush(fence);
		if (flush == null) {
			return;
		}
		texts.journal(record.path, flush, written -> {
			record.acknowledge(flush, written);
			if (!record.needed() && records.get(record.path) == record) {
				records.remove(record.path);
			}
			trim();
			changed();
		});
	}

	// Run then on main once the worker did what it was given so far.
	private void afterWorker(Runnable then) {
		worker.execute(() -> main.execute(() -> {
			changed();
			then.run();
		}));
	}

	// Return the files not in memory whose unsaved text is in the
	// journal, also where it could not be read.
	private Set<ProjectPath> journaled() {
		Set<ProjectPath> journaled = new HashSet<>();
		for (FileRecord record : records.values()) {
			if (record.journaled()) {
				journaled.add(record.path);
			}
		}
		return journaled;
	}

	private Map<ProjectPath, Text> snapshot() {
		Map<ProjectPath, Text> snapshot = new HashMap<>();
		for (FileRecord record : records.values()) {
			Document document = record.document();
			// A file gone from storage is not built from what was shown.
			if (document != null && (document.isModified() ||
					record.found == FileTexts.Found.SAME ||
					record.found == FileTexts.Found.CHANGED)) {
				snapshot.put(record.path, document.text());
			}
		}
		return snapshot;
	}

	// Find what runs from the text now; a choice made meanwhile wins,
	// text changed meanwhile asks again.
	private void updateRunning(Runnable then) {
		int choice = this.choice;
		int generation = this.generation;
		Map<ProjectPath, Text> snapshot = snapshot();
		Set<ProjectPath> journaled = journaled();
		worker.execute(() -> {
			ProjectPath found = findRunning(snapshot, journaled);
			main.execute(() -> {
				if (choice != this.choice) {
					then.run();
					return;
				} else if (generation != this.generation) {
					updateRunning(then);
					return;
				}
				if (!Objects.equals(found, running)) {
					running = found;
					++this.choice;
					++this.generation;
				}
				changed();
				then.run();
			});
		});
	}

	// Find what runs by the one rule: the file last chosen with Run this
	// file if it still exists, else the entry of a valid manifest, else
	// main.frag when present, else nothing. It asks only whether a file
	// exists, never what is in it. On the worker.
	private ProjectPath findRunning(Map<ProjectPath, Text> snapshot,
			Set<ProjectPath> journaled) {
		if (lone != null) {
			return lone;
		}
		ProjectFiles files = new Edited(snapshot, journaled);
		long limit = Document.MAX_BYTES;
		ProjectPath found = sessions.running(id);
		if (found == null ||
				!(files.read(found, limit) instanceof Content)) {
			found = null;
			ProjectParseResult<ProjectManifest> manifest = null;
			if (files.read(ProjectManifest.FILE, limit) instanceof
					Content content) {
				String text = SourceText.decode(content.bytes());
				manifest = text != null ? ProjectManifest.parse(text) : null;
			}
			if (manifest != null && manifest.isSuccess()) {
				found = manifest.value().entry();
			} else if (files.read(MAIN, limit) instanceof Content) {
				found = MAIN;
			}
		}
		return found;
	}

	private State state() {
		Map<ProjectPath, FileTexts.Found> found = new HashMap<>();
		for (FileRecord record : records.values()) {
			if (record.document() != null &&
					record.found != FileTexts.Found.SAME) {
				found.put(record.path, record.found);
			}
		}
		Map<ProjectPath, Runs> runs = new HashMap<>();
		Map<ProjectPath, String> unreadable = new HashMap<>();
		for (FileRecord record : records.values()) {
			// A key does not change what is known until it is known.
			if (record.document() != null && record.runs != Runs.UNKNOWN) {
				runs.put(record.path, record.runs);
			}
			if (record.unreadable() != null) {
				unreadable.put(record.path, record.unreadable());
			}
		}
		Map<ProjectPath, String> conflicts = new HashMap<>();
		for (ProjectPath path : conflicts().keySet()) {
			conflicts.put(path, seen(path));
		}
		return new State(unsaved(), conflicts, running, used,
				diagnostics, unprotected(), unkept, found, runs, unreadable,
				damaged, moves);
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

	private static int compareNames(String a, String b) {
		int order = a.compareToIgnoreCase(b);
		return order != 0 ? order : a.compareTo(b);
	}

	// The project as the editor had it when the snapshot was taken:
	// files in memory with the text of their documents, the journaled
	// ones with the text of their journal, the others as stored, none
	// read beyond the largest text a document may have. Read on the
	// worker.
	private final class Edited implements ProjectFiles {
		private final Map<ProjectPath, Text> snapshot;
		private final Set<ProjectPath> journaled;
		// Whether a text no file can hold fails instead of being read
		// as the bytes written for it.
		private final boolean exact;

		private Edited(Map<ProjectPath, Text> snapshot,
				Set<ProjectPath> journaled) {
			this(snapshot, journaled, false);
		}

		private Edited(Map<ProjectPath, Text> snapshot,
				Set<ProjectPath> journaled, boolean exact) {
			this.snapshot = snapshot;
			this.journaled = journaled;
			this.exact = exact;
		}

		@Override
		public Read read(ProjectPath path) {
			return read(path, Document.MAX_BYTES);
		}

		@Override
		public Read read(ProjectPath path, long limit) {
			Text text = snapshot.get(path);
			if (text == null && journaled.contains(path)) {
				Journal.Recovered recovered = journal.read(path);
				// Not the stored text in its place: that is older than
				// what the file is marked unsaved for.
				if (recovered != null && recovered.text() == null) {
					return new Failed(recovered.problem());
				}
				text = recovered != null ? recovered.text() : null;
			}
			if (text == null) {
				return store.read(path, limit);
			}
			String unstorable = exact ? FileTexts.unstorable(text) : null;
			if (unstorable != null) {
				return new Failed(unstorable);
			}
			byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
			if (bytes.length > limit) {
				return new Failed(TOO_LARGE);
			}
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
