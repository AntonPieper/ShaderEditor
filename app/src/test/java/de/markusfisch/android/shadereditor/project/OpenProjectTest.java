package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.OpenProject.Conflict;
import de.markusfisch.android.shadereditor.project.OpenProject.Failed;
import de.markusfisch.android.shadereditor.project.OpenProject.Outcome;
import de.markusfisch.android.shadereditor.project.OpenProject.Saved;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

public class OpenProjectTest {
	private static final String SHADER = """
			#version 300 es
			precision mediump float;
			out vec4 color;
			void main() {
				color = vec4(1.0);
			}
			""";
	private static final Object NOTHING = new Object();

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private final Steps steps = new Steps();
	private final Steps.Queue worker = steps.worker;
	private final Steps.Queue main = steps.main;
	private File root;
	private File sessionsDir;
	private String id;
	private long budget = OpenProject.MAX_BUDGET;

	@Before
	public void setUp() throws IOException {
		root = temp.newFolder("project");
		sessionsDir = new File(temp.getRoot(), "sessions");
		id = new ProjectSessions(sessionsDir).open(
				new Locator(Kind.DIRECTORY, root.getPath()));
	}

	@Test
	public void runsNothingWithoutChoiceManifestOrMainU10() throws IOException {
		assertNull(open().running());
		write("a.glsl", SHADER);
		write("b.glsl", SHADER);

		OpenProject project = open();

		assertNull(project.running());
		assertNull(prepare(project));
	}

	@Test
	public void runsMainWithoutManifestU10() throws IOException {
		write("main.glsl", SHADER);
		write("other.glsl", SHADER);

		assertEquals(path("main.glsl"), open().running());
	}

	@Test
	public void runsTheManifestEntryOverMainU10() throws IOException {
		write("main.glsl", SHADER);
		write("passes/b.glsl", SHADER);
		write("shader-project.json", "{\"entry\": \"passes/b.glsl\"}");

		assertEquals(path("passes/b.glsl"), open().running());
	}

	@Test
	public void runsMainBesideAnInvalidManifestU10() throws IOException {
		write("main.glsl", SHADER);
		write("shader-project.json", "{\"projectVersion\": 2}");

		OpenProject project = open();

		assertEquals(path("main.glsl"), project.running());
		Preparation preparation = prepare(project);
		assertNull(preparation.plan());
		assertEquals(ProjectManifest.FILE,
				preparation.diagnostics().get(0).file());
	}

	@Test
	public void remembersRunThisFileOverManifestU10() throws IOException {
		write("main.glsl", SHADER);
		write("b.glsl", SHADER);
		write("c.glsl", SHADER);
		String manifest = "{\"entry\": \"b.glsl\"}";
		write("shader-project.json", manifest);
		OpenProject project = open();

		project.runThisFile(path("c.glsl"));
		settle();

		assertEquals(path("c.glsl"), project.running());
		assertEquals(path("c.glsl"), open().running());
		assertEquals(manifest, read("shader-project.json"));
		write("shader-project.json", "{\"entry\": \"main.glsl\"}");
		assertEquals(path("c.glsl"), open().running());
	}

	@Test
	public void forgetsAChosenFileThatIsGoneU10() throws IOException {
		write("main.glsl", SHADER);
		write("c.glsl", SHADER);
		open().runThisFile(path("c.glsl"));
		settle();

		assertTrue(new File(root, "c.glsl").delete());

		assertEquals(path("main.glsl"), open().running());
	}

	@Test
	public void findsWhatRunsWithoutReadingAFileWhole() {
		List<Long> limits = new ArrayList<>();
		ProjectFiles huge = new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				throw new AssertionError("read all of " + path);
			}

			@Override
			public Read read(ProjectPath path, long limit) {
				limits.add(limit);
				return new Failed(ProjectFiles.TOO_LARGE);
			}
		};

		assertNull(open(huge).running());

		assertEquals(List.of(Document.MAX_BYTES, Document.MAX_BYTES),
				limits);
	}

	@Test
	public void buildAndCopyReportAFileOverTheLimitWithoutReadingIt()
			throws IOException {
		write("main.glsl", "#include \"big.glsl\"\n" + SHADER);
		ProjectPath big = path("big.glsl");
		DirectoryProjectFiles files = new DirectoryProjectFiles(root);
		OpenProject project = open(new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				assertNotEquals(big, path);
				return files.read(path);
			}

			@Override
			public Read read(ProjectPath path, long limit) {
				return big.equals(path)
						? new Failed(ProjectFiles.TOO_LARGE)
						: files.read(path, limit);
			}
		});
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;

		Preparation preparation = prepare(project);
		Map<ProjectPath, String> problems = await(done -> project.copyTo(
				destination, List.of(big, path("main.glsl")), done));

		assertNull(preparation.plan());
		assertTrue(preparation.diagnostics().get(0).message(),
				preparation.diagnostics().get(0).message().contains(
						"big.glsl: it is too large"));
		assertEquals("Could not read big.glsl: it is too large",
				problems.get(big));
		assertNull(problems.get(path("main.glsl")));
		assertNull(destination.text("big.glsl"));
	}

	@Test
	public void editingAnotherFileKeepsWhatRunsU10() throws IOException {
		write("main.glsl", "#include \"lib/common.glsl\"\n" + SHADER);
		write("lib/common.glsl", "// common\n");
		OpenProject project = open();
		document(project, "lib/common.glsl");
		project.show(path("lib/common.glsl"));

		edit(project, "lib/common.glsl", "// edited\n");

		assertEquals(path("main.glsl"), project.running());
		Preparation preparation = prepare(project);
		assertEquals("// edited\n", built(preparation, "lib/common.glsl"));
		assertEquals("// common\n", read("lib/common.glsl"));
	}

	@Test
	public void doesNotDeliverABuildOfAnOlderText() throws IOException {
		write("main.glsl", SHADER);
		OpenProject project = open();
		document(project, "main.glsl");
		edit(project, "main.glsl", SHADER + "// one\n");
		List<OpenProject.Build> builds = new ArrayList<>();

		project.prepare(BuiltinNames.ALL, builds::add);
		edit(project, "main.glsl", SHADER + "// two\n");
		settle();

		assertTrue(builds.isEmpty());
		assertEquals(SHADER + "// two\n",
				built(prepare(project), "main.glsl"));
	}

	@Test
	public void refreshFindsAChangedManifestU10() throws IOException {
		write("main.glsl", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		assertEquals(path("main.glsl"), project.running());

		write("shader-project.json", "{\"entry\": \"b.glsl\"}");
		refresh(project);

		assertEquals(path("b.glsl"), project.running());
	}

	@Test
	public void switchingFilesKeepsUnsavedTextAndSeparateHistories()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		Document b = document(project, "b.glsl");
		a.type(1, 1, Text.of("!"));
		b.type(1, 1, Text.of("?"));
		List<Runnable> posted = new ArrayList<>();
		project.setListener(posted::add, () -> {
		});

		assertSame(a, project.show(path("a.glsl")));
		assertSame(b, project.show(path("b.glsl")));
		assertSame(a, project.show(path("a.glsl")));

		assertEquals(0, drain(posted));
		assertEquals(1, a.revision());
		assertEquals("a!", a.text().toString());
		assertEquals("b?", b.text().toString());
		assertEquals(Set.of(path("a.glsl"), path("b.glsl")),
				project.unsaved());
		a.undo();
		assertEquals("a", a.text().toString());
		assertEquals("b?", b.text().toString());
		assertEquals(Set.of(path("b.glsl")), project.unsaved());
		assertEquals(1, drain(posted));
		b.undo();
		assertFalse(b.canUndo());
		assertTrue(a.canRedo());
	}

	@Test
	public void restoresDraftsIntoDocumentsAfterTheProcessDied()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		edit(project, document(project, "a.glsl"), "a!");
		edit(project, document(project, "b.glsl"), "b!");

		project.persist();
		settle();
		OpenProject restarted = open();

		assertEquals(Set.of(path("a.glsl"), path("b.glsl")),
				restarted.unsaved());
		assertNull(restarted.document(path("b.glsl")));
		Document b = document(restarted, "b.glsl");
		assertEquals("b!", b.text().toString());
		assertTrue(b.isModified());
		assertFalse(b.canUndo());
		assertEquals("a", read("a.glsl"));

		assertTrue(save(restarted, false).get(path("a.glsl")) instanceof
				Saved);
		assertEquals("a!", read("a.glsl"));
		assertNull(restarted.document(path("a.glsl")));
		assertNull(journaled("a.glsl"));
		assertTrue(kept().isEmpty());
	}

	@Test
	public void reopensTheTextOfTheLastCompleteRecordWhereverItWasCut()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		List<Long> ends = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		for (String text : List.of("one", "one two", "three two",
				"three two\nfour")) {
			edit(project, a, text);
			flush(project);
			ends.add(file.length());
			texts.add(text);
		}
		byte[] full = Files.readAllBytes(file.toPath());
		assertTrue("appended, not rewritten", ends.get(1) > ends.get(0));

		for (long cut = ends.get(0); cut <= full.length; ++cut) {
			Files.write(file.toPath(), Arrays.copyOf(full, (int) cut));
			int complete = 0;
			while (complete + 1 < ends.size() &&
					ends.get(complete + 1) <= cut) {
				++complete;
			}
			OpenProject reopened = open();
			assertEquals("cut at " + cut, Set.of(path("a.glsl")),
					reopened.unsaved());
			assertEquals("cut at " + cut, texts.get(complete),
					text(reopened, "a.glsl"));
		}
	}

	@Test
	public void damagedJournalKeepsWhatIsGoodElseOpensTheStoredFile()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		int snapshot = (int) file.length();
		a.type(4, 4, Text.of("!"));
		flush(project);
		byte[] bytes = Files.readAllBytes(file.toPath());
		assertTrue(bytes.length > snapshot);

		// The checksum of the record appended, then of the snapshot.
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		assertEquals("mine", text(open(), "a.glsl"));
		bytes[snapshot - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		OpenProject reopened = open();
		Document stored = document(reopened, "a.glsl");
		flush(reopened);

		assertEquals("stored", stored.text().toString());
		assertFalse(stored.isModified());
		assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
	}

	@Test
	public void unreadableJournalIsToldUntilDismissed() throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);

		OpenProject restarted = open();
		assertNull(load(restarted, "a.glsl"));
		String reason = restarted.unreadable(path("a.glsl"));

		assertNotNull(reason);
		assertTrue(reason, reason.contains("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), restarted.unsaved());
		assertTrue(save(restarted, true).isEmpty());
		OpenProject again = open();
		assertEquals(Set.of(path("a.glsl")), again.unsaved());
		assertTrue(save(again, true).get(path("a.glsl")) instanceof Failed);
		assertNull(load(again, "a.glsl"));
		again.dismiss(path("a.glsl"));
		settle();
		assertNull(again.unreadable(path("a.glsl")));
		assertTrue(again.unsaved().isEmpty());
		assertFalse(file.exists());
		assertTrue(kept().isEmpty());
	}

	@Test
	public void dismissLeavesTheJournalWrittenSince() throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		OpenProject restarted = open();
		edit(restarted, document(restarted, "a.glsl"), "new work");

		// The snapshot is on its way when the old notice is dismissed.
		restarted.persist();
		restarted.dismiss(path("a.glsl"));
		settle();

		assertEquals("new work", journaled("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), restarted.unsaved());
		assertNull(restarted.unreadable(path("a.glsl")));
		assertNull(restarted.unprotected());
	}

	@Test
	public void closedProjectLeavesTheJournalOfTheNextOne()
			throws IOException {
		OpenProject first = open();
		Document old = document(first, "a.glsl", "stored");
		edit(first, old, "first");
		first.close();
		settle();
		OpenProject second = open();
		edit(second, document(second, "a.glsl"), "second");
		second.persist();
		settle();

		// Whoever kept the document of the closed project undoes in it.
		old.undo();
		first.persist();
		first.save(true, outcomes -> {
		});
		settle();
		steps.later.run();
		settle();

		assertEquals("second", journaled("a.glsl"));
		assertEquals("stored", read("a.glsl"));
		assertNull(first.document(path("a.glsl")));
	}

	@Test
	public void projectReplacedWithoutClosingWritesNoJournal()
			throws IOException {
		OpenProject first = open();
		Document old = document(first, "a.glsl", "stored");
		edit(first, old, "first");
		flush(first);
		OpenProject second = open();
		edit(second, document(second, "a.glsl"), "second");
		flush(second);

		old.undo();
		first.persist();
		settle();

		assertEquals("second", journaled("a.glsl"));
		assertNotNull(first.unprotected());
	}

	@Test
	public void removingTheRecentOfAnOpenProjectKeepsItsSession()
			throws IOException {
		ProjectSessions sessions = new ProjectSessions(sessionsDir);
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");

		// Nothing is in the journal yet when the recent is removed.
		project.persist();
		assertTrue(sessions.remove(id));
		settle();

		assertNotNull(sessions.locator(id));
		assertEquals(Set.of(path("a.glsl")), sessions.unsaved(id));
		project.close();
		settle();
		assertTrue(sessions.remove(id));
		assertNotNull(sessions.locator(id));
		assertEquals("mine", text(open(), "a.glsl"));
	}

	@Test
	public void deletesDraftsOfTheFormatBeforeAndUnfinishedSnapshots()
			throws IOException {
		File drafts = new File(new File(sessionsDir, id), "drafts");
		assertTrue(drafts.mkdirs());
		File old = new File(drafts, "0123.draft");
		File half = new File(drafts, ".journal-1.tmp");
		Files.write(old.toPath(), new byte[]{1});
		Files.write(half.toPath(), new byte[]{2});

		OpenProject project = open();
		settle();

		assertFalse(old.exists());
		assertFalse(half.exists());
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void appendTheJournalDoesNotContinueIsSentAsASnapshot()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "one");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		assertTrue(file.delete());

		a.type(3, 3, Text.of(" two"));
		flush(project);

		assertEquals("one two", journaled("a.glsl"));
		assertNull(project.unprotected());
	}

	@Test
	public void savesEachFileInPlaceAndKeepsFailuresForRetry()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		FailingFiles store = new FailingFiles(new DirectoryProjectFiles(root));
		store.failing = path("b.glsl");
		OpenProject project = open(store);
		Document a = document(project, "a.glsl");
		Document b = document(project, "b.glsl");
		edit(project, a, "a!");
		edit(project, b, "b!");

		Map<ProjectPath, Outcome> outcomes = save(project, true);

		assertTrue(outcomes.get(path("a.glsl")) instanceof Saved);
		assertEquals(new Failed("the card was removed"),
				outcomes.get(path("b.glsl")));
		assertEquals("a!", read("a.glsl"));
		assertEquals("b", read("b.glsl"));
		assertFalse(a.isModified());
		assertTrue(b.isModified());
		assertEquals(Set.of(path("b.glsl")), project.unsaved());
		assertEquals(Set.of(path("b.glsl")), kept());

		store.failing = null;
		outcomes = save(project, true);

		assertEquals(Set.of(path("b.glsl")), outcomes.keySet());
		assertEquals("b!", read("b.glsl"));
		assertTrue(project.unsaved().isEmpty());
		assertTrue(kept().isEmpty());
		edit(project, a, "a!!");
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		a.undo();
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void savesTheTextADocumentHadWhenSaveWasAsked()
			throws IOException {
		write("a.glsl", "a");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "one");
		Map<?, ?>[] outcomes = new Map<?, ?>[1];

		project.save(false, saved -> outcomes[0] = saved);
		edit(project, a, "two");
		settle();

		assertTrue(outcomes[0].get(path("a.glsl")) instanceof Saved);
		assertEquals("one", read("a.glsl"));
		assertTrue(a.isModified());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		a.undo();
		assertFalse(a.isModified());
	}

	@Test
	public void reloadsFromFileOnRequestAsOneUndoableChange()
			throws IOException {
		OpenProject project = editedElsewhere();
		Document a = project.document(path("a.glsl"));

		assertNull(this.<String>await(done ->
				project.reload(path("a.glsl"), done)));

		assertSame(a, project.document(path("a.glsl")));
		assertEquals("theirs", a.text().toString());
		assertTrue(project.unsaved().isEmpty());
		assertTrue(project.conflicts().isEmpty());
		a.undo();
		assertEquals("mine", a.text().toString());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
	}

	@Test
	public void openAgainJoinsTheFirstSoAChangeElsewhereConflicts()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		List<String> problems = new ArrayList<>();
		project.load(path("a.glsl"), ProjectFiles.UNKNOWN, problems::add);
		worker.run();
		write("a.glsl", "theirs");
		project.load(path("a.glsl"), ProjectFiles.UNKNOWN, problems::add);
		worker.run();
		main.run();
		assertEquals(Arrays.asList(null, null), problems);
		Document a = project.document(path("a.glsl"));
		assertEquals("stored", a.text().toString());

		edit(project, a, "mine");

		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
		assertEquals("mine", a.text().toString());
	}

	@Test
	public void reloadKeepsTypingThatCameBeforeItsResult()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "mine");
		project.persist();
		settle();
		write("a.glsl", "theirs");
		project.reload(path("a.glsl"), problem -> {
		});
		worker.run();

		edit(project, a, "later");
		settle();

		assertEquals("later", a.text().toString());
		assertTrue(a.isModified());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
	}

	@Test
	public void listenerThatEditsWhileTheStoredTextIsTakenSaves()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		write("a.glsl", "theirs");
		a.addListener(new Document.Listener() {
			@Override
			public void onUpdate() {
				if (a.text().toString().equals("theirs")) {
					edit(project, a, "mine");
					project.persist();
				}
			}
		});

		assertEquals(Set.of(path("a.glsl")), refresh(project));

		assertEquals("mine", a.text().toString());
		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Saved);
		assertEquals("mine", read("a.glsl"));
	}

	@Test
	public void reloadOfAFileGoneKeepsTheDraftAndTheText()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "mine");
		project.persist();
		assertTrue(new File(root, "a.glsl").delete());

		assertNotNull(this.<String>await(done ->
				project.reload(path("a.glsl"), done)));

		assertEquals("mine", a.text().toString());
		assertTrue(a.isModified());
		assertEquals("mine", text(open(), "a.glsl"));
	}

	@Test
	public void copyKeepsTextTypedBeforeItsResult() throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "mine");
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;
		project.copyTo(destination, List.of(path("a.glsl")), problems -> {
		});
		worker.run();

		edit(project, a, "later");
		settle();
		project.persist();
		settle();

		assertEquals("mine", destination.text("a.glsl"));
		assertEquals("later", a.text().toString());
		assertTrue(a.isModified());
		OpenProject reopened = open();
		assertEquals("later", text(reopened, "a.glsl"));
		assertEquals(Set.of(path("a.glsl")), reopened.unsaved());
	}

	@Test
	public void typingWithinTheDelayWritesTheJournalOnce()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "0");
		steps.later.run();
		int once = worker.size();
		settle();

		for (int i = 1; i <= 10; ++i) {
			edit(project, a, "" + i);
		}
		steps.later.run();

		assertEquals(once, worker.size());
		settle();
		assertEquals("10", journaled("a.glsl"));
	}

	@Test
	public void flushCompletesOnlyAfterTheJournalTookTheText()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		List<String> flushed = new ArrayList<>();

		project.flush(() -> flushed.add(journaled("a.glsl")));
		main.run();

		assertTrue(flushed.isEmpty());
		settle();
		assertEquals(List.of("mine"), flushed);
	}

	@Test
	public void failedJournalWriteIsReportedAndRetriedOnTheNextFlush()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		File drafts = new File(new File(sessionsDir, id), "drafts");
		assertTrue(!drafts.exists() || drafts.delete());
		Files.write(drafts.toPath(), new byte[]{1});

		flush(project);

		assertNotNull(project.unprotected());
		assertTrue(drafts.delete());
		flush(project);
		assertNull(project.unprotected());
		assertEquals("mine", journaled("a.glsl"));
	}

	@Test
	public void keepingEditsOverOneRevisionConflictsWithTheNext()
			throws IOException {
		OpenProject project = editedElsewhere();
		String seen = project.seen(path("a.glsl"));
		assertNotNull(seen);

		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP, seen);
		settle();
		write("a.glsl", "again");

		assertTrue(save(project, true).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("again", read("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP, seen);
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
	}

	@Test
	public void cleanFileDeletedElsewhereKeepsItsTextAndIsNotBuilt()
			throws IOException {
		write(OpenProject.MAIN.toString(), SHADER);
		OpenProject project = open();
		Document main = document(project, OpenProject.MAIN.toString());
		assertTrue(new File(root, OpenProject.MAIN.toString()).delete());

		refresh(project);

		assertEquals(FileTexts.Found.MISSING, project.found(OpenProject.MAIN));
		assertSame(main, project.document(OpenProject.MAIN));
		assertEquals(SHADER, main.text().toString());
		Preparation preparation = prepare(project);
		assertTrue(preparation == null || preparation.plan() == null);
	}

	@Test
	public void opensASessionOnceUntilItIsClosed() {
		LiveProjects live = live();
		OpenProject project = open(live, this::folder);

		assertSame(project, open(live, this::folder));
		assertEquals(OpenProject.Access.AVAILABLE,
				project.storage().access());
		project.close();
		assertNotSame(project, open(live, this::folder));
	}

	@Test
	public void reopeningAfterCloseReadsWhatTheProjectWroteLast()
			throws IOException {
		LiveProjects live = live();
		OpenProject project = open(live, this::folder);
		edit(project, document(project, "a.glsl", "stored"), "mine");
		project.close();
		OpenProject[] reopened = {null};

		live.open(id, this::folder, opened -> reopened[0] = opened);
		settle();

		assertEquals(Set.of(path("a.glsl")), reopened[0].unsaved());
		assertEquals("mine", text(reopened[0], "a.glsl"));
	}

	@Test
	public void sessionWhoseFilesAreGoneOpensWithItsDrafts()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);

		OpenProject gone = open(live(), locator ->
				new SafProjects.Opened(null, null, "the folder is gone"));

		assertEquals(new OpenProject.Storage(OpenProject.Access.UNAVAILABLE,
				"the folder is gone"), gone.storage());
		assertEquals(Set.of(path("a.glsl")), gone.unsaved());
		assertEquals("mine", text(gone, "a.glsl"));
	}

	@Test
	public void loneDocumentRunsAsItsOwnEntry() {
		MemoryProjectFiles store = new MemoryProjectFiles();
		store.put("standalone.frag", SHADER);

		OpenProject project = OpenProject.open(id, store,
				path("standalone.frag"), null,
				new ProjectSessions(sessionsDir), worker, main, steps.timers,
				budget);

		assertEquals(path("standalone.frag"), project.running());
		assertNotNull(prepare(project).plan());
	}

	@Test
	public void refreshFindsWhatRunsFromTheManifestItTook()
			throws IOException {
		write(ProjectManifest.FILE.toString(), "{\"entry\":\"a.glsl\"}");
		write("a.glsl", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		document(project, ProjectManifest.FILE.toString());
		assertEquals(path("a.glsl"), project.running());
		write(ProjectManifest.FILE.toString(), "{\"entry\":\"b.glsl\"}");

		refresh(project);

		assertEquals(path("b.glsl"), project.running());
	}

	@Test
	public void refreshDoesNotUndoAChoiceMadeMeanwhile()
			throws IOException {
		write("main.glsl", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		project.refresh(reread -> {
		});
		// Up to the entry found waiting on main.
		worker.run();
		main.run();
		worker.run();
		assertEquals(1, main.size());

		project.runThisFile(path("b.glsl"));
		settle();

		assertEquals(path("b.glsl"), project.running());
		assertEquals(path("b.glsl"),
				new ProjectSessions(sessionsDir).running(id));
	}

	@Test
	public void buildAskedBeforeAnotherEntryIsNotDelivered()
			throws IOException {
		write("main.glsl", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		List<OpenProject.Build> builds = new ArrayList<>();
		project.prepare(BuiltinNames.ALL, builds::add);
		worker.run();

		project.runThisFile(path("b.glsl"));
		settle();

		assertTrue(builds.isEmpty());
		assertTrue(project.used().isEmpty());
		OpenProject.Build build = build(project);
		assertEquals(List.of(new OpenProject.Used(path("b.glsl"), false)),
				project.used());
		project.setDiagnostics(build, List.of(ProjectDiagnostic.error("e")));
		assertEquals(1, project.errors());
	}

	@Test
	public void closeKeepsTheTextAndIgnoresWhatArrivesLater()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		List<String> loaded = new ArrayList<>();
		project.load(path("b.glsl"), ProjectFiles.UNKNOWN, loaded::add);

		project.close();
		settle();
		steps.later.run();

		assertTrue(loaded.isEmpty());
		assertEquals(0, worker.size());
		assertEquals("mine", journaled("a.glsl"));
	}

	@Test
	public void reloadOfARevisionNoLongerStoredKeepsTheText()
			throws IOException {
		OpenProject project = editedElsewhere();
		String seen = project.seen(path("a.glsl"));
		write("a.glsl", "again");

		project.resolve(path("a.glsl"), OpenProject.Choice.RELOAD, seen);
		settle();

		assertEquals("mine", project.document(path("a.glsl")).text()
				.toString());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
	}

	@Test
	public void copyWritesTheCurrentTextsAndLeavesTheProjectAsItIs()
			throws IOException {
		write("b.glsl", "stored b");
		write("c.glsl", "stored c");
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored a");
		edit(project, a, "mine");
		flush(project);
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;
		destination.put("c.glsl", "taken");

		Map<ProjectPath, String> problems = await(done -> project.copyTo(
				destination, List.of(path("a.glsl"), path("b.glsl"),
						path("c.glsl"), path("gone.glsl")), done));

		assertEquals("mine", destination.text("a.glsl"));
		assertEquals("stored b", destination.text("b.glsl"));
		assertEquals("taken", destination.text("c.glsl"));
		assertNull(problems.get(path("a.glsl")));
		assertNull(problems.get(path("b.glsl")));
		assertNotNull(problems.get(path("c.glsl")));
		assertNotNull(problems.get(path("gone.glsl")));
		assertTrue(a.isModified());
		assertEquals("mine", journaled("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		assertEquals("stored a", read("a.glsl"));
	}

	@Test
	public void copiesTheUnsavedTextOfASessionWhoseFilesAreGone()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		OpenProject gone = open(live(), locator ->
				new SafProjects.Opened(null, null, "the folder is gone"));
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;

		Map<ProjectPath, String> problems = await(done -> gone.copyTo(
				destination, List.of(path("a.glsl")), done));

		assertNull(problems.get(path("a.glsl")));
		assertEquals("mine", destination.text("a.glsl"));
	}

	@Test
	public void keepsEditsUntilAnExplicitSave() throws IOException {
		OpenProject project = editedElsewhere();

		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP,
				project.seen(path("a.glsl")));

		assertTrue(project.conflicts().isEmpty());
		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Conflict);
		assertTrue(project.document(path("a.glsl")).isModified());
		assertEquals("theirs", read("a.glsl"));
		assertTrue(save(project, true).get(path("a.glsl")) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void savesNothingOfAFileInConflict() throws IOException {
		OpenProject project = editedElsewhere();

		assertTrue(save(project, true).get(path("a.glsl")) instanceof
				Conflict);

		assertEquals("theirs", read("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
	}

	@Test
	public void keptEditsStayKeptAfterARestart() throws IOException {
		OpenProject project = editedElsewhere();
		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP,
				project.seen(path("a.glsl")));
		flush(project);

		OpenProject restarted = open();

		assertTrue(restarted.conflicts().isEmpty());
		assertTrue(save(restarted, false).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
		assertTrue(save(restarted, true).get(path("a.glsl")) instanceof
				Saved);
		assertEquals("mine", read("a.glsl"));
		assertTrue(kept().isEmpty());
	}

	@Test
	public void keepingEditsOfAFileDeletedElsewhereCreatesItAgain()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		edit(project, document(project, "a.glsl"), "mine");
		assertTrue(new File(root, "a.glsl").delete());
		refresh(project);
		assertEquals("the file was deleted",
				project.conflicts().get(path("a.glsl")));
		assertNull(project.seen(path("a.glsl")));

		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP, null);

		assertTrue(save(project, true).get(path("a.glsl")) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
	}

	@Test
	public void fileChangedWhileTheProcessWasDeadIsInConflict()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		write("a.glsl", "theirs");

		OpenProject restarted = open();

		assertEquals(Set.of(path("a.glsl")), restarted.conflicts().keySet());
		assertNotNull(restarted.seen(path("a.glsl")));
		assertEquals("mine", text(restarted, "a.glsl"));
		assertTrue(save(restarted, true).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
	}

	@Test
	public void buildsAFileNotInMemoryFromItsJournal() throws IOException {
		write("main.glsl", "#include \"lib.glsl\"\n" + SHADER);
		write("lib.glsl", "// lib\n");
		OpenProject project = open();
		edit(project, document(project, "lib.glsl"), "// edited\n");
		flush(project);

		OpenProject restarted = open();

		assertNull(restarted.document(path("lib.glsl")));
		assertEquals("// edited\n", built(prepare(restarted), "lib.glsl"));
		assertEquals("// lib\n", read("lib.glsl"));
	}

	@Test
	public void refreshGivesCleanDocumentsTheChangedFile()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		Document b = document(project, "b.glsl");
		edit(project, b, "b!");
		write("a.glsl", "a2");

		assertEquals(Set.of(path("a.glsl")), refresh(project));

		assertSame(a, project.document(path("a.glsl")));
		assertEquals("a2", a.text().toString());
		assertFalse(a.isModified());
		assertEquals("b!", b.text().toString());
		assertTrue(project.conflicts().isEmpty());
		assertTrue(refresh(project).isEmpty());
		edit(project, a, "a3");
		assertTrue(save(project, false).get(path("a.glsl")) instanceof Saved);
		assertEquals("a3", read("a.glsl"));
		a.undo();
		a.undo();
		assertEquals("a", a.text().toString());
		assertTrue(a.isModified());
	}

	@Test
	public void refreshForgetsWhatListingsTold() {
		FailingFiles store = new FailingFiles(new DirectoryProjectFiles(root));
		OpenProject project = open(store);

		refresh(project);

		assertEquals(1, store.forgotten);
	}

	@Test
	public void budgetHoldsAfterUndoToClean() throws IOException {
		budget = 3 * OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		List<Document> edited = new ArrayList<>();
		for (int i = 0; i < 5; ++i) {
			Document document = document(project, i + ".glsl", "" + i);
			edit(project, document, "edited " + i);
			edited.add(document);
		}
		project.show(path("4.glsl"));
		// Kept while the journal does not hold them.
		assertTrue(used(project) > budget);

		for (Document document : edited) {
			document.undo();
		}
		flush(project);

		assertTrue(used(project) <= budget);
		assertTrue(project.unsaved().isEmpty());
		assertNull(project.document(path("0.glsl")));
	}

	@Test
	public void modifiedFileDroppedFromMemoryReopensAsItWas()
			throws IOException {
		budget = OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = editedElsewhere();
		Document a = project.document(path("a.glsl"));
		flush(project);
		document(project, "b.glsl", "b");
		project.show(path("b.glsl"));
		document(project, "c.glsl", "c");

		assertNull(project.document(path("a.glsl")));
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		Document reopened = document(project, "a.glsl");
		assertNotSame(a, reopened);
		assertEquals("mine", reopened.text().toString());
		assertTrue(reopened.isModified());
		assertTrue(save(project, true).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
	}

	@Test
	public void undoAfterReopeningEqualsUndoBeforeTheDrop()
			throws IOException {
		budget = OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		Document a = document(project, "a.glsl", "one");
		edit(project, a, "two");
		edit(project, a, "three");
		a.select(1, 2);
		flush(project);
		document(project, "b.glsl", "b");
		project.show(path("b.glsl"));
		document(project, "c.glsl", "c");
		settle();
		assertNull(project.document(path("a.glsl")));

		Document reopened = document(project, "a.glsl");

		assertEquals("three", reopened.text().toString());
		assertEquals(new Document.Selection(1, 2), reopened.selection());
		assertTrue(reopened.isModified());
		reopened.undo();
		assertEquals("two", reopened.text().toString());
		reopened.undo();
		assertEquals("one", reopened.text().toString());
		assertFalse(reopened.isModified());
		assertFalse(reopened.canUndo());
	}

	@Test
	public void historySurvivesARestartForTheSameTextOnly()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "one");
		edit(project, a, "two");
		assertTrue(save(project, true).get(path("a.glsl")) instanceof Saved);
		flush(project);

		Document restarted = document(open(), "a.glsl");
		assertFalse(restarted.isModified());
		restarted.undo();
		assertEquals("one", restarted.text().toString());
		assertTrue(restarted.isModified());

		write("a.glsl", "theirs");
		Document changed = document(open(), "a.glsl");
		assertEquals("theirs", changed.text().toString());
		assertFalse(changed.canUndo());
		File history = new File(new File(sessionsDir, id), "history");
		assertEquals(0, history.list().length);
	}

	@Test
	public void dropsTheCleanDocumentsUsedLeastRecentlyFirst()
			throws IOException {
		Document sample = new Document(Text.of("e"));
		sample.replace(0, 1, Text.of("edited"));
		budget = OpenProject.cost(sample) +
				3 * OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		Document edited = document(project, "edited.glsl", "e");
		edit(project, edited, "edited");
		Document shown = document(project, "shown.glsl", "s");
		project.show(path("shown.glsl"));
		List<Document> clean = new ArrayList<>();
		for (int i = 0; i <= 4; ++i) {
			clean.add(document(project, i + ".glsl", "" + i));
		}

		assertNull(project.document(path("0.glsl")));
		for (int i = 1; i <= 4; ++i) {
			assertSame(clean.get(i), project.document(path(i + ".glsl")));
		}
		assertSame(edited, project.document(path("edited.glsl")));
		assertSame(shown, project.document(path("shown.glsl")));
		Document reopened = document(project, "0.glsl");
		assertNotSame(clean.get(0), reopened);
		assertEquals("0", reopened.text().toString());
		assertNull(project.document(path("1.glsl")));
		assertEquals(Set.of(path("edited.glsl")), project.unsaved());
	}

	@Test
	public void keepsACleanDocumentUntilItsJournalIsRemoved()
			throws IOException {
		budget = 2 * OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		Document a = document(project, "a.glsl", "a");
		edit(project, a, "a!");
		project.persist();
		settle();
		a.undo();
		for (int i = 0; i <= 4; ++i) {
			document(project, i + ".glsl", "" + i);
		}

		assertSame(a, project.document(path("a.glsl")));
		assertTrue(project.unsaved().isEmpty());
		project.persist();
		settle();
		assertNull(project.document(path("a.glsl")));
		assertTrue(kept().isEmpty());
	}

	@Test
	public void budgetIsAnEighthOfTheHeapWithinItsBounds() {
		long mib = 1024 * 1024;

		assertEquals(8 * mib, OpenProject.budget(32 * mib));
		assertEquals(16 * mib, OpenProject.budget(128 * mib));
		assertEquals(32 * mib, OpenProject.budget(512 * mib));
	}

	@Test
	public void listsOneFolderAtATimeFoldersFirst() throws IOException {
		write("zeta.glsl", "z");
		write("Alpha.png", "p");
		write("beta/deep/x.glsl", "x");
		write("lib/common.glsl", "c");
		write(".git/HEAD", "ref");
		FailingFiles store = new FailingFiles(new DirectoryProjectFiles(root));
		OpenProject project = open(store);

		OpenProject.Folder top = project.list(null);

		assertNull(top.problem());
		assertEquals(List.of("beta", "lib", "Alpha.png", "zeta.glsl"),
				names(top));
		assertTrue(top.items().get(0).folder());
		assertEquals(Collections.singletonList((ProjectPath) null),
				store.listed);
		assertEquals(List.of("deep"), names(project.list(path("beta"))));
		assertEquals(path("beta/deep/x.glsl"),
				project.list(path("beta/deep")).items().get(0).path());
		assertNotNull(project.list(path("gone")).problem());
	}

	@Test
	public void listsSizeAndTimeOfLastChange() throws IOException {
		write("main.glsl", SHADER);
		assertTrue(new File(root, "main.glsl").setLastModified(
				1_700_000_000_000L));

		OpenProject.Item item = open().list(null).items().get(0);

		assertEquals(SHADER.length(), item.size());
		assertEquals(1_700_000_000_000L, item.modified());
	}

	@Test
	public void keepsWhatTheLastBuildRead() throws IOException {
		write("main.glsl", "#include \"lib/common.glsl\"\n" +
				"#pragma se input \"noise.png\"\n" +
				"uniform sampler2D noise;\n" + SHADER);
		write("lib/common.glsl", "// common\n");
		write("noise.png", "not decoded");
		write("shader-project.json", "{}");
		write("other.glsl", SHADER);
		OpenProject project = open();
		assertEquals(List.of(), project.used());

		prepare(project);

		assertEquals(List.of(new OpenProject.Used(path("main.glsl"), false),
						new OpenProject.Used(path("lib/common.glsl"), false),
						new OpenProject.Used(path("noise.png"), true)),
				project.used());
		assertTrue(project.uses(path("lib/common.glsl")));
		assertFalse(project.uses(path("other.glsl")));
		assertFalse(project.uses(ProjectManifest.FILE));
	}

	@Test
	public void theEntryOnlyCheckedToExistIsNotUsed() throws IOException {
		write("a.glsl", SHADER);
		write("b.glsl", SHADER);
		write("shader-project.json", "{\"entry\": \"a.glsl\"}");
		OpenProject project = open();
		project.runThisFile(path("b.glsl"));
		settle();

		Preparation preparation = prepare(project);

		assertTrue(preparation.files().containsKey(path("a.glsl")));
		assertEquals(List.of(new OpenProject.Used(path("b.glsl"), false)),
				project.used());
		assertFalse(project.uses(path("a.glsl")));
	}

	@Test
	public void leavesOutFilesTheBuildCouldNotRead() throws IOException {
		write("main.glsl", "#include \"missing.glsl\"\n" + SHADER);
		OpenProject project = open();

		prepare(project);

		assertEquals(List.of(new OpenProject.Used(path("main.glsl"), false)),
				project.used());
	}

	@Test
	public void runsAGlslFileWhoseOwnTextDefinesMain() throws IOException {
		write("main.glsl", SHADER);
		write("tight.glsl", "void main(){}");
		write("lib/common.glsl", "float f() { return 1.0; }\n");
		write("declared.glsl", "void main();\n");
		write("disabled.glsl", "#if 0\nvoid main() {}\n#endif\n");
		write("commented.glsl", "// void main() {}\n");
		write("notes.txt", SHADER);
		OpenProject project = open();

		assertTrue(canRun(project, "main.glsl"));
		assertTrue(canRun(project, "tight.glsl"));
		assertFalse(canRun(project, "lib/common.glsl"));
		assertFalse(canRun(project, "declared.glsl"));
		assertFalse(canRun(project, "disabled.glsl"));
		assertFalse(canRun(project, "commented.glsl"));
		assertFalse(canRun(project, "notes.txt"));
		assertEquals(OpenProject.Runs.NO, project.runs(path("other.glsl")));
		edit(project, document(project, "lib/common.glsl"), "void main() {}");
		assertTrue(canRun(project, "lib/common.glsl"));
	}

	@Test
	public void findsWhetherALargeFileRunsOnTheWorkerOncePerText()
			throws IOException {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 500_000; ++i) {
			text.append("float x;\n");
		}
		write("big.glsl", text.append("void main() {}\n").toString());
		OpenProject project = open();
		Document big = document(project, "big.glsl");
		List<Runnable> posted = new ArrayList<>();
		project.setListener(posted::add, () -> {
		});

		assertEquals(OpenProject.Runs.UNKNOWN, project.runs(path("big.glsl")));
		assertEquals(OpenProject.Runs.UNKNOWN, project.runs(path("big.glsl")));
		assertEquals(1, worker.size());
		settle();
		assertEquals(1, drain(posted));
		assertEquals(OpenProject.Runs.YES, project.runs(path("big.glsl")));

		edit(project, big, "void f() {}");
		assertEquals(OpenProject.Runs.UNKNOWN, project.runs(path("big.glsl")));
		settle();
		assertEquals(OpenProject.Runs.NO, project.runs(path("big.glsl")));
	}

	@Test
	public void marksAFileAndEverythingUnderAFolder() throws IOException {
		for (String file : List.of("lib/x.glsl", "lib/deep/y.glsl",
				"library.glsl", "a.glsl")) {
			write(file, "stored");
		}
		OpenProject project = open();
		for (String file : List.of("lib/x.glsl", "lib/deep/y.glsl",
				"library.glsl", "a.glsl")) {
			document(project, file);
		}
		edit(project, "lib/x.glsl", "mine");
		edit(project, "lib/deep/y.glsl", "mine");
		edit(project, "library.glsl", "mine");
		write("lib/x.glsl", "theirs");
		refresh(project);
		project.setDiagnostics(build(project), List.of(
				new ProjectDiagnostic(Severity.ERROR, "e", path("lib/x.glsl"), 1),
				new ProjectDiagnostic(Severity.WARNING, "w",
						path("lib/deep/y.glsl"), 1),
				new ProjectDiagnostic(Severity.ERROR, "e",
						path("library.glsl"), 1),
				ProjectDiagnostic.error("driver crashed")));

		assertEquals(new OpenProject.Marks(2, 1, 1, 1),
				project.marks(path("lib")));
		assertEquals(new OpenProject.Marks(1, 1, 0, 1),
				project.marks(path("lib/x.glsl")));
		assertEquals(new OpenProject.Marks(0, 0, 0, 0),
				project.marks(path("a.glsl")));
		assertEquals(new OpenProject.Marks(3, 3, 1, 1), project.marks(null));
	}

	@Test
	public void tellsWhatTheStorageOffers() throws IOException {
		write("main.glsl", SHADER);

		assertTrue(open().offers(path("main.glsl")).write());
		assertEquals(ProjectFiles.Offers.NONE, open(new MemoryProjectFiles()
				.put("main.glsl", SHADER)).offers(path("main.glsl")));
	}

	@Test
	public void tellsTheListenerOnceThroughItsExecutor() throws IOException {
		write("a.glsl", "a");
		OpenProject project = open();
		document(project, "a.glsl");
		List<Runnable> posted = new ArrayList<>();
		int[] heard = {0};
		project.setListener(posted::add, () -> ++heard[0]);

		edit(project, "a.glsl", "a!");
		edit(project, "a.glsl", "a!!");
		OpenProject.Build build = build(project);
		project.setDiagnostics(build, List.of(ProjectDiagnostic.error("e")));

		assertEquals(0, heard[0]);
		assertEquals(1, posted.size());
		posted.remove(0).run();
		assertEquals(1, heard[0]);
		edit(project, "a.glsl", "a!!!");
		project.setDiagnostics(build, List.of());
		assertTrue(posted.isEmpty());
		assertEquals(1, project.errors());
	}

	@Test
	public void tellsTheListenerWhatTheScreensMark() throws IOException {
		write("main.glsl", "#include \"lib.glsl\"\n" + SHADER);
		write("lib.glsl", "// lib\n");
		write("b.glsl", SHADER);
		OpenProject project = open();
		document(project, "lib.glsl");
		List<Runnable> posted = new ArrayList<>();
		project.setListener(posted::add, () -> {
		});

		edit(project, "lib.glsl", "// edited\n");
		assertEquals(1, drain(posted));
		save(project, true);
		assertEquals(1, drain(posted));
		OpenProject.Build build = build(project);
		assertEquals(1, drain(posted));
		project.setDiagnostics(build, List.of(ProjectDiagnostic.error("e")));
		assertEquals(1, drain(posted));
		project.runThisFile(path("b.glsl"));
		assertEquals(1, drain(posted));
		edit(project, "lib.glsl", "// mine\n");
		drain(posted);
		write("lib.glsl", "// theirs\n");
		refresh(project);
		assertEquals(Set.of(path("lib.glsl")), project.conflicts().keySet());
		assertEquals(1, drain(posted));
		refresh(project);
		assertEquals(0, drain(posted));

		project.setListener(null, null);
		project.resolve(path("lib.glsl"), OpenProject.Choice.KEEP,
				project.seen(path("lib.glsl")));
		assertEquals(0, drain(posted));
	}

	@Test
	public void knowsItsNameAndWhereItIs() {
		assertNull(open().name());
		new ProjectSessions(sessionsDir).remember(id, "aurora",
				"ShaderEditor / Shaders", 1);

		OpenProject project = open();

		assertEquals("aurora", project.name());
		assertEquals("ShaderEditor / Shaders", project.location());
	}

	@Test
	public void showsTheFileShownLastAfterReopening() throws IOException {
		write("lib/common.glsl", "// common\n");
		OpenProject project = open();
		assertNull(project.show(path("lib/common.glsl")));

		project.persist();
		settle();

		assertEquals(path("lib/common.glsl"), open().shown());
		assertEquals(path("lib/common.glsl"),
				new ProjectSessions(sessionsDir).shown(id));
	}

	@Test
	public void groupsDiagnosticsByFile() throws IOException {
		OpenProject project = open();
		ProjectDiagnostic inMain = new ProjectDiagnostic(Severity.WARNING,
				"unused", path("main.glsl"), 3);
		ProjectDiagnostic inLib = new ProjectDiagnostic(Severity.ERROR,
				"syntax error", path("lib/common.glsl"), 7);
		ProjectDiagnostic nowhere = ProjectDiagnostic.error("driver crashed");

		project.setDiagnostics(build(project),
				List.of(inMain, inLib, nowhere));

		assertEquals(List.of(inLib), project.diagnostics(
				path("lib/common.glsl")));
		assertEquals(List.of(inMain), project.diagnostics(path("main.glsl")));
		assertEquals(List.of(), project.diagnostics(path("other.glsl")));
		assertEquals(List.of(), project.diagnostics(null));
		assertEquals(List.of(inMain, inLib, nowhere), project.diagnostics());
		assertEquals(2, project.errors());
	}

	@Test
	public void opensOnlyTextOfAtMost16MiBInTheEditor() throws IOException {
		write("image.png", "not really");
		Files.write(new File(root, "data.bin").toPath(), new byte[]{0, 1, 2});
		write("notes.txt", "notes");
		write("big.glsl", "// " + "x".repeat(2 * 1024 * 1024));
		OpenProject project = open();

		assertNotNull(load(project, "image.png"));
		assertNotNull(load(project, "data.bin"));
		assertNotNull(load(project, "missing.glsl"));
		assertEquals("notes", text(project, "notes.txt"));
		assertEquals("big.glsl is larger than 16 MiB",
				this.<String>await(done -> project.load(path("big.glsl"),
						Document.MAX_BYTES + 1, done)));
		assertNull(load(project, "big.glsl"));
	}

	@Test
	public void keepsTheProjectFilesWhenOpening() throws IOException {
		write("main.glsl", SHADER);
		byte[] before = Files.readAllBytes(new File(root, "main.glsl")
				.toPath());

		OpenProject project = open();
		document(project, "main.glsl");
		project.persist();
		refresh(project);

		assertArrayEquals(before, Files.readAllBytes(
				new File(root, "main.glsl").toPath()));
		assertEquals(Set.of("main.glsl"), Set.of(root.list()));
	}

	private OpenProject editedElsewhere() throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		edit(project, document(project, "a.glsl"), "mine");
		write("a.glsl", "theirs");
		refresh(project);
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		assertEquals("mine", project.document(path("a.glsl")).text()
				.toString());
		return project;
	}

	private OpenProject open() {
		return open(new DirectoryProjectFiles(root));
	}

	private OpenProject open(ProjectFiles store) {
		return OpenProject.open(id, store, null, null,
				new ProjectSessions(sessionsDir), worker, main, steps.timers,
				budget);
	}

	private LiveProjects live() {
		return new LiveProjects(new ProjectSessions(sessionsDir), worker,
				main, steps.timers, budget);
	}

	// Return what the documents in memory besides the one shown take.
	private static long used(OpenProject project) {
		long used = 0;
		for (int i = 0; i < 5; ++i) {
			ProjectPath path = path(i + ".glsl");
			Document document = project.document(path);
			if (document != null && !path.equals(project.shown())) {
				used += OpenProject.cost(document);
			}
		}
		return used;
	}

	private OpenProject open(LiveProjects live, LiveProjects.Opener opener) {
		OpenProject[] opened = {null};
		live.open(id, opener, project -> opened[0] = project);
		settle();
		assertNotNull(opened[0]);
		return opened[0];
	}

	private SafProjects.Opened folder(Locator locator) {
		return new SafProjects.Opened(new DirectoryProjectFiles(
				new File(locator.value())), null, null);
	}

	private void settle() {
		steps.settle();
	}

	@SuppressWarnings("unchecked")
	private <T> T await(Consumer<Callback<T>> call) {
		Object[] result = {NOTHING};
		call.accept(value -> result[0] = value);
		settle();
		assertNotSame(NOTHING, result[0]);
		return (T) result[0];
	}

	private String load(OpenProject project, String path) {
		return await(done -> project.load(path(path), ProjectFiles.UNKNOWN,
				done));
	}

	private Document document(OpenProject project, String path) {
		assertNull(load(project, path));
		return project.document(path(path));
	}

	private Document document(OpenProject project, String path, String text)
			throws IOException {
		write(path, text);
		return document(project, path);
	}

	private boolean canRun(OpenProject project, String path) {
		document(project, path);
		project.runs(path(path));
		settle();
		return project.runs(path(path)) == OpenProject.Runs.YES;
	}

	private String text(OpenProject project, String path) {
		return document(project, path).text().toString();
	}

	private static void edit(OpenProject project, String path, String text) {
		edit(project, project.document(path(path)), text);
	}

	private static void edit(OpenProject project, Document document,
			String text) {
		document.replace(0, document.text().length(), Text.of(text));
	}

	private Map<ProjectPath, Outcome> save(OpenProject project,
			boolean explicit) {
		return await(done -> project.save(explicit, done));
	}

	private void flush(OpenProject project) {
		boolean[] flushed = {false};
		project.flush(() -> flushed[0] = true);
		settle();
		assertTrue(flushed[0]);
	}

	// Return the files the journal keeps unsaved text of.
	private Set<ProjectPath> kept() {
		return new ProjectSessions(sessionsDir).unsaved(id);
	}

	// Return the text the journal keeps for path, or null.
	private String journaled(String path) {
		Journal.Recovered recovered = new ProjectSessions(sessionsDir)
				.journal(id).read(path(path));
		return recovered != null ? recovered.text().toString() : null;
	}

	private Set<ProjectPath> refresh(OpenProject project) {
		return await(project::refresh);
	}

	private Preparation prepare(OpenProject project) {
		return build(project).preparation();
	}

	private OpenProject.Build build(OpenProject project) {
		return await(done -> project.prepare(BuiltinNames.ALL, done));
	}

	private static String built(Preparation preparation, String path) {
		return new String(preparation.files().get(path(path)).bytes(),
				StandardCharsets.UTF_8);
	}

	private void write(String name, String text) throws IOException {
		File file = new File(root, name);
		File parent = file.getParentFile();
		assertTrue(parent.isDirectory() || parent.mkdirs());
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String name) throws IOException {
		return new String(Files.readAllBytes(new File(root, name).toPath()),
				StandardCharsets.UTF_8);
	}

	private static int drain(List<Runnable> posted) {
		int count = posted.size();
		for (Runnable runnable : posted) {
			runnable.run();
		}
		posted.clear();
		return count;
	}

	private static List<String> names(OpenProject.Folder folder) {
		List<String> names = new ArrayList<>();
		for (OpenProject.Item item : folder.items()) {
			names.add(item.path().getFileName());
		}
		return names;
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	private static final class FailingFiles implements ProjectFiles {
		private final ProjectFiles files;
		private final List<ProjectPath> listed = new ArrayList<>();
		private ProjectPath failing;
		private int forgotten;

		private FailingFiles(ProjectFiles files) {
			this.files = files;
		}

		@Override
		public Read read(ProjectPath path) {
			return files.read(path);
		}

		@Override
		public Listing list(ProjectPath folder) {
			listed.add(folder);
			return files.list(folder);
		}

		@Override
		public void forgetListings() {
			++forgotten;
		}

		@Override
		public Change write(ProjectPath path, byte[] bytes,
				String expectedRevision) {
			return path.equals(failing)
					? new Refused(Refusal.FAILED, "the card was removed")
					: files.write(path, bytes, expectedRevision);
		}
	}
}
