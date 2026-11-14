package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

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
 * the process: what opens afterwards is text the user had and did not
 * have kept since, or gave up, and exactly the text they have where
 * the project said it was held or saved. Text a reload replaced after
 * it was held is there again, as it is or by undo.
 */
public class LifecycleSequencesTest {
	private enum Step {
		/** The user replaces the text. */
		TYPE,
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
		/** The user reloads the file from storage. */
		RELOAD,
		/** The user saves. */
		SAVE,
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
		SAVED(5, SAVED_SEQUENCES),
		/** The same; what is typed has a character no file can hold. */
		UNSTORABLE(4, UNSTORABLE_SEQUENCES),
		/** Unsaved text the journal holds; FAIL fails the history. */
		HELD(4, HELD_SEQUENCES),
		/**
		 * The same, replaced by a reload whose history could not be
		 * written: the journal waits for one.
		 */
		RELOADED(4, RELOADED_SEQUENCES),
		/**
		 * Unsaved text the journal holds, with RENAME and DELETE in
		 * place of reload, save and flush(done); FAIL fails journal and
		 * history.
		 */
		MOVED(4, MOVED_SEQUENCES);

		private final int length;
		private final int sequences;

		Start(int length, int sequences) {
			this.length = length;
			this.sequences = sequences;
		}
	}

	private static final int SAVED_SEQUENCES = 3371;
	private static final int UNSTORABLE_SEQUENCES = 1340;
	private static final int HELD_SEQUENCES = 1925;
	private static final int RELOADED_SEQUENCES = 1931;
	private static final int MOVED_SEQUENCES = 926;
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
		Journal.Recovered recovered = new ProjectSessions(sessionsDir)
				.journal(id).read(A);
		return recovered != null ? recovered.text().toString() : null;
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
		// The texts a reload replaced since then; and the one the last
		// reload replaced when it was held and nothing was typed since.
		private final Set<String> givenUp = new HashSet<>();
		private String replaced;
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
		// Whether the user ever asked for a reload.
		private boolean reloaded;
		private final Start start;
		private final File[] failing;
		// Whether everything close asked for could be written so far.
		private boolean fence;

		private World(Start start) {
			this.start = start;
			boolean held = start != Start.SAVED &&
					start != Start.UNSTORABLE;
			failing = start == Start.HELD || start == Start.RELOADED
					? new File[]{history}
					: new File[]{drafts, history};
			setWritable(true);
			for (File dir : new File[]{drafts, history}) {
				for (File file : dir.listFiles()) {
					assertTrue(file.delete());
				}
			}
			store.writable = true;
			store.put("a.glsl", "stored");
			texts.add("stored");
			open();
			text = document.text().toString();
			assertEquals("stored", text);
			if (held) {
				take(Step.TYPE, "held");
				project.persist();
				steps.settle();
				see(Step.MAIN, "held");
				assertEquals(1, floor);
			}
			if (start == Start.RELOADED) {
				for (Step step : List.of(Step.RELOAD, Step.WORKER, Step.MAIN,
						Step.FAIL, Step.MAIN)) {
					take(step, "reloaded");
				}
				assertEquals("stored", text);
				assertEquals("typed 1", journaled());
				assertEquals("typed 1", replaced);
			}
		}

		private void open() {
			steps = new Steps();
			project = OpenProject.open(id, store, null, null,
					new ProjectSessions(sessionsDir), steps.worker,
					steps.main, steps.timers, OpenProject.MAX_BUDGET);
			closed = false;
			load(A);
			assertNotNull(document);
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
					case SAVE, BACKGROUND -> !closed && step != last &&
							start != Start.MOVED;
					case RELOAD -> !closed && step != last &&
							start != Start.SAVED && start != Start.MOVED;
					case RENAME, DELETE -> !closed && step != last &&
							start == Start.MOVED && at() != null;
					case CLOSE -> !closed;
					case TIMER -> !closed && steps.later.size() > 0;
					case WORKER, FAIL -> steps.worker.size() > 0;
					case MAIN -> steps.main.size() > 0;
					case REOPEN -> last != null && last != Step.REOPEN;
				}) {
					possible.add(step);
				}
			}
			return possible;
		}

		private void take(Step step, String sequence) {
			switch (step) {
				case TYPE -> document.replace(0, document.text().length(),
						Text.of("typed " + ++typed +
								(start == Start.UNSTORABLE ? " \uD800" : "")));
				case TIMER -> steps.later.run();
				case WORKER -> steps.worker.run();
				case MAIN -> steps.main.run();
				case FAIL -> {
					setWritable(false, failing);
					steps.worker.run();
					setWritable(true, failing);
					fence = false;
				}
				case RELOAD -> {
					reloaded = true;
					project.reload(A, problem -> {
					});
				}
				case SAVE -> project.save(true, outcomes -> {
				});
				case BACKGROUND -> {
					OpenProject asked = project;
					int revision = document.revision();
					Document flushed = document;
					// Unsaved text it answers for without a reason is in
					// the journal as it was when it was asked.
					asked.flush(() -> {
						if (flushed.revision() == revision &&
								flushed.isModified() &&
								asked.unprotected() == null) {
							assertEquals(sequence + ": flush answered" +
											" without a reason",
									flushed.text().toString(), journaled());
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
				replaced = null;
				if (step != Step.TYPE) {
					givenUp.add(text);
					replaced = floor == texts.size() - 1 ? text : null;
				}
				texts.add(now);
				text = now;
			}
			if (project.settled(at)) {
				floor = texts.size() - 1;
				givenUp.clear();
			}
			assertNull(sequence, project.unreadable(at));
			assertEquals(sequence, 0, project.damagedJournals());
			if (!document.isModified() &&
					project.found(at) == FileTexts.Found.SAME &&
					store.read(at) != null) {
				// Exactly: not what its bytes would be.
				assertTrue(sequence + ": saved, but stored \"" +
								new String(store.read(at).bytes(),
										StandardCharsets.UTF_8) + "\"",
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
			if (start == Start.MOVED) {
				if (!moved(sequence, may)) {
					return;
				}
			} else {
				open();
			}
			String found = document.text().toString();
			if (store.read(A) != null || store.read(B) != null) {
				assertTrue(sequence + ": reopened \"" + found +
						"\", but the user had " + may + " of " + texts,
						may.contains(found));
			}
			ProjectPath at = at();
			assertNull(sequence, project.unreadable(at));
			// Text that comes back after a reload says so, where its
			// journal could be written; no other text does.
			if (found.equals(replaced) && failing.length == 1) {
				assertTrue(sequence + ": came back without saying so",
						project.replaced(at));
			} else if (!reloaded) {
				assertTrue(sequence + ": says a reload replaced it",
						!project.replaced(at));
			}
			if (replaced != null && !found.equals(replaced)) {
				assertTrue(sequence + ": no undo to what was reloaded",
						document.canUndo());
				document.undo();
				assertEquals(sequence + ": undo of the reload", replaced,
						document.text().toString());
				document.redo();
			}
			replaced = null;
			if (!found.equals(text)) {
				texts.add(found);
				text = found;
			}
			// What a restart gives is what the next one gives.
			floor = texts.size() - 1;
			givenUp.clear();
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
