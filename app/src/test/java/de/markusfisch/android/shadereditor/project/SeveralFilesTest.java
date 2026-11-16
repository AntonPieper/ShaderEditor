package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What every day has and {@link LifecycleSequencesTest} has not: more
 * than one file with unsaved text at once, and documents that leave
 * memory and come back. No file fails here. In each scenario the user
 * does several things at once, and the process dies after every single
 * step the worker, the main thread and the timers take from there, one
 * run for each such moment. Whenever it dies, the same must hold: of
 * every document, the newest text the project told as held, or one
 * typed after it, is somewhere when the project opens again: in a
 * journal, in a text a reload replaced, or in storage, under any of
 * the names involved.
 */
public class SeveralFilesTest {
	private static final String[] NAMES = {"a.glsl", "b.glsl", "c.glsl",
			"d.glsl", "f/x.glsl", "f/y.glsl", "g/x.glsl", "g/y.glsl"};

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	// A scenario: where it starts, and what the user does at once.
	private interface Scenario {
		void setUp(World world);

		void act(World world);
	}

	// One project over files in memory, with real journals.
	private final class World {
		private final MemoryProjectFiles store = new MemoryProjectFiles();
		private final File root;
		private final String id;
		// Every text typed into each document, in order.
		private final Map<Document, List<String>> typed = new HashMap<>();
		private Steps steps;
		private OpenProject project;
		private long budget = OpenProject.MAX_BUDGET;

		private World() throws IOException {
			root = temp.newFolder();
			id = new ProjectSessions(root).open(new ProjectSessions.Locator(
					ProjectSessions.Kind.DIRECTORY,
					temp.newFolder().getPath()));
			store.writable = true;
			store.put("a.glsl", "stored");
		}

		private void open() {
			steps = new Steps();
			project = OpenProject.open(id, store, null, null,
					new ProjectSessions(root), steps.worker, steps.main,
					steps.timers, budget);
		}

		// The process dies: nothing that waited runs. It opens again.
		private void die() {
			steps.worker.clear();
			steps.main.clear();
			steps.later.clear();
			open();
		}

		private Document load(String name) {
			ProjectPath path = path(name);
			project.load(path, ProjectFiles.UNKNOWN,
					problem -> project.show(path));
			steps.settle();
			return project.document(path);
		}

		private void type(String name, String text) {
			Document document = project.document(path(name));
			typed.computeIfAbsent(document, key -> new ArrayList<>())
					.add(text);
			document.replace(0, document.text().length(), Text.of(text));
		}

		private void flush() {
			project.persist();
			steps.settle();
		}

		private void refresh() {
			project.refresh(reread -> {
			});
			steps.settle();
		}

		private String journaled(String name) {
			Journal.Recovered recovered = new ProjectSessions(root)
					.journal(id).read(path(name));
			return recovered != null && recovered.text() != null
					? recovered.text().toString()
					: null;
		}

		// Return every text there is: in a journal, kept beside one, or
		// stored, under any of the names.
		private Set<String> everywhere() {
			Set<String> all = new HashSet<>();
			Journal journal = new ProjectSessions(root).journal(id);
			for (String name : NAMES) {
				all.add(String.valueOf(journaled(name)));
				for (Journal.Replaced replaced : journal.replaced(
						path(name))) {
					all.add(replaced.text().toString());
				}
				ProjectFiles.Content content = store.read(path(name));
				if (content != null) {
					all.add(new String(content.bytes(),
							StandardCharsets.UTF_8));
				}
			}
			return all;
		}

		// Note, of each document in memory, the text it has, and that
		// the project tells it as held when it does.
		private void note(Map<Document, List<String>> held,
				Map<Document, List<String>> had) {
			for (String name : NAMES) {
				Document document = project.document(path(name));
				if (document == null) {
					continue;
				}
				String text = document.text().toString();
				add(had, document, text);
				if (document.isModified() && project.settled(path(name))) {
					add(held, document, text);
				}
			}
		}
	}

	private static void add(Map<Document, List<String>> texts,
			Document document, String text) {
		List<String> list = texts.computeIfAbsent(document,
				key -> new ArrayList<>());
		if (list.isEmpty() || !list.get(list.size() - 1).equals(text)) {
			list.add(text);
		}
	}

	// Run scenario once for each moment the process can die at: after
	// no step, after one, and so on until nothing is left to do. Return
	// how many moments there were.
	private int dieAfterEveryStep(Scenario scenario) throws IOException {
		for (int steps = 0; ; ++steps) {
			World world = new World();
			scenario.setUp(world);
			Map<Document, List<String>> held = new HashMap<>();
			Map<Document, List<String>> had = new HashMap<>();
			world.note(held, had);
			scenario.act(world);
			world.note(held, had);
			int taken = 0;
			while (taken < steps && (world.steps.worker.step() ||
					world.steps.main.step() || world.steps.later.step())) {
				++taken;
				world.note(held, had);
			}
			world.die();
			world.steps.settle();
			Set<String> all = world.everywhere();
			for (Map.Entry<Document, List<String>> entry : held.entrySet()) {
				List<String> texts = entry.getValue();
				String newest = texts.get(texts.size() - 1);
				// What it had from that text on: typed in that order,
				// or as it was seen.
				List<String> since = world.typed.getOrDefault(
						entry.getKey(), List.of());
				if (!since.contains(newest)) {
					since = had.get(entry.getKey());
				}
				boolean found = false;
				for (int i = since.lastIndexOf(newest); i >= 0 &&
						i < since.size(); ++i) {
					found |= all.contains(since.get(i));
				}
				assertTrue("dies after " + steps + " steps: \"" + newest +
						"\" was held, and neither it nor a later one of " +
						since + " is among " + all, found);
			}
			if (taken < steps) {
				return steps;
			}
		}
	}

	@Test
	public void renameOneSaveAndReloadTheOtherInConflict()
			throws IOException {
		assertEquals(35, dieAfterEveryStep(new Scenario() {
			@Override
			public void setUp(World world) {
				world.store.put("c.glsl", "stored c");
				world.open();
				world.load("c.glsl");
				world.load("a.glsl");
				world.type("a.glsl", "a1");
				world.type("c.glsl", "c1");
				world.flush();
				world.store.put("a.glsl", "theirs a");
				world.refresh();
				assertNotNull(world.project.conflict(path("a.glsl")));
			}

			@Override
			public void act(World world) {
				OpenProject project = world.project;
				project.rename(path("c.glsl"), path("d.glsl"), moved -> {
				});
				world.type("a.glsl", "a2");
				project.save(true, outcomes -> {
				});
				world.type("c.glsl", "c2");
				project.resolve(path("a.glsl"), OpenProject.Choice.RELOAD,
						project.seen(path("a.glsl")), problem -> {
						});
				project.persist();
			}
		}));
	}

	@Test
	public void renameAFolderOfTwoUnsavedFilesOneOfThemKept()
			throws IOException {
		assertEquals(13, dieAfterEveryStep(new Scenario() {
			@Override
			public void setUp(World world) {
				world.store.put("f/x.glsl", "stored x");
				world.store.put("f/y.glsl", "stored y");
				world.open();
				world.load("f/x.glsl");
				world.load("f/y.glsl");
				world.type("f/x.glsl", "x1");
				world.type("f/y.glsl", "y1");
				world.flush();
				world.store.put("f/y.glsl", "theirs y");
				world.refresh();
				world.project.resolve(path("f/y.glsl"),
						OpenProject.Choice.KEEP,
						world.project.seen(path("f/y.glsl")));
				world.steps.settle();
			}

			@Override
			public void act(World world) {
				world.project.rename(path("f"), path("g"), moved -> {
				});
				world.type("f/x.glsl", "x2");
				world.project.persist();
				world.project.save(false, outcomes -> {
				});
			}
		}));
	}

	@Test
	public void reloadOneWhileTheOtherIsTypedInThenRenameIt()
			throws IOException {
		assertEquals(35, dieAfterEveryStep(new Scenario() {
			@Override
			public void setUp(World world) {
				world.store.put("c.glsl", "stored c");
				world.open();
				world.load("c.glsl");
				world.load("a.glsl");
				world.type("a.glsl", "a1");
				world.type("c.glsl", "c1");
				world.flush();
				world.store.put("a.glsl", "theirs a");
				world.refresh();
			}

			@Override
			public void act(World world) {
				OpenProject project = world.project;
				project.resolve(path("a.glsl"), OpenProject.Choice.RELOAD,
						project.seen(path("a.glsl")), problem -> {
						});
				world.type("c.glsl", "c2");
				project.persist();
				world.type("c.glsl", "c3");
				project.rename(path("a.glsl"), path("b.glsl"), moved -> {
				});
				project.flush(() -> {
				});
			}
		}));
	}

	@Test
	public void filesLeaveMemoryWhileOneIsRenamedAndInConflict()
			throws IOException {
		assertEquals(20, dieAfterEveryStep(new Scenario() {
			@Override
			public void setUp(World world) {
				// No room for a document that is not shown.
				world.budget = 1;
				world.store.put("c.glsl", "stored c");
				world.open();
				world.load("a.glsl");
				world.type("a.glsl", "a1");
				world.flush();
				world.load("c.glsl");
				world.type("c.glsl", "c1");
				world.flush();
				world.store.put("a.glsl", "theirs a");
			}

			@Override
			public void act(World world) {
				OpenProject project = world.project;
				project.refresh(reread -> {
				});
				project.rename(path("a.glsl"), path("b.glsl"), moved -> {
				});
				project.load(path("b.glsl"), ProjectFiles.UNKNOWN,
						problem -> {
						});
				project.show(path("c.glsl"));
				world.type("c.glsl", "c2");
				project.persist();
			}
		}));
	}

	@Test
	public void whatReloadsReplacedIsWithinReachAfterLeavingMemoryTwice()
			throws IOException {
		World world = new World();
		world.budget = 1;
		world.store.put("c.glsl", "stored c");
		world.open();
		world.load("a.glsl");
		world.type("a.glsl", "a1");
		world.flush();
		world.store.put("a.glsl", "theirs a");
		world.refresh();
		reload(world, "a.glsl");
		world.type("a.glsl", "a2");
		world.flush();

		// Another file is shown: the first leaves memory, unsaved.
		world.load("c.glsl");
		assertNull(world.project.document(path("a.glsl")));
		assertTrue(world.project.unsaved().contains(path("a.glsl")));
		world.type("c.glsl", "c1");
		world.flush();

		// Back: its text, and undo to what the reload replaced.
		Document a = world.load("a.glsl");
		assertNull(world.project.document(path("c.glsl")));
		assertEquals("a2", a.text().toString());
		assertTrue(undoReaches(a, "a1"));

		// Reloaded again, it leaves memory again, and the process dies.
		world.store.put("a.glsl", "theirs a 2");
		world.refresh();
		reload(world, "a.glsl");
		world.load("c.glsl");
		assertNull(world.project.document(path("a.glsl")));
		world.die();

		a = world.load("a.glsl");
		assertEquals("theirs a 2", a.text().toString());
		assertTrue(undoReaches(a, "a2"));
		assertTrue(undoReaches(a, "a1"));
		assertEquals("c1", world.journaled("c.glsl"));
	}

	private static void reload(World world, String name) {
		List<String> problems = new ArrayList<>();
		world.project.reload(path(name), problems::add);
		world.steps.settle();
		assertEquals(1, problems.size());
		assertNull(problems.get(0));
	}

	// Return whether undo of document leads to text; it is left as it
	// was.
	private static boolean undoReaches(Document document, String text) {
		int undone = 0;
		boolean found = document.text().toString().equals(text);
		for (; !found && document.canUndo(); ++undone) {
			document.undo();
			found = document.text().toString().equals(text);
		}
		while (undone-- > 0) {
			document.redo();
		}
		return found;
	}
}
