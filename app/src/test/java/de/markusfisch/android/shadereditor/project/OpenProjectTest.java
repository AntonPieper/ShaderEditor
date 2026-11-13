package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import de.markusfisch.android.shadereditor.project.EditingSession.Conflict;
import de.markusfisch.android.shadereditor.project.EditingSession.Failed;
import de.markusfisch.android.shadereditor.project.EditingSession.Outcome;
import de.markusfisch.android.shadereditor.project.EditingSession.Saved;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
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
	public void preparesTheTextTheDocumentsHadWhenAsked() throws IOException {
		write("main.glsl", SHADER);
		OpenProject project = open();
		document(project, "main.glsl");
		edit(project, "main.glsl", SHADER + "// one\n");
		Preparation[] prepared = new Preparation[1];

		project.prepare(BuiltinNames.ALL, preparation ->
				prepared[0] = preparation);
		edit(project, "main.glsl", SHADER + "// two\n");
		settle();

		assertEquals(SHADER + "// one\n", built(prepared[0], "main.glsl"));
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
		assertEquals(Set.of(path("b.glsl")), open().unsaved());

		store.failing = null;
		outcomes = save(project, true);

		assertEquals(Set.of(path("b.glsl")), outcomes.keySet());
		assertEquals("b!", read("b.glsl"));
		assertTrue(project.unsaved().isEmpty());
		assertTrue(open().unsaved().isEmpty());
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
		project.saveCopy(path("a.glsl"), copy -> {
		});
		worker.run();

		edit(project, a, "later");
		settle();
		project.persist();
		settle();

		assertEquals("mine", read("a copy.glsl"));
		assertEquals("later", a.text().toString());
		assertTrue(a.isModified());
		OpenProject reopened = open();
		assertEquals("later", text(reopened, "a.glsl"));
		assertEquals(Set.of(path("a.glsl")), reopened.unsaved());
	}

	@Test
	public void keepsEditsUntilAnExplicitSave() throws IOException {
		OpenProject project = editedElsewhere();

		project.keepEdits(path("a.glsl"));

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
	public void savesACopyBesideTheChangedFile() throws IOException {
		OpenProject project = editedElsewhere();

		OpenProject.Copy copy = await(done ->
				project.saveCopy(path("a.glsl"), done));

		assertNull(copy.problem());
		assertEquals(path("a copy.glsl"), copy.path());
		assertEquals("mine", read("a copy.glsl"));
		assertEquals("theirs", read("a.glsl"));
		assertEquals("mine", text(project, "a copy.glsl"));
		assertEquals("mine", text(project, "a.glsl"));
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		write("b.txt", "b");
		edit(project, document(project, "b.txt"), "b!");
		write("b copy.txt", "taken");
		assertEquals(path("b copy 2.txt"), this.<OpenProject.Copy>await(
				done -> project.saveCopy(path("b.txt"), done)).path());
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
	public void keepsModifiedDocumentsAndTheCleanOnesUsedLast()
			throws IOException {
		OpenProject project = open();
		Document edited = document(project, "edited.glsl", "e");
		edit(project, edited, "edited");
		Document shown = document(project, "shown.glsl", "s");
		project.show(path("shown.glsl"));
		List<Document> clean = new ArrayList<>();
		for (int i = 0; i <= OpenProject.KEPT_CLEAN; ++i) {
			clean.add(document(project, i + ".glsl", "" + i));
		}

		assertNull(project.document(path("0.glsl")));
		for (int i = 1; i <= OpenProject.KEPT_CLEAN; ++i) {
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
	public void keepsACleanDocumentUntilItsDraftIsDropped()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "a");
		edit(project, a, "a!");
		project.persist();
		settle();
		a.undo();
		for (int i = 0; i <= OpenProject.KEPT_CLEAN; ++i) {
			document(project, i + ".glsl", "" + i);
		}

		assertSame(a, project.document(path("a.glsl")));
		assertTrue(project.unsaved().isEmpty());
		project.persist();
		document(project, "x.glsl", "x");
		assertNull(project.document(path("a.glsl")));
		assertTrue(open().unsaved().isEmpty());
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
		assertFalse(project.canRun(path("other.glsl")));
		edit(project, document(project, "lib/common.glsl"), "void main() {}");
		assertTrue(project.canRun(path("lib/common.glsl")));
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
		project.setDiagnostics(List.of(
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
		project.setDiagnostics(List.of(ProjectDiagnostic.error("e")));

		assertEquals(0, heard[0]);
		assertEquals(1, posted.size());
		posted.remove(0).run();
		assertEquals(1, heard[0]);
		edit(project, "a.glsl", "a!!!");
		project.setDiagnostics(List.of(ProjectDiagnostic.error("e")));
		assertTrue(posted.isEmpty());
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
		prepare(project);
		assertEquals(1, drain(posted));
		project.setDiagnostics(List.of(ProjectDiagnostic.error("e")));
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
		project.keepEdits(path("lib.glsl"));
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

		project.setDiagnostics(List.of(inMain, inLib, nowhere));

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
						FileTexts.MAX_BYTES + 1, done)));
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
		return OpenProject.open(id, store, new ProjectSessions(sessionsDir),
				worker, main);
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
		return project.canRun(path(path));
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

	private Set<ProjectPath> refresh(OpenProject project) {
		return await(project::refresh);
	}

	private Preparation prepare(OpenProject project) {
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
