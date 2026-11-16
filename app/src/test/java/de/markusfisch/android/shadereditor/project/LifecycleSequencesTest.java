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
		REOPEN
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
		KEPT_THEN_CHANGED(4, 3989);

		private final int length;
		private final int sequences;

		Start(int length, int sequences) {
			this.length = length;
			this.sequences = sequences;
		}

		// Whether the start is past a reload that replaced unsaved text.
		private boolean reloaded() {
			return this == RELOADED || this == TYPED_ON ||
					this == TYPED_TWICE || this == UNDONE || this == LARGE;
		}
	}

	private static final ProjectPath B = ProjectPath.parse("b.glsl").value();
	private static final ProjectPath A = ProjectPath.parse("a.glsl").value();

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File sessionsDir;
	private File drafts;
	private File history;
	private String id;
	private int sequences;

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
			sequences = 0;
			explore(start, new ArrayList<>());
			counted.add(start + " " + sequences);
			expected.add(start + " " + start.sequences);
		}

		assertEquals(expected, counted);
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
		World world = new World(start);
		String name = start + " " + prefix;
		for (Step step : prefix) {
			world.take(step, name);
		}
		List<Step> next = prefix.size() < start.length
				? world.possible(prefix)
				: List.of();
		world.die(name);
		++sequences;
		for (Step step : next) {
			prefix.add(step);
			explore(start, prefix);
			prefix.remove(prefix.size() - 1);
		}
	}

	// Return the text the journal holds, or null.
	private String journaled() {
		return journaled(A);
	}

	private String journaled(ProjectPath path) {
		Journal.Recovered recovered = new ProjectSessions(sessionsDir)
				.journal(id).read(path);
		return recovered != null ? recovered.text().toString() : null;
	}

	// Return the texts reloads replaced that are kept beside the journal.
	private Set<String> putAside() {
		Set<String> texts = new HashSet<>();
		for (Journal.Replaced replaced : new ProjectSessions(sessionsDir)
				.journal(id).replaced(A)) {
			texts.add(replaced.text().toString());
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

		private void open() {
			steps = new Steps();
			project = OpenProject.open(id, store, null, null,
					new ProjectSessions(sessionsDir), steps.worker,
					steps.main, steps.timers, OpenProject.MAX_BUDGET);
			closed = false;
			failed = false;
			load(A);
			// A file deleted elsewhere opens only from unsaved text.
			assertTrue(start == Start.GONE || document != null);
		}

		private void load(ProjectPath path) {
			project.load(path, ProjectFiles.UNKNOWN,
					problem -> project.show(path));
			steps.settle();
			document = project.document(path);
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
				if (switch (step) {
					// The same twice in a row is as good as once.
					case TYPE -> !closed && step != last && at() != null;
					// Only where it leads to what a reload replaced.
					case UNDO -> !closed && start.reloaded() &&
							at() != null && document.canUndo();
					case SAVE -> !closed && step != last &&
							start != Start.MOVED && start != Start.LARGE;
					case BACKGROUND -> !closed && step != last &&
							start != Start.MOVED;
					case RELOAD -> !closed && step != last &&
							start != Start.SAVED && start != Start.MOVED &&
							start != Start.LARGE && start != Start.GONE;
					case KEEP -> !closed && step != last &&
							(start == Start.CHANGED ||
									start == Start.KEPT_THEN_CHANGED);
					case AUTOSAVE -> !closed && step != last &&
							start == Start.KEPT_THEN_CHANGED;
					case RENAME, DELETE -> !closed && step != last &&
							start == Start.MOVED && at() != null;
					case CLOSE -> !closed && start != Start.LARGE;
					case TIMER -> !closed && steps.later.size() > 0;
					case WORKER -> steps.worker.size() > 0;
					case FAIL -> steps.worker.size() > 0 &&
							start != Start.LARGE;
					case MAIN -> steps.main.size() > 0;
					case REOPEN -> last != null && last != Step.REOPEN &&
							start != Start.LARGE;
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
						wanted.add(store.text("a.glsl"));
						askedOver.add(text);
					}
					if (start == Start.CHANGED) {
						project.resolve(A, OpenProject.Choice.RELOAD,
								project.seen(A));
					} else {
						project.reload(A, problem -> {
						});
					}
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
									held.equals(journaled()) ||
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
				if (fence && steps.worker.size() == 0) {
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
			boolean idle = steps.worker.size() == 0 &&
					steps.main.size() == 0;
			if (project.settled(at)) {
				floor = texts.size() - 1;
				givenUp.clear();
				if (!failed) {
					assertNull(sequence, project.unprotected());
				}
				if (idle) {
					// As the journal is told to be, it is: the text
					// when it is unsaved, none when it is not.
					assertEquals(sequence + ": settled, journal",
							document.isModified() ? now : null,
							journaled(at));
				}
			} else if (!failed && idle && steps.later.size() == 0) {
				// Nothing is on its way and nothing failed: whatever was
				// typed is in the journal.
				assertTrue(sequence + ": \"" + brief(now) + "\" is not" +
						" held, and nothing is on its way", false);
			}
			assertNull(sequence, project.unreadable(at));
			assertEquals(sequence, 0, project.damagedJournals());
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
			// What a reload replaced and the history still leads to.
			Set<String> led = document != null
					? reached(document, replaced)
					: null;
			Set<String> wanted = new HashSet<>(this.wanted);
			Set<String> askedOver = new HashSet<>(this.askedOver);
			this.wanted.clear();
			this.askedOver.clear();
			if (start == Start.MOVED) {
				if (!moved(sequence, may)) {
					return;
				}
			} else {
				open();
			}
			if (document == null) {
				// Neither file nor journal: nothing typed was held since
				// the file was deleted elsewhere.
				assertEquals(sequence + ": held text is gone", 0, floor);
				closed = true;
				return;
			}
			String found = document.text().toString();
			if (!may.contains(found) && wanted.contains(found)) {
				// The reload was made and main never heard: the file is
				// as stored, as asked, and undo leads back.
				assertFalse(sequence + ": reopened as stored, without" +
						" what the reload was asked over within reach",
						reached(document, askedOver).isEmpty());
			} else if (store.read(A) != null || store.read(B) != null) {
				assertTrue(sequence + ": reopened \"" + brief(found) +
						"\", but the user had " + brief(may) + " of " +
						brief(texts), may.contains(found));
			}
			ProjectPath at = at();
			assertNull(sequence, project.unreadable(at));
			theirsKept(sequence);
			// Unsaved text over what another app wrote is in conflict
			// again until the user chose to keep it.
			if (theirs != null && !kept.contains(theirs) &&
					document.isModified() &&
					theirs.equals(store.text("a.glsl"))) {
				assertTrue(sequence + ": unsaved text over theirs without" +
						" a conflict", project.conflicts().containsKey(A));
			}
			// Undo and redo lead to what they led to: every text a reload
			// replaced that was within reach when the process died.
			if (led != null) {
				assertEquals(sequence + ": reopened \"" + brief(found) +
								"\", what a reload replaced within reach",
						brief(led), brief(reached(document, led)));
			}
			if (!found.equals(text)) {
				texts.add(found);
				text = found;
			}
			// What a restart gives is what the next one gives.
			floor = texts.size() - 1;
			givenUp.clear();
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
			project = OpenProject.open(id, store, null, null,
					new ProjectSessions(sessionsDir), steps.worker,
					steps.main, steps.timers, OpenProject.MAX_BUDGET);
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
			assertTrue(state, stored.size() <= 1 && unsaved.size() <= 1);
			assertNull(state, new ProjectSessions(sessionsDir).journal(id)
					.move());
			assertTrue(state, project.conflicts().isEmpty() ||
					stored.isEmpty());
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
				assertTrue(state + ": text given up with the file is back",
						texts.indexOf(document.text().toString()) >=
								deleteAt);
				return true;
			}
			assertTrue(state, unsaved.isEmpty() || unsaved.equals(
					Set.copyOf(stored)));
			load(stored.get(0));
			assertNotNull(state, document);
			return true;
		}
	}
}
