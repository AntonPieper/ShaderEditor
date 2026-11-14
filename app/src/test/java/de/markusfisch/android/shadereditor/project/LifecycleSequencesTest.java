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
		 * history from a saved file, the history alone after unsaved
		 * text, where a reload depends on it.
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
		/** The process dies and the project is opened again. */
		REOPEN
	}

	// How many steps a sequence has at most, from a file that is saved
	// and from one with unsaved text the journal holds; steps that would
	// change nothing where they stand are left out.
	private static final int LENGTH = 5;
	private static final int LENGTH_HELD = 4;
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
		explore(false, LENGTH, new ArrayList<>());
		explore(true, LENGTH_HELD, new ArrayList<>());

		// 8458 and 1925 when this was written.
		assertTrue("sequences: " + sequences, sequences > 5000);
	}

	private void explore(boolean held, int length, List<Step> prefix) {
		World world = new World(held);
		String name = (held ? "held, " : "") + prefix;
		for (Step step : prefix) {
			world.take(step, name);
		}
		List<Step> next = prefix.size() < length
				? world.possible(prefix)
				: List.of();
		world.die(name);
		++sequences;
		for (Step step : next) {
			prefix.add(step);
			explore(held, length, prefix);
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
		private final File[] failing;
		// Whether everything close asked for could be written so far.
		private boolean fence;

		private World(boolean held) {
			failing = held
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
		}

		private void open() {
			steps = new Steps();
			project = OpenProject.open(id, store, null, null,
					new ProjectSessions(sessionsDir), steps.worker,
					steps.main, steps.timers, OpenProject.MAX_BUDGET);
			project.load(A, ProjectFiles.UNKNOWN,
					problem -> project.show(A));
			steps.settle();
			document = project.document(A);
			assertNotNull(document);
			closed = false;
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
					case TYPE, RELOAD, SAVE, BACKGROUND -> !closed &&
							step != last;
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
						Text.of("typed " + ++typed));
				case TIMER -> steps.later.run();
				case WORKER -> steps.worker.run();
				case MAIN -> steps.main.run();
				case FAIL -> {
					setWritable(false, failing);
					steps.worker.run();
					setWritable(true, failing);
					fence = false;
				}
				case RELOAD -> project.reload(A, problem -> {
				});
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
			String now = document.text().toString();
			if (!now.equals(text)) {
				replaced = null;
				if (step != Step.TYPE) {
					givenUp.add(text);
					replaced = floor == texts.size() - 1 ? text : null;
				}
				texts.add(now);
				text = now;
			}
			if (project.settled(A)) {
				floor = texts.size() - 1;
				givenUp.clear();
			}
			assertNull(sequence, project.unreadable(A));
			assertEquals(sequence, 0, project.damagedJournals());
			if (!document.isModified() &&
					project.found(A) == FileTexts.Found.SAME) {
				assertEquals(sequence + ": saved, but stored otherwise",
						now, new String(store.read(A).bytes(),
								StandardCharsets.UTF_8));
			}
		}

		// The process dies; what was not written is gone. Open again
		// and compare what is there with what may be.
		private void die(String sequence) {
			Set<String> may = new HashSet<>(texts.subList(floor,
					texts.size()));
			may.addAll(givenUp);
			open();
			String found = document.text().toString();
			assertTrue(sequence + ": reopened \"" + found +
					"\", but the user had " + may + " of " + texts,
					may.contains(found));
			assertNull(sequence, project.unreadable(A));
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
	}
}
