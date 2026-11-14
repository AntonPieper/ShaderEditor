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

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Read;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

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
		private final int generation;

		private Build(Preparation preparation, int generation) {
			this.preparation = preparation;
			this.generation = generation;
		}

		/** Return the preparation, or null when nothing runs. */
		public Preparation preparation() {
			return preparation;
		}
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
	 * The unsaved text of the file cannot be read, for reason. Nothing
	 * was written, and the journal stays until {@link #dismiss}.
	 */
	public record Unreadable(String reason) implements Outcome {
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
	 * A file the last build of what runs is made of, and whether it is
	 * an image.
	 */
	public record Used(ProjectPath path, boolean image) {
	}

	// What the listener hears about.
	private record State(Set<ProjectPath> unsaved,
			Map<ProjectPath, String> conflicts,
			ProjectPath running, List<Used> used,
			List<ProjectDiagnostic> diagnostics, String unprotected,
			String unkept,
			Map<ProjectPath, FileTexts.Found> found,
			Map<ProjectPath, Runs> runs, Map<ProjectPath, String> unreadable,
			Set<ProjectPath> replaced, int damaged, int moves) {
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
	private final Later later;
	// The only file of a project of one document, else null.
	private final ProjectPath lone;
	private final Storage storage;
	private final long budget;
	private Runnable release;
	// Why the journal could not be made durable, or null.
	private String unsynced;
	private List<ProjectDiagnostic> diagnostics = List.of();
	private List<Used> used = List.of();
	private ProjectPath running;
	private ProjectPath shown;
	// The file shown as the session keeps it; the file chosen to run
	// and whether the session is to keep it yet; whether the worker is
	// keeping them, and why it could not, or null.
	private ProjectPath keptShown;
	private ProjectPath chosen;
	private boolean choose;
	private boolean keeping;
	private String unkept;
	// Whether the session is to keep yet that the project is the one
	// open last, opened at this time; what the write on its way holds
	// besides the file shown; and how many were sent.
	private boolean last;
	private long opened;
	private boolean keepingChoice;
	private boolean keepingLast;
	private int selections;
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
		journal.removeLeftovers();
		// A rename or deletion the process died in is finished or
		// dropped before the journals are looked at.
		follow(sessions, id, project.texts.recover());
		project.histories.trim();
		Journal.Scan journals = journal.scan();
		project.damaged = journals.damaged();
		for (Journal.Header header : journals.headers().values()) {
			FileTexts.Checked checked = project.texts.check(header.path(),
					header.base(), null, true);
			if (checked.equal()) {
				// Storage holds the text: saved before the process died,
				// or written elsewhere.
				journal.remove(header.path());
			} else {
				project.record(header.path()).recover(header,
						checked.found(), checked.revision());
			}
		}
		project.shown = project.keptShown = sessions.shown(id);
		project.running = project.findRunning(Map.of(),
				project.journaled());
		return project;
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
	 * budget.
	 */
	public void load(ProjectPath path, long size, Callback<String> done) {
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
		last = true;
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
	// is the one open last in the session; each counts as kept once the
	// worker says it is. One write at a time, except at a fence, which
	// sends what is on its way again as it is now.
	private void keepSelection(boolean fence) {
		if (keeping && !fence) {
			return;
		}
		ProjectPath show = shown;
		ProjectPath run = chosen;
		long time = opened;
		boolean showing = keeping || !Objects.equals(show, keptShown);
		boolean choosing = choose || keeping && keepingChoice;
		boolean lasting = last || keeping && keepingLast;
		if (!showing && !choosing && !lasting) {
			return;
		}
		keeping = true;
		keepingChoice = choosing;
		keepingLast = lasting;
		choose = last = false;
		int sent = ++selections;
		worker.execute(() -> {
			boolean shownKept = !showing || sessions.setShown(id, show);
			boolean chosenKept = !choosing ||
					sessions.setRunning(id, run);
			// A project that is not listed has no time to keep.
			boolean lastKept = !lasting || sessions.setLast(id) &&
					(sessions.recent(id) == null ||
							sessions.touch(id, time));
			main.execute(() -> {
				// The answer to the last one sent tells.
				if (sent != selections) {
					return;
				}
				keeping = false;
				if (shownKept) {
					keptShown = show;
				}
				choose |= !chosenKept;
				last |= !lastKept;
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
	 * all of it that was written is.
	 */
	public String unprotected() {
		for (FileRecord record : records.values()) {
			if (record.failure != null) {
				return "the unsaved text of " + record.path +
						" is not protected: " + record.failure;
			}
		}
		return unsynced;
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
	 * character and stays unsaved; nothing is written for it. When explicit, a
	 * document without unsaved text whose file was seen to be gone is
	 * written too, creating the file again unless one is there.
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
			if (!record.edited() && record.unreadable != null) {
				outcomes.put(path, new Unreadable(record.unreadable));
				continue;
			} else if (!record.edited() && !(explicit &&
					record.document() != null &&
					record.found == FileTexts.Found.MISSING)) {
				continue;
			} else if (record.conflict() != null) {
				outcomes.put(path, new Conflict(record.conflict()));
				continue;
			} else if (!explicit && record.kept) {
				outcomes.put(path, new Conflict("the file changed elsewhere"));
				continue;
			}
			outcomes.put(path, null);
			++left[0];
			record.whenIdle(() -> save(record, save -> {
				if (save.outcome() != null) {
					outcomes.put(path, save.outcome());
				} else {
					outcomes.remove(path);
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
				updateRunning(() -> done.done(reread));
			}
		};
		for (FileRecord record : new ArrayList<>(records.values())) {
			++left[0];
			record.whenIdle(() -> check(record, reread, checked));
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
	 * in memory stays too. The journal of the replaced text is removed
	 * only together with a history that holds it; while none can be
	 * written, the journal stays, and the text comes back as unsaved
	 * when the project opens again. done gets null when done, or why
	 * the file cannot be read; what was found in its place is seen.
	 */
	public void reload(ProjectPath path, Callback<String> done) {
		reload(path, false, null, done);
	}

	private void reload(ProjectPath path, boolean expect, String seen,
			Callback<String> done) {
		FileRecord record = record(path);
		if (record.document() == null && record.journaled) {
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
	 * happens when the file is not in conflict at seen anymore.
	 */
	public void resolve(ProjectPath path, Choice choice, String seen) {
		FileRecord record = records.get(path);
		if (record == null || record.conflict() == null ||
				!Objects.equals(seen, record.seen)) {
			return;
		} else if (choice == Choice.RELOAD) {
			reload(path, true, seen, problem -> {
			});
			return;
		}
		record.consent(seen);
		if (record.document() != null) {
			flush(record);
		} else {
			texts.header(new Journal.Header(path, seen, true, seen),
					kept -> {
						record.failure = kept
								? null
								: "the journal cannot be written";
						changed();
					});
		}
		changed();
	}

	/**
	 * Return why the unsaved text of path could not be read when it was
	 * opened, or null. The file then counts among those with unsaved
	 * text, its document has the stored text, and the journal that
	 * could not be read stays until {@link #dismiss} or until the text
	 * is edited and kept again.
	 */
	public String unreadable(ProjectPath path) {
		FileRecord record = records.get(path);
		return record != null ? record.unreadable : null;
	}

	/**
	 * Give up the unsaved text of path that could not be read: its
	 * journal is deleted, unless text typed since took its place, and
	 * the file no longer counts as unsaved for it.
	 */
	public void dismiss(ProjectPath path) {
		FileRecord record = records.get(path);
		if (record == null || record.unreadable == null) {
			return;
		}
		texts.dismiss(path, removed -> {
			if (removed && record.unreadable != null) {
				record.unreadable = null;
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
	 * Return whether the unsaved text of path is text a reload replaced
	 * with the stored one, which came back: the journal that held it is
	 * kept until an undo history holds it, and the app ended, or no
	 * history could be stored, before. It stays so until the text is
	 * edited, saved, reloaded again or kept by {@link #keepReplaced}.
	 */
	public boolean replaced(ProjectPath path) {
		FileRecord record = records.get(path);
		// As the journal gave it back: an edit makes it the user's.
		return record != null && record.replaced() && record.edited() &&
				(record.document() == null ||
						record.document().revision() == record.opened());
	}

	/**
	 * Keep the text of path that a reload replaced, see
	 * {@link #replaced}, as unsaved text like any other.
	 */
	public void keepReplaced(ProjectPath path) {
		FileRecord record = records.get(path);
		if (record == null || !replaced(path)) {
			return;
		}
		record.keepReplaced();
		if (record.document() != null) {
			flush(record);
		} else {
			String base = record.base();
			texts.header(new Journal.Header(path, base, record.kept,
					record.kept ? base : null), kept -> {
				record.failure = kept
						? null
						: "the journal cannot be written";
				changed();
			});
		}
		changed();
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
	 * by path null when it was written, else why not.
	 */
	public void copyTo(ProjectFiles destination, Collection<ProjectPath> paths,
			Callback<Map<ProjectPath, String>> done) {
		Map<ProjectPath, Text> texts = new HashMap<>();
		for (ProjectPath path : paths) {
			FileRecord record = loaded(path);
			if (record != null) {
				texts.put(path, record.document().text());
			}
		}
		List<ProjectPath> copied = new ArrayList<>(paths);
		ProjectFiles current = new Edited(Map.of(), journaled(), true);
		worker.execute(() -> {
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
			main.execute(() -> done.done(problems));
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
				record.discard(false);
				record.journaled = false;
				record.unreadable = null;
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
		if (shown != null && move.touches(shown)) {
			// The session forgets it; a document that stayed is shown.
			keptShown = null;
			shown = loaded(shown) != null ? shown : null;
		}
		if (chosen != null && move.touches(chosen)) {
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
		if (chosen != null) {
			chosen = move.moved(chosen);
		}
		if (running != null && move.touches(running)) {
			running = move.moved(running);
			++choice;
		}
		List<Used> built = new ArrayList<>();
		for (Used file : this.used) {
			built.add(new Used(move.moved(file.path()), file.image()));
		}
		this.used = Collections.unmodifiableList(built);
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
		choose = true;
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
		persist();
		int generation = this.generation;
		ProjectPath entry = running;
		Edited edited = new Edited(snapshot(), journaled());
		worker.execute(() -> {
			Preparation preparation = entry != null
					? Preparation.prepare(edited, entry,
							ProjectLimits.DEFAULT, builtins)
					: null;
			List<Used> read = new ArrayList<>();
			if (preparation != null) {
				for (SourceAssembly.Dependency dependency :
						preparation.uses()) {
					ProjectPath path = dependency.path();
					ProjectFiles.Content content =
							preparation.files().get(path);
					if (dependency.revision() != null && content != null) {
						// By its bytes, as the build told it.
						read.add(new Used(path, FileKind.picture(
								content.bytes()) != null));
					}
				}
			}
			main.execute(() -> {
				// Not delivered when the text or what runs changed.
				if (generation != this.generation) {
					return;
				}
				used = Collections.unmodifiableList(read);
				changed();
				done.done(new Build(preparation, generation));
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
	 * Return whether path, open in the editor, can run: a text file
	 * whose own text defines what its language starts at. Until that is
	 * known for its text,
	 * UNKNOWN, and the text is read on the worker, once per revision;
	 * the listener hears when it is known.
	 */
	public Runs runs(ProjectPath path) {
		FileRecord record = loaded(path);
		if (record == null) {
			return Runs.NO;
		}
		Document document = record.document();
		int revision = document.revision();
		if (record.runsRevision == revision) {
			return record.runs;
		} else if (record.runsAsked != revision) {
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
		return Runs.UNKNOWN;
	}

	/** Keep the diagnostics of the last build of what runs. */
	public void setDiagnostics(Build build,
			List<ProjectDiagnostic> diagnostics) {
		if (build.generation != generation) {
			return;
		}
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
		if (!record.needed() && records.get(record.path) == record) {
			records.remove(record.path);
		}
	}

	private void open(FileRecord record, long size) {
		FileRecord.Request request = record.start(FileRecord.Kind.OPEN);
		ProjectPath path = record.path;
		texts.open(path, size, opened -> {
			String problem = null;
			boolean taken = false;
			if (opened instanceof FileTexts.Unopened unopened) {
				problem = unopened.reason();
			} else {
				taken = take(record, request, (FileTexts.Opened) opened) &&
						((FileTexts.Opened) opened).journal() < 0;
			}
			// The journal is gone, or cannot be read and is left as it
			// is until it is dismissed or the next snapshot replaces
			// it; that is told also when the stored file cannot be
			// opened in its place.
			if (records.get(path) == record && (taken ||
					opened.unreadable() != null)) {
				record.journaled = false;
				record.unreadable = opened.unreadable();
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
						opened.journal(), opened.replaced())) {
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

	// Save the text the document of record or its journal has now;
	// done gets the save, or null when there was nothing to save.
	private void save(FileRecord record, Callback<FileTexts.Save> done) {
		String expected = record.expected();
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
						record.journaled = false;
						record.unreadable = damage.reason();
					} else if (save.found() != null) {
						record.observe(request, save.found().found(),
								save.found().revision());
					}
					end(record, request);
					flush(record);
					done.done(save);
				});
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
					checked.text(), checked.revision())) {
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
		// A journal of text that would be replaced, or that waits for a
		// history since an earlier reload, is marked before main is told.
		boolean mark = record.document() != null
				? record.document().isModified() || record.replaced()
				: record.journaled;
		texts.reload(path, mark, expect, seen, reloaded -> {
			FileTexts.Opening opening = reloaded.opening();
			boolean took = false;
			String problem = null;
			if (opening instanceof FileTexts.Unopened unopened) {
				// What stands in place of the text is seen all the same.
				problem = unopened.reason();
				record.observe(request, unopened.found(),
						unopened.revision());
			} else if (!(opening instanceof FileTexts.Opened opened)) {
				problem = "it cannot be read";
			} else if (records.get(path) != record) {
				// Closed since.
			} else if (expect && !Objects.equals(seen, opened.revision())) {
				problem = "the file changed again";
			} else {
				// Typing that came after the request stays, with its
				// journal.
				took = asked == null && record.document() == null &&
						!record.journaled
						? take(record, request, opened)
						: record.adoptStored(request, asked, at,
								opened.text(), opened.revision());
			}
			// What was read and not taken is what storage was seen to
			// hold.
			if (!took && opening instanceof FileTexts.Opened opened) {
				record.observe(request, FileTexts.Found.CHANGED,
						opened.revision());
			}
			end(record, request);
			// The journal goes only now that the stored text is taken,
			// and only by the task of the worker that wrote the history
			// which brings its text back by undo.
			if (took) {
				record.kept = false;
				flush(record);
				keepHistory(record);
			} else if (reloaded.marked() && records.get(path) == record &&
					record.edited()) {
				// Not replaced after all: its journal says so again.
				record.keepReplaced();
				flush(record);
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
	// told of for that call.
	private void trim() {
		List<ProjectPath> paths = new ArrayList<>(recent);
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

	// Flush record after the delay since its last change, and at the
	// latest after the limit since the first one not flushed.
	private void schedule(FileRecord record) {
		int timer = ++record.timer;
		later.post(() -> {
			if (record.timer == timer) {
				flush(record);
			}
		}, FLUSH_DELAY);
		if (record.limit == 0) {
			int limit = record.limit = timer;
			later.post(() -> {
				if (record.limit == limit) {
					flush(record);
				}
			}, FLUSH_LIMIT);
		}
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
		FileRecord.Flush flush = records.get(record.path) == record
				? record.flush(fence)
				: null;
		if (flush == null) {
			return;
		} else if (flush.history() != null) {
			record.historyAt = record.document().revision();
			record.historySelection = record.document().selection();
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
			if (record.document() == null && (record.journaled ||
					record.unreadable != null)) {
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
			if (record.document() != null &&
					record.runsRevision == record.document().revision()) {
				runs.put(record.path, record.runs);
			}
			if (record.unreadable != null) {
				unreadable.put(record.path, record.unreadable);
			}
		}
		Set<ProjectPath> replaced = new HashSet<>();
		for (ProjectPath path : records.keySet()) {
			if (replaced(path)) {
				replaced.add(path);
			}
		}
		Map<ProjectPath, String> conflicts = new HashMap<>();
		for (ProjectPath path : conflicts().keySet()) {
			conflicts.put(path, seen(path));
		}
		return new State(unsaved(), conflicts, running, used,
				diagnostics, unprotected(), unkept, found, runs, unreadable,
				replaced, damaged, moves);
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
