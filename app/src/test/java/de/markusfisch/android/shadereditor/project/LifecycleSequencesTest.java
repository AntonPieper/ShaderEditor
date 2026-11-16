package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/**
 * Every order of a few steps a user, the timers, the two threads and a
 * failing disk can take with one file, each followed by the death of
 * the process. What opens afterwards is the newest text the user typed
 * that the project told was held, or one they had since; or, where
 * they chose to reload, the stored text; and every text a reload
 * replaced that undo or redo of the document led to when the process
 * died is led to by undo or redo of the document that opens, however
 * many writes of the journal lie between. And nothing typed stays out
 * of the journal: once the timers fired and both threads are idle, the
 * journal holds the text, unless a write failed.
 *
 * The files of the journal are those of a {@link FaultyDisk}: in the
 * sequences that say so every single operation on them fails once, and
 * the process dies after every one; in the others one of them does, by
 * a fixed seed; and at every end the files cannot be read for one
 * opening. Whatever fails, the same holds: what cannot be written or
 * read now is kept, told and tried again, and nothing is deleted for it.
 */
public class LifecycleSequencesTest {
	private enum Step {
		/** The user replaces the text. */
		TYPE,
		/** The user takes back the last change. */
		UNDO,
		/** The timers that flush what was typed fire. */
		TIMER,
		/** The worker does what it was given. */
		WORKER,
		/**
		 * The same, while nothing can be written: neither journal nor
		 * history, or the history alone, see {@link Start}.
		 */
		FAIL,
		/** The main thread takes what was posted to it. */
		MAIN,
		/**
		 * The user reloads the file from storage; where it changed
		 * elsewhere, as they saw it then.
		 */
		RELOAD,
		/**
		 * The user keeps their edits over the file another app changed,
		 * as they saw it then.
		 */
		KEEP,
		/** The user saves. */
		SAVE,
		/** The app saves by itself, as on leaving or switching files. */
		AUTOSAVE,
		/** The app goes to the background: flush(done). */
		BACKGROUND,
		/** The project is closed. */
		CLOSE,
		/** The user renames the file, to the other of two names. */
		RENAME,
		/** The user deletes the file, with the text it has now. */
		DELETE,
		/** The process dies and the project is opened again. */
		REOPEN,
		/** Both threads run until neither has anything left to do. */
		SETTLE,
		/** The app looks at storage, as when it returns to the front. */
		REFRESH,
		/** Another app deletes the file. */
		GONE_OUTSIDE
	}

	// Where the sequences start, how many steps one has at most, and how
	// many sequences that makes; steps that would change nothing where
	// they stand are left out. The number changes when a step becomes
	// possible or impossible somewhere: then say why, and set it again.
	private enum Start {
		/**
		 * A saved file; FAIL fails journal and history. Without RELOAD,
		 * which the other starts take further in fewer steps.
		 */
		SAVED(5, 3379),
		/** The same; what is typed has a character no file can hold. */
		UNSTORABLE(4, 1336),
		/** Unsaved text the journal holds; FAIL fails the history. */
		HELD(4, 1929),
		/**
		 * The same, replaced by a reload whose history could not be
		 * written: only what was kept beside the journal leads back.
		 * FAIL fails the history.
		 */
		RELOADED(4, 3668),
		/**
		 * The same, and the user typed on and the journal took it: one
		 * write after the reload is done. FAIL fails journal and
		 * history, as in the starts below.
		 */
		TYPED_ON(4, 2948),
		/**
		 * The same, typed on twice: two writes after the reload, and a
		 * third within three steps.
		 */
		TYPED_TWICE(3, 359),
		/**
		 * Reloaded as RELOADED, then undone past the text the reload
		 * replaced: what is typed now leaves it behind.
		 */
		UNDONE(4, 2547),
		/**
		 * A file and unsaved text too large for a history to keep both,
		 * reloaded. Without the steps that fail or repeat what the
		 * other starts cover, a step less deep: each text is megabytes.
		 */
		LARGE(3, 65),
		/**
		 * Unsaved text the journal holds, with RENAME and DELETE in
		 * place of reload, save and flush(done); FAIL fails journal and
		 * history.
		 */
		MOVED(4, 926),
		/**
		 * A saved file, shown, that another app deleted while the app
		 * was in front, so no check saw it; FAIL fails journal and
		 * history. A save that tells of no failure leaves a file.
		 */
		GONE(4, 485),
		/**
		 * Unsaved text the journal holds, while another app changed the
		 * stored file and a refresh saw it; FAIL fails journal and
		 * history.
		 */
		CHANGED(4, 3469),
		/**
		 * The same, where the user chose to keep their edits, the
		 * process died, and the other app wrote again before anything
		 * was saved. With AUTOSAVE, the save nobody asked for: it never
		 * uses the consent, and no save writes over the second text.
		 */
		KEPT_THEN_CHANGED(4, 3989),
		/**
		 * Unsaved text the journal holds, replaced by a reload, typed
		 * on, held and replaced by a second reload, and then edited
		 * until undo leads to the second text only and the first is
		 * forgotten. With RENAME: what is kept of a file follows it,
		 * as the same text, whatever is reloaded afterwards.
		 */
		TWO_RELOADS(5, 452, Step.RENAME, Step.TYPE, Step.RELOAD,
				Step.SETTLE),
		/**
		 * Unsaved text the journal holds, with a look at storage, the
		 * save nobody asked for, undo and another app deleting the
		 * file, in every order: what is decided of a save is decided
		 * when it runs, so a file without unsaved text that is gone is
		 * never made again.
		 */
		QUEUED(5, 717, Step.REFRESH, Step.AUTOSAVE, Step.UNDO,
				Step.GONE_OUTSIDE, Step.SETTLE),
		/**
		 * Unsaved text the journal holds; every operation on a file of
		 * the journal fails once, and the process dies after each.
		 */
		EVERY_FAULT_HELD(3, 195, Step.TYPE, Step.UNDO, Step.RELOAD,
				Step.RENAME, Step.BACKGROUND, Step.SETTLE, Step.REOPEN),
		/** The same, after a reload replaced that text. */
		EVERY_FAULT_RELOADED(3, 211, Step.TYPE, Step.UNDO, Step.RELOAD,
				Step.RENAME, Step.BACKGROUND, Step.SETTLE, Step.REOPEN),
		/**
		 * The same as the first, with a rename and a deletion taken
		 * apart: the worker by itself, so the process dies between what
		 * storage did and what the journal did, and what is typed
		 * meanwhile.
		 */
		EVERY_FAULT_MOVED(3, 71, Step.TYPE, Step.RENAME, Step.DELETE,
				Step.WORKER, Step.SETTLE, Step.REOPEN);

		private final int length;
		private final int sequences;
		// The steps of the start, null for those the start is told by.
		private final Set<Step> steps;

		Start(int length, int sequences, Step... steps) {
			this.length = length;
			this.sequences = sequences;
			this.steps = steps.length > 0
					? java.util.EnumSet.copyOf(List.of(steps))
					: null;
		}

		// Whether the start is past a reload that replaced unsaved text.
		private boolean reloaded() {
			return this == RELOADED || this == TYPED_ON ||
					this == TYPED_TWICE || this == UNDONE || this == LARGE ||
					this == EVERY_FAULT_RELOADED || this == TWO_RELOADS;
		}

		// Whether every operation on a file fails once in each sequence.
		private boolean everyFault() {
			return this == EVERY_FAULT_HELD ||
					this == EVERY_FAULT_RELOADED || this == EVERY_FAULT_MOVED;
		}

		// Whether the file may get another name.
		private boolean moves() {
			return this == MOVED ||
					steps != null && steps.contains(Step.RENAME);
		}
	}

	// What chooses the one operation that fails, or after which the
	// process dies, in the sequences where not every one does; and
	// every how many sequences one is chosen.
	private static final long SEED = 20261116L;
	private static final int SAMPLED = 4;

	private static final ProjectPath B = ProjectPath.parse("b.glsl").value();
	private static final ProjectPath A = ProjectPath.parse("a.glsl").value();

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File sessionsDir;
	private File drafts;
	private File history;
	private String id;
	private int sequences;
	// The sequences run again with a failing operation or a death.
	private int injected;
	private final FaultyDisk disk = new FaultyDisk();
	// What did not hold, by what was told of it, with the first
	// sequence it was told of and how many there were.
	private final Map<String, String> broken = new java.util.TreeMap<>();
	private final Map<String, Integer> brokenCount =
			new java.util.HashMap<>();

	@Before
	public void setUp() throws IOException {
		sessionsDir = temp.newFolder("sessions");
		id = new ProjectSessions(sessionsDir).open(new Locator(
				Kind.DIRECTORY, temp.newFolder("project").getPath()));
		drafts = new File(new File(sessionsDir, id), "drafts");
		history = new File(new File(sessionsDir, id), "history");
		assertTrue(drafts.mkdirs() && history.mkdirs());
		// The disk fails by refusing to write; not for a superuser.
		setWritable(false);
		boolean refuses;
		try {
			refuses = !new File(drafts, "probe").createNewFile();
		} catch (IOException e) {
			refuses = true;
		}
		setWritable(true);
		assumeTrue(refuses);
	}

	@Test
	public void reopeningGivesWhatWasHeldOrWhatWasTyped() {
		List<String> counted = new ArrayList<>();
		List<String> expected = new ArrayList<>();
		for (Start start : Start.values()) {
			if (start.everyFault()) {
				continue;
			}
			sequences = 0;
			explore(start, new ArrayList<>());
			counted.add(start + " " + sequences);
			expected.add(start + " " + start.sequences);
		}
		assertEquals(told(), 0, broken.size());
		assertEquals(expected, counted);
		assertEquals(4169, injected);
	}

	@Test
	public void everyOperationOnAFileFailsOnceAndNoTextIsLost() {
		List<String> counted = new ArrayList<>();
		List<String> expected = new ArrayList<>();
		for (Start start : Start.values()) {
			if (!start.everyFault()) {
				continue;
			}
			sequences = 0;
			explore(start, new ArrayList<>());
			counted.add(start + " " + sequences);
			expected.add(start + " " + start.sequences);
		}
		assertEquals(told(), 0, broken.size());
		assertEquals(expected, counted);
		assertEquals(12131, injected);
	}

	// Return what did not hold, each kind with how often and where first.
	private String told() {
		StringBuilder told = new StringBuilder();
		for (Map.Entry<String, String> entry : broken.entrySet()) {
			told.append('\n').append(brokenCount.get(entry.getKey()))
					.append(" x ").append(entry.getKey()).append("; first: ")
					.append(entry.getValue());
		}
		return told.toString();
	}

	@Test
	public void reloadIsRefusedWhenTheJournalCannotKeepTheTextFirst() {
		World world = new World(Start.SAVED);
		world.take(Step.TYPE, "refused");
		List<String> problems = new ArrayList<>();
		setWritable(false, drafts);
		world.project.reload(A, problems::add);
		world.steps.settle();
		setWritable(true, drafts);

		// Nothing is replaced, and the reason is told.
		assertEquals(List.of("its unsaved text cannot be kept first"),
				problems);
		assertEquals("typed 1", world.document.text().toString());
		assertTrue(world.document.isModified());
		// Written once it can be, it is kept like any unsaved text.
		world.project.persist();
		world.steps.settle();
		assertEquals("typed 1", journaled());
		assertTrue(putAside().isEmpty());
	}

	@Test
	public void whatAnOpeningCouldNotReadALookAtStorageFinds() {
		ProjectPath gone = path("gone");
		ProjectPath there = path("there");
		int operations = 1;
		int runs = 0;
		for (int at = 0; at <= operations; ++at) {
			for (FaultyDisk.Fault fault : at == 0
					? new FaultyDisk.Fault[]{FaultyDisk.Fault.NONE}
					: new FaultyDisk.Fault[]{FaultyDisk.Fault.FAIL,
							FaultyDisk.Fault.STUCK, FaultyDisk.Fault.SHORT}) {
				// Unsaved text of a file that is there and of one another
				// app deleted, in a session whose process died.
				disk.reset();
				for (File dir : new File[]{drafts, history}) {
					for (File file : dir.listFiles()) {
						assertTrue(file.delete());
					}
				}
				MemoryProjectFiles store = new MemoryProjectFiles();
				store.writable = true;
				ProjectSessions sessions = new ProjectSessions(sessionsDir);
				sessions.disk = disk;
				Steps steps = new Steps();
				OpenProject project = OpenProject.open(id, store, null, null,
						sessions, steps.worker, steps.main, steps.timers,
						OpenProject.MAX_BUDGET);
				for (ProjectPath path : List.of(gone, there)) {
					store.put(path.toString(), "stored");
					project.load(path, ProjectFiles.UNKNOWN, problem -> {
					});
					steps.settle();
					project.document(path).replace(0, 6,
							Text.of("typed in " + path));
				}
				project.flush(() -> {
				});
				steps.settle();
				store.delete(gone);

				// It opens while an operation fails, goes on failing
				// until it is open, or a read comes short.
				String name = fault + " at operation " + at;
				steps = new Steps();
				disk.arm(fault, at);
				OpenProject opened = OpenProject.open(id, store, null, null,
						sessions, steps.worker, steps.main, steps.timers,
						OpenProject.MAX_BUDGET);
				steps.settle();
				List<FaultyDisk.Kind> kinds = disk.kinds();
				operations = Math.max(operations, disk.disarm());
				if (fault == FaultyDisk.Fault.SHORT &&
						kinds.get(at - 1) != FaultyDisk.Kind.READ) {
					continue;
				}
				++runs;
				// What it could not read then it says; and a look at
				// storage, once the files can be read, finds all of it.
				if (!opened.unsaved().equals(Set.of(gone, there))) {
					assertNotNull(name, opened.unprotected());
				}
				opened.refresh(reread -> {
				});
				steps.settle();
				assertEquals(name, Set.of(gone, there), opened.unsaved());
				assertNull(name, opened.unprotected());
				assertEquals(name, Set.of(gone), opened.missing());
				for (ProjectPath path : List.of(gone, there)) {
					opened.load(path, ProjectFiles.UNKNOWN,
							Assert::assertNull);
					steps.settle();
					assertEquals(name, "typed in " + path,
							opened.document(path).text().toString());
				}
				opened.close();
				steps.settle();
			}
		}
		assertEquals(18, runs);
	}

	@Test
	public void tellsWhatAnotherAppDidToEachFile() {
		MemoryProjectFiles store = new MemoryProjectFiles();
		store.writable = true;
		for (String name : List.of("clean", "mine", "gone", "lost")) {
			store.put(name + ".glsl", "stored");
		}
		Steps steps = new Steps();
		OpenProject project = OpenProject.open(id, store, null, null,
				new ProjectSessions(sessionsDir), steps.worker, steps.main,
				steps.timers, OpenProject.MAX_BUDGET);
		for (String name : List.of("clean", "mine", "gone", "lost")) {
			project.load(path(name), ProjectFiles.UNKNOWN, problem -> {
			});
		}
		steps.settle();
		for (String name : List.of("mine", "gone")) {
			project.document(path(name)).replace(0, 6, Text.of("typed"));
		}
		store.put("clean.glsl", "theirs");
		store.put("mine.glsl", "theirs");
		store.delete(path("gone"));
		store.delete(path("lost"));
		project.refresh(reread -> {
		});
		steps.settle();

		// Without unsaved text the stored text is taken, as one undo step.
		Document clean = project.document(path("clean"));
		assertEquals("theirs", clean.text().toString());
		assertNull(project.conflict(path("clean")));
		clean.undo();
		assertEquals("stored", clean.text().toString());
		// Changed, with unsaved text: a conflict over what was seen.
		assertNotNull(project.conflict(path("mine")));
		assertEquals(FileTexts.Found.CHANGED, project.found(path("mine")));
		assertNotNull(project.seen(path("mine")));
		// Deleted, with unsaved text: a conflict over no file.
		assertNotNull(project.conflict(path("gone")));
		assertEquals(FileTexts.Found.MISSING, project.found(path("gone")));
		assertNull(project.seen(path("gone")));
		// Only unsaved text whose file is gone is missing.
		assertEquals(Set.of(path("gone")), project.missing());
		// Deleted without unsaved text and not shown: no file of the
		// folder anymore.
		assertNull(project.conflict(path("lost")));
		assertNull(project.document(path("lost")));

		// Nothing can be read: no conflict, and the text stays unsaved.
		store.unreadable = "it cannot be read";
		project.refresh(reread -> {
		});
		steps.settle();
		assertNull(project.conflict(path("mine")));
		assertEquals(FileTexts.Found.UNAVAILABLE,
				project.found(path("mine")));
		assertTrue(project.unsaved().contains(path("mine")));
		// No listing leads to unsaved text of storage that cannot be
		// asked, so it is told as missing too: the two typed in and the
		// one undone above.
		assertEquals(Set.of(path("clean"), path("mine"), path("gone")),
				project.missing());
	}

	@Test
	public void unsavedTextOfDeletedFoldersStaysListedThroughASwitchAndARestart() {
		MemoryProjectFiles store = new MemoryProjectFiles();
		store.writable = true;
		List<String> edited = List.of("root", "notes/a", "notes/deep/b");
		for (String name : edited) {
			store.put(name + ".glsl", "stored");
		}
		store.put("other.glsl", "stored");
		Steps steps = new Steps();
		OpenProject project = OpenProject.open(id, store, null, null,
				new ProjectSessions(sessionsDir), steps.worker, steps.main,
				steps.timers, OpenProject.MAX_BUDGET);
		Set<ProjectPath> gone = new HashSet<>();
		for (String name : edited) {
			project.load(path(name), ProjectFiles.UNKNOWN, problem -> {
			});
			steps.settle();
			project.document(path(name)).replace(0, 6, Text.of("typed"));
			gone.add(path(name));
		}
		// Another app deletes a root file and a folder with all in it.
		for (String name : edited) {
			store.delete(path(name));
		}
		project.refresh(reread -> {
		});
		steps.settle();
		assertEquals(gone, project.missing());

		// Another file is shown: no listing shows the three anymore.
		project.load(path("other"), ProjectFiles.UNKNOWN,
				problem -> project.show(path("other")));
		steps.settle();
		assertEquals(gone, project.missing());
		project.flush(() -> {
		});
		steps.settle();

		// The process dies. Each is still told, and opens with its text.
		Steps next = new Steps();
		OpenProject restarted = OpenProject.open(id, store, null, null,
				new ProjectSessions(sessionsDir), next.worker, next.main,
				next.timers, OpenProject.MAX_BUDGET);
		assertEquals(gone, restarted.missing());
		assertEquals(gone, restarted.conflicts().keySet());
		for (ProjectPath path : gone) {
			restarted.load(path, ProjectFiles.UNKNOWN, Assert::assertNull);
			next.settle();
			assertEquals("typed", restarted.document(path).text()
					.toString());
		}
	}

	private static ProjectPath path(String name) {
		return ProjectPath.parse(name + ".glsl").value();
	}

	private void explore(Start start, List<Step> prefix) {
		String name = start + " " + prefix;
		List<Step> next = List.of();
		int operations = 0;
		try {
			World world = new World(start);
			disk.arm(FaultyDisk.Fault.NONE, 0);
			for (Step step : prefix) {
				world.take(step, name);
			}
			next = prefix.size() < start.length
					? world.possible(prefix)
					: List.of();
			operations = disk.disarm();
			world.end(name, true);
		} catch (AssertionError | RuntimeException e) {
			disk.disarm();
			broke(name, e);
		}
		++sequences;
		List<FaultyDisk.Kind> kinds = disk.kinds();
		if (start.everyFault()) {
			for (int at = 1; at <= operations; ++at) {
				for (FaultyDisk.Fault fault : FAULTS) {
					inject(start, prefix, fault, at, false);
				}
				// It goes on failing: until the step is over, and
				// through the opening after the process died.
				inject(start, prefix, FaultyDisk.Fault.STUCK, at, false);
				inject(start, prefix, FaultyDisk.Fault.STUCK, at, true);
				if (kinds.get(at - 1) == FaultyDisk.Kind.READ) {
					inject(start, prefix, FaultyDisk.Fault.SHORT, at, false);
				}
			}
		} else if (operations > 0 && sequences % SAMPLED == 0) {
			java.util.Random random = new java.util.Random(SEED * 31 +
					start.ordinal() * 1_000_003L + sequences);
			int kind = random.nextInt(FAULTS.length + 3);
			int at = 1 + random.nextInt(operations);
			if (kind < FAULTS.length) {
				inject(start, prefix, FAULTS[kind], at, false);
			} else if (kind < FAULTS.length + 2) {
				inject(start, prefix, FaultyDisk.Fault.STUCK, at,
						kind == FAULTS.length);
			} else {
				// The read at or after it, or the first one.
				int read = kinds.subList(at - 1, operations).indexOf(
						FaultyDisk.Kind.READ);
				read = read >= 0 ? at + read
						: 1 + kinds.indexOf(FaultyDisk.Kind.READ);
				inject(start, prefix, read > 0 ? FaultyDisk.Fault.SHORT
						: FaultyDisk.Fault.FAIL, Math.max(read, at), false);
			}
		}
		for (Step step : next) {
			prefix.add(step);
			explore(start, prefix);
			prefix.remove(prefix.size() - 1);
		}
	}

	private static final FaultyDisk.Fault[] FAULTS = {
			FaultyDisk.Fault.FAIL, FaultyDisk.Fault.DIE,
			FaultyDisk.Fault.FAIL_THEN_DIE};

	// Run the steps again while the operation numbered at fails, or
	// the process dies after it; then the process dies, and what opens
	// is compared as always. What goes on failing does so until the
	// step is over, or, with through, until the process died and the
	// project was opened once more: so what a project does to recover
	// when it opens fails as well.
	private void inject(Start start, List<Step> prefix,
			FaultyDisk.Fault fault, int at, boolean through) {
		String name = start + " " + prefix + ", " + fault +
				(through ? " through an opening" : "") +
				" at operation " + at;
		++injected;
		try {
			World world = new World(start);
			disk.arm(fault, at);
			try {
				for (Step step : prefix) {
					world.take(step, name);
					if (!through) {
						disk.heal();
					}
				}
				if (disk.stuck()) {
					world.opening();
				}
			} catch (FaultyDisk.Death e) {
				// The process died there.
			}
			disk.disarm();
			world.end(name, false);
		} catch (AssertionError | RuntimeException e) {
			disk.disarm();
			broke(name, e);
		}
	}

	// Note that something did not hold in sequence.
	private void broke(String sequence, Throwable e) {
		String message = String.valueOf(e.getMessage());
		String what = message.startsWith(sequence + ": ")
				? message.substring(sequence.length() + 2)
				: e instanceof AssertionError
						? message.replace(sequence, "")
						: e.toString();
		// Told by its kind: what follows names texts of the sequence.
		int detail = what.indexOf('"');
		String kind = (detail > 0 ? what.substring(0, detail) : what).trim();
		kind = kind.split(" expected:<| expected null")[0];
		String how = !sequence.contains(" at operation ")
				? "nothing fails"
				: sequence.substring(sequence.lastIndexOf(", ") + 2)
						.replaceAll(" at operation.*", "");
		kind += " (" + how + ")";
		if (!broken.containsKey(kind)) {
			broken.put(kind, sequence + ": " + what);
		}
		Integer count = brokenCount.get(kind);
		brokenCount.put(kind, count == null ? 1 : count + 1);
	}

	// Return the text the journal holds, or null.
	private String journaled() {
		return journaled(A);
	}

	private String journaled(ProjectPath path) {
		Journal.Recovered recovered = new ProjectSessions(sessionsDir)
				.journal(id).read(path);
		return recovered == null ? null : recovered.text() == null
				? "(cannot be read: " + recovered.problem() + ")"
				: recovered.text().toString();
	}

	// Return the texts reloads replaced that are kept beside the journal.
	private Set<String> putAside() {
		Set<String> texts = new HashSet<>();
		Journal journal = new ProjectSessions(sessionsDir).journal(id);
		for (ProjectPath path : List.of(A, B)) {
			for (Journal.Replaced replaced : journal.replaced(path)) {
				texts.add(replaced.text().toString());
			}
		}
		return texts;
	}

	// Texts of more than two million characters each: together more
	// than a history may keep.
	private static final String LARGE_STORED = large('s');
	private static final String LARGE_TYPED = large('u');

	private static String large(char c) {
		return (String.valueOf(c).repeat(99) + "\n").repeat(21_000);
	}

	// Return which of texts undo or redo of document lead to, or it has
	// now; the document is left as it was.
	private static Set<String> reached(Document document,
			Set<String> texts) {
		Set<String> reached = new HashSet<>();
		if (texts.isEmpty()) {
			return reached;
		}
		int undone = 0;
		for (String text = document.text().toString(); ; ++undone) {
			if (texts.contains(text)) {
				reached.add(text);
			}
			if (!document.canUndo()) {
				break;
			}
			document.undo();
			text = document.text().toString();
		}
		while (undone-- > 0) {
			document.redo();
		}
		int redone = 0;
		for (; document.canRedo(); ++redone) {
			document.redo();
			if (texts.contains(document.text().toString())) {
				reached.add(document.text().toString());
			}
		}
		while (redone-- > 0) {
			document.undo();
		}
		return reached;
	}

	private void setWritable(boolean writable) {
		setWritable(writable, drafts, history);
	}

	private static void setWritable(boolean writable, File... dirs) {
		for (File dir : dirs) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					assertTrue(file.setWritable(writable));
				}
			}
			assertTrue(dir.setWritable(writable));
		}
	}

	// One file of one project, from a fresh start.
	private final class World {
		private final MemoryProjectFiles store = new MemoryProjectFiles();
		// Every text the file had, in order; from floor on, those it had
		// since the project last told that all is held or saved.
		private final List<String> texts = new ArrayList<>();
		// Those of them the file had as its saved text, not as unsaved.
		private final Set<Integer> saved = new HashSet<>();
		// The texts a reload replaced since then, and every text a
		// reload ever replaced.
		private final Set<String> givenUp = new HashSet<>();
		private final Set<String> replaced = new HashSet<>();
		// What reloads asked for since the project was opened would
		// give, and the texts they were asked over: a reload the worker
		// made is made, whether or not main heard of it.
		private final Set<String> wanted = new HashSet<>();
		private final Set<String> askedOver = new HashSet<>();
		// What the stored file held at first.
		private final String stored;
		// Whether a write failed since the project was opened.
		private boolean failed;
		// What a reload replaced that undo or redo led to when the
		// process died, and that the document opened since could not be
		// given while its files could not be read: it is to be there
		// once they can.
		private final Set<String> owed = new HashSet<>();
		// What the user held when the process died that an opening
		// with files that could not be read did not show: one of these
		// is to be found once they can, as the text of the file, by
		// undo, in a journal or kept beside one.
		private final Set<String> hidden = new HashSet<>();
		// What the user held at such an opening: any of it may be what
		// opens once the files can be read.
		private final Set<String> later = new HashSet<>();
		// Whether this is the last opening, with files that can be read.
		private boolean ending;
		// Whether another app deleted the file, and the user asked for
		// it to be written since.
		private boolean deletedOutside;
		private boolean askedToWrite;
		private Steps steps = new Steps();
		private OpenProject project;
		private Document document;
		private String text;
		private int floor;
		private int typed;
		private boolean closed;
		// How many texts the file had had when its deletion was asked
		// for, -1 when it was not.
		private int deleteAt = -1;
		// What another app wrote last, null when it wrote nothing; each
		// text it wrote by the revision it has; and those of them the
		// user chose to keep their edits over, as they saw them.
		private String theirs;
		private final Map<String, String> written = new java.util.HashMap<>();
		private final Set<String> kept = new HashSet<>();
		private final Start start;
		private final File[] failing;
		// Whether everything close asked for could be written so far.
		private boolean fence;

		private World(Start start) {
			this.start = start;
			boolean held = start != Start.SAVED &&
					start != Start.UNSTORABLE && start != Start.GONE;
			disk.reset();
			failing = start == Start.HELD || start == Start.RELOADED
					? new File[]{history}
					: new File[]{drafts, history};
			setWritable(true);
			for (File dir : new File[]{drafts, history}) {
				for (File file : dir.listFiles()) {
					assertTrue(file.delete());
				}
			}
			stored = start == Start.LARGE ? LARGE_STORED : "stored";
			store.writable = true;
			store.put("a.glsl", stored);
			texts.add(stored);
			saved.add(0);
			open();
			text = document.text().toString();
			assertEquals(stored, text);
			if (held) {
				take(Step.TYPE, "held");
				project.persist();
				steps.settle();
				see(Step.MAIN, "held");
				assertEquals(1, floor);
			}
			if (start.reloaded()) {
				String mine = text;
				// The history that would lead back cannot be written.
				setWritable(false, history);
				take(Step.RELOAD, "reloaded");
				steps.settle();
				setWritable(true, history);
				see(Step.MAIN, "reloaded");
				assertEquals(stored, text);
				assertNull(journaled());
				assertEquals(Set.of(mine), replaced);
				assertTrue(project.settled(A));
			}
			for (int typed = start == Start.TYPED_TWICE ? 2
					: start == Start.TYPED_ON ? 1 : 0; typed-- > 0; ) {
				take(Step.TYPE, "typed on");
				steps.later.run();
				steps.settle();
				see(Step.MAIN, "typed on");
				assertEquals(text, journaled());
			}
			if (start == Start.UNDONE) {
				take(Step.UNDO, "undone");
				take(Step.UNDO, "undone");
				assertEquals(stored, text);
			}
			if (start == Start.TWO_RELOADS) {
				String first = replaced.iterator().next();
				take(Step.TYPE, "two reloads");
				project.persist();
				steps.settle();
				see(Step.MAIN, "two reloads");
				String second = text;
				take(Step.RELOAD, "two reloads");
				steps.settle();
				see(Step.MAIN, "two reloads");
				assertEquals(Set.of(first, second), putAside());
				// The history keeps a thousand steps: the older goes,
				// with the step of the first reload, two before that
				// of the second.
				for (int steps = 4; steps < 1002; ++steps) {
					document.replace(document.text().length(),
							document.text().length(), Text.of("x"));
				}
				see(Step.TYPE, "two reloads");
				project.persist();
				steps.settle();
				see(Step.MAIN, "two reloads");
				assertEquals(Set.of(second), reached(document, replaced));
				assertEquals(Set.of(second), putAside());
			}
			if (start == Start.GONE) {
				store.delete(A);
			}
			if (start == Start.CHANGED || start == Start.KEPT_THEN_CHANGED) {
				outside("theirs");
				project.refresh(reread -> {
				});
				steps.settle();
				assertEquals(Set.of(A), project.conflicts().keySet());
			}
			if (start == Start.KEPT_THEN_CHANGED) {
				take(Step.KEEP, "kept");
				project.persist();
				steps.settle();
				see(Step.MAIN, "kept");
				assertEquals(Set.of("theirs"), kept);
				die("kept");
				assertTrue(project.conflicts().isEmpty());
				outside("theirs 2");
			}
		}

		// Another app writes text to the file.
		private void outside(String text) {
			store.put("a.glsl", text);
			theirs = text;
			written.put(store.read(A).revision(), text);
		}

		// Return the sessions, whose files are those that may fail.
		private ProjectSessions sessions() {
			ProjectSessions sessions = new ProjectSessions(sessionsDir);
			sessions.disk = disk;
			return sessions;
		}

		private void open() {
			steps = new Steps();
			project = OpenProject.open(id, store, null, null, sessions(),
					steps.worker, steps.main, steps.timers,
					OpenProject.MAX_BUDGET);
			closed = false;
			failed = false;
			load(A);
			// A file deleted elsewhere opens only from unsaved text.
			assertTrue(start == Start.GONE || deletedOutside ||
					document != null);
		}

		// Where a journal could not be read the stored text is shown,
		// and the steps go on from that, as the user may.
		private void load(ProjectPath path) {
			project.load(path, ProjectFiles.UNKNOWN,
					problem -> project.show(path));
			steps.settle();
			document = project.document(path);
		}

		// Return whether what is left under the name before a rename
		// is only left to be deleted: its text is under the new one.
		private boolean followed() {
			Journal.Note note = new ProjectSessions(sessionsDir)
					.journal(id).note();
			boolean followed = note != null;
			for (Journal.Noted noted : note != null
					? note.files()
					: List.<Journal.Noted>of()) {
				followed &= noted.followed();
			}
			return followed;
		}

		// Return where the project has the document, or null when it
		// closed it.
		private ProjectPath at() {
			if (document == null) {
				return null;
			}
			return project.document(A) == document ? A
					: project.document(B) == document ? B : null;
		}

		// Return the steps that change something here.
		private List<Step> possible(List<Step> prefix) {
			List<Step> possible = new ArrayList<>();
			Step last = prefix.isEmpty()
					? null
					: prefix.get(prefix.size() - 1);
			for (Step step : Step.values()) {
				if (start.steps != null && !start.steps.contains(step)) {
					continue;
				}
				if (switch (step) {
					// The same twice in a row is as good as once.
					case TYPE -> !closed && step != last && at() != null;
					// Only where it leads to what a reload replaced, or
					// where the start names it.
					case UNDO -> !closed && (start.reloaded() ||
							start.steps != null) &&
							at() != null && document.canUndo();
					case SAVE -> !closed && step != last &&
							start != Start.MOVED && start != Start.LARGE;
					case BACKGROUND -> !closed && step != last &&
							start != Start.MOVED;
					case RELOAD -> !closed && step != last &&
							start != Start.SAVED && start != Start.MOVED &&
							start != Start.LARGE && start != Start.GONE &&
							at() != null;
					case KEEP -> !closed && step != last &&
							(start == Start.CHANGED ||
									start == Start.KEPT_THEN_CHANGED);
					case AUTOSAVE -> !closed && step != last &&
							(start == Start.KEPT_THEN_CHANGED ||
									start.steps != null);
					case RENAME, DELETE -> !closed && step != last &&
							start.moves() && at() != null &&
							(step == Step.RENAME || start == Start.MOVED ||
									start == Start.EVERY_FAULT_MOVED);
					case CLOSE -> !closed && start != Start.LARGE;
					case TIMER -> !closed && steps.later.size() > 0;
					case WORKER -> steps.worker.size() > 0;
					case FAIL -> steps.worker.size() > 0 &&
							start != Start.LARGE;
					case MAIN -> steps.main.size() > 0;
					case REOPEN -> last != null && last != Step.REOPEN &&
							start != Start.LARGE;
					case SETTLE -> start.steps != null &&
							steps.worker.size() + steps.main.size() > 0;
					case REFRESH -> start.steps != null && !closed &&
							step != last;
					case GONE_OUTSIDE -> start.steps != null &&
							!deletedOutside;
				}) {
					possible.add(step);
				}
			}
			return possible;
		}

		private void take(Step step, String sequence) {
			switch (step) {
				case TYPE -> {
					int length = document.text().length();
					if (start != Start.LARGE) {
						document.replace(0, length, Text.of("typed " +
								++typed + (start == Start.UNSTORABLE
										? " \uD800" : "")));
					} else if (++typed == 1) {
						document.replace(0, length, Text.of(LARGE_TYPED));
					} else {
						// It stays large: typed in front of it.
						document.replace(0, 0, Text.of("typed " + typed +
								" "));
					}
				}
				case UNDO -> document.undo();
				case TIMER -> steps.later.run();
				case WORKER -> steps.worker.run();
				case MAIN -> steps.main.run();
				case FAIL -> {
					setWritable(false, failing);
					steps.worker.run();
					setWritable(true, failing);
					fence = false;
					failed = true;
				}
				case RELOAD -> {
					if (document.isModified()) {
						wanted.add(store.text(at().toString()));
						askedOver.add(text);
					}
					if (start == Start.CHANGED) {
						project.resolve(A, OpenProject.Choice.RELOAD,
								project.seen(A));
					} else {
						project.reload(at(), problem -> {
						});
					}
				}
				case SETTLE -> steps.settle();
				case REFRESH -> project.refresh(reread -> {
				});
				case GONE_OUTSIDE -> {
					store.delete(at() != null ? at() : A);
					deletedOutside = true;
				}
				case KEEP -> {
					// Consent is for the text as it was seen then.
					String seen = project.seen(A);
					if (project.conflicts().containsKey(A) &&
							written.containsKey(seen)) {
						kept.add(written.get(seen));
					}
					project.resolve(A, OpenProject.Choice.KEEP, seen);
				}
				case AUTOSAVE -> project.save(false, outcomes -> {
				});
				case SAVE -> project.save(true, outcomes -> {
					askedToWrite = true;
					OpenProject.Outcome outcome = outcomes.get(A);
					if (start == Start.GONE && at() == A &&
							(outcome == null ||
									outcome instanceof OpenProject.Saved ||
									outcome instanceof OpenProject.Created)) {
						assertNotNull(sequence + ": saved, but no file",
								store.read(A));
					}
				});
				case BACKGROUND -> {
					OpenProject asked = project;
					int revision = document.revision();
					Document flushed = document;
					ProjectPath where = at();
					// Unsaved text it answers for without a reason is in
					// the journal as it was when it was asked, or kept
					// beside it by a reload asked for since.
					asked.flush(() -> {
						if (flushed.revision() == revision &&
								flushed.isModified() &&
								asked.unprotected() == null) {
							String held = flushed.text().toString();
							assertTrue(sequence + ": flush answered" +
											" without a reason",
									held.equals(journaled(where)) ||
											putAside().contains(held));
						}
					});
				}
				case CLOSE -> {
					project.close();
					closed = true;
					fence = true;
				}
				case RENAME -> project.rename(at(), at() == A ? B : A,
						moved -> {
						});
				case DELETE -> {
					// What is typed after the first one asked for was not
					// given up with it; a later one may never start.
					if (deleteAt < 0) {
						deleteAt = texts.size();
					}
					project.delete(at(), document.isModified()
							? Map.of(at(), document.revision())
							: Map.of(), moved -> {
					});
				}
				case REOPEN -> {
					die(sequence);
					return;
				}
			}
			see(step, sequence);
		}

		// Note what the project tells after a step.
		private void see(Step step, String sequence) {
			if (closed) {
				// Close held the text once all it asked for was done.
				if (fence && !disk.happened() && steps.worker.size() == 0) {
					floor = texts.size() - 1;
					givenUp.clear();
				}
				return;
			}
			ProjectPath at = at();
			String now = document.text().toString();
			if (at == null) {
				// Closed by a deletion: nothing more is told of it.
				return;
			}
			if (!now.equals(text)) {
				if (step != Step.TYPE && step != Step.UNDO) {
					// Only a reload takes text from the screen so, and
					// only with the text kept beside the journal.
					givenUp.add(text);
					replaced.add(text);
				}
				texts.add(now);
				text = now;
			}
			if (!document.isModified()) {
				saved.add(texts.size() - 1);
			}
			boolean idle = steps.worker.size() == 0 &&
					steps.main.size() == 0;
			gone(sequence);
			if (project.settled(at)) {
				floor = texts.size() - 1;
				givenUp.clear();
				if (!failed && !disk.happened()) {
					assertNull(sequence, project.unprotected());
				}
				if (idle) {
					// As the journal is told to be, it is: the text
					// when it is unsaved, none when it is not. After a
					// rename it could not follow yet it holds it under
					// the name before, noted to follow.
					String held = journaled(at);
					if (held == null && disk.happened() && !followed()) {
						held = journaled(at == A ? B : A);
					}
					// One that could not be read when the file was
					// opened holds what it held, and is told as that.
					if (project.unreadable(at) == null) {
						assertEquals(sequence + ": settled, journal",
								document.isModified() ? now : null, held);
					}
				}
			} else if (!failed && !disk.happened() && idle &&
					steps.later.size() == 0) {
				// Nothing is on its way and nothing failed: whatever was
				// typed is in the journal.
				assertTrue(sequence + ": \"" + brief(now) + "\" is not" +
						" held, and nothing is on its way", false);
			}
			if (!disk.happened()) {
				assertNull(sequence, project.unreadable(at));
				assertEquals(sequence, 0, project.damagedJournals());
			}
			theirsKept(sequence);
			if (!document.isModified() && steps.main.size() == 0 &&
					project.found(at) == FileTexts.Found.SAME &&
					store.read(at) != null) {
				// Exactly: not what its bytes would be. Once main heard
				// of a save that was on its way: undone meanwhile, the
				// document is at the text saved before until then.
				assertTrue(sequence + ": saved, but stored \"" +
								brief(new String(store.read(at).bytes(),
										StandardCharsets.UTF_8)) + "\"",
						FileTexts.holds(store.read(at).bytes(),
								document.text()));
			}
		}

		// The process dies; what was not written is gone. Open again
		// and compare what is there with what may be.
		private void die(String sequence) {
			Set<String> may = new HashSet<>(texts.subList(floor,
					texts.size()));
			may.addAll(givenUp);
			Set<String> held = new HashSet<>(may);
			// What an opening before could not show may be shown now.
			may.addAll(later);
			// What the files hold of it, now that the process is dead.
			Set<String> onDisk = new HashSet<>(putAside());
			onDisk.add(String.valueOf(journaled(A)));
			onDisk.add(String.valueOf(journaled(B)));
			onDisk.retainAll(held);
			// Not what the user gave up with the file they deleted.
			for (int i = 0; i < deleteAt; ++i) {
				onDisk.remove(texts.get(i));
			}
			// What a reload replaced and the history still leads to.
			if (document != null) {
				owed.addAll(reached(document, replaced));
			}
			Set<String> wanted = new HashSet<>(this.wanted);
			Set<String> askedOver = new HashSet<>(this.askedOver);
			boolean opened = !start.moves();
			if (start.moves()) {
				opened = moved(sequence, may);
			} else {
				open();
			}
			// What was held and is not shown now, while a file could not
			// be read, is there all the same; and once every file can
			// be read, it is found.
			boolean whole = ending || !disk.happened();
			String shown = document != null
					? document.text().toString()
					: null;
			if (!whole) {
				later.addAll(held);
				if (!held.contains(shown)) {
					hidden.addAll(onDisk);
				}
			} else if (!hidden.isEmpty()) {
				Set<String> there = new HashSet<>(putAside());
				there.add(String.valueOf(journaled(A)));
				there.add(String.valueOf(journaled(B)));
				if (document != null) {
					there.add(shown);
					there.addAll(reached(document, hidden));
				}
				there.retainAll(hidden);
				assertFalse(sequence + ": what an opening could not show" +
						" is gone for good: " + brief(hidden),
						there.isEmpty());
				hidden.clear();
			}
			if (whole) {
				later.clear();
			}
			if (!opened) {
				return;
			}
			// Only now: an opening the process died in is none.
			this.wanted.clear();
			this.askedOver.clear();
			gone(sequence);
			if (document == null) {
				// Neither file nor journal: the file was deleted
				// elsewhere while it had no unsaved text, as it was at
				// some moment since its text was last told held.
				boolean hadNone = false;
				for (int i = floor; i < texts.size(); ++i) {
					hadNone |= saved.contains(i);
				}
				assertTrue(sequence + ": held text is gone", hadNone);
				closed = true;
				return;
			}
			// While a file could not be read, what opened may lack what
			// that file holds: it is owed until one opens that can.
			boolean readable = ending || !disk.happened();
			String found = document.text().toString();
			if (!may.contains(found) && wanted.contains(found)) {
				// The reload was made and main never heard: the file is
				// as stored, as asked, and undo leads back.
				assertFalse(sequence + ": reopened as stored, without" +
						" what the reload was asked over within reach",
						readable &&
								reached(document, askedOver).isEmpty());
			} else if (readable && (store.read(A) != null ||
					store.read(B) != null)) {
				assertTrue(sequence + ": reopened \"" + brief(found) +
						"\", but the user had " + brief(may) + " of " +
						brief(texts), may.contains(found));
			}
			ProjectPath at = at();
			if (readable) {
				assertNull(sequence, project.unreadable(at));
			}
			theirsKept(sequence);
			// Unsaved text over what another app wrote is in conflict
			// again until the user chose to keep it.
			if (readable && theirs != null && !kept.contains(theirs) &&
					document.isModified() &&
					theirs.equals(store.text("a.glsl"))) {
				assertTrue(sequence + ": unsaved text over theirs without" +
						" a conflict", project.conflicts().containsKey(A));
			}
			// Undo and redo lead to what they led to: every text a reload
			// replaced that was within reach when the process died.
			Set<String> given = reached(document, owed);
			if (readable) {
				assertEquals(sequence + ": reopened \"" + brief(found) +
								"\", what a reload replaced within reach",
						brief(owed), brief(given));
			}
			owed.removeAll(given);
			if (!found.equals(text)) {
				texts.add(found);
				text = found;
			}
			// What a restart gives is what the next one gives.
			floor = texts.size() - 1;
			if (!document.isModified()) {
				saved.add(floor);
			}
			givenUp.clear();
		}

		// The sequence is over: the process dies. With denied, the
		// project is first opened while none of its files can be read,
		// as when the permission is gone for a while, and dies again;
		// then it opens for good, and everything is compared.
		private void end(String sequence, boolean denied) {
			if (denied) {
				// One of the ways in which files fail for a while, each
				// in its turn: none can be read, the folder cannot be
				// listed, nothing can be written, reads come short,
				// nothing can be renamed or deleted.
				int way = sequences % 5;
				disk.denied = way == 0;
				disk.unlisted = way == 1;
				disk.full = way == 2;
				disk.shortened = way == 3;
				disk.unrenamed = disk.undeleted = way == 4;
				try {
					opening();
				} finally {
					disk.denied = disk.unlisted = disk.full = false;
					disk.shortened = disk.unrenamed = disk.undeleted = false;
				}
			}
			ending = true;
			die(sequence);
		}

		// The process dies and the project is opened while files fail,
		// with both files; nothing is done there, and the process dies
		// again. What was open before stays what is compared.
		private void opening() {
			if (document != null) {
				owed.addAll(reached(document, replaced));
			}
			steps = new Steps();
			OpenProject failing = OpenProject.open(id, store, null, null,
					sessions(), steps.worker, steps.main, steps.timers,
					OpenProject.MAX_BUDGET);
			for (ProjectPath path : List.of(A, B)) {
				failing.load(path, ProjectFiles.UNKNOWN, problem -> {
				});
			}
			steps.settle();
		}

		// A file another app deleted is made again only when the user
		// asked for a write of it.
		private void gone(String sequence) {
			if (deletedOutside && !askedToWrite) {
				assertNull(sequence + ": a file another app deleted is" +
						" there again", store.read(A));
			}
		}

		private static String brief(String text) {
			return text.length() > 40 ? text.substring(0, 40) + "..." : text;
		}

		private static List<String> brief(java.util.Collection<String> texts) {
			List<String> brief = new ArrayList<>();
			for (String text : texts) {
				brief.add(brief(text));
			}
			java.util.Collections.sort(brief);
			return brief;
		}

		// What another app wrote is written over only after the user saw
		// it in their document, or chose to keep their edits over it.
		private void theirsKept(String sequence) {
			if (theirs != null && !kept.contains(theirs) &&
					!texts.contains(theirs)) {
				assertEquals(sequence + ": wrote over their text", theirs,
						store.text("a.glsl"));
			}
		}

		// Open again after renames and deletions: the stored file and
		// the unsaved text are under one name, the same, or both gone.
		// Return whether there is a file to go on with.
		private boolean moved(String sequence, Set<String> may) {
			steps = new Steps();
			project = OpenProject.open(id, store, null, null, sessions(),
					steps.worker, steps.main, steps.timers,
					OpenProject.MAX_BUDGET);
			closed = false;
			List<ProjectPath> stored = new ArrayList<>();
			for (ProjectPath path : List.of(A, B)) {
				if (store.read(path) != null) {
					stored.add(path);
				}
			}
			Set<ProjectPath> unsaved = project.unsaved();
			String state = sequence + ": stored " + stored + ", unsaved " +
					unsaved;
			// While a file could not be read or written, what follows a
			// rename may still be on its way: the next opening ends it.
			boolean readable = ending || !disk.happened();
			assertTrue(state, stored.size() <= 1);
			if (readable) {
				assertTrue(state, unsaved.size() <= 1);
				assertNull(state, new ProjectSessions(sessionsDir)
						.journal(id).note());
				assertTrue(state, project.conflicts().isEmpty() ||
						stored.isEmpty());
			}
			if (stored.isEmpty()) {
				// Deleted, as asked: only what was typed after that may
				// be there, as unsaved text of a file that is gone.
				assertTrue(state, deleteAt >= 0);
				if (unsaved.isEmpty()) {
					document = null;
					closed = true;
					return false;
				}
				load(unsaved.iterator().next());
				// Not what the deletion was asked for; but what an
				// opening could not show was never given up with it.
				assertTrue(state + ": text given up with the file is back",
						!readable || may.contains(
								document.text().toString()) &&
								hidden.contains(document.text().toString()) ||
								texts.indexOf(document.text().toString()) >=
										deleteAt);
				return true;
			}
			assertTrue(state, !readable || unsaved.isEmpty() ||
					unsaved.equals(Set.copyOf(stored)));
			load(stored.get(0));
			assertNotNull(state, document);
			return true;
		}
	}
}
