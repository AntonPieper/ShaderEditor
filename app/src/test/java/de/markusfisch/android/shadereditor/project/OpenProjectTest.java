package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.EditingSession.Conflict;
import de.markusfisch.android.shadereditor.project.EditingSession.Failed;
import de.markusfisch.android.shadereditor.project.EditingSession.Outcome;
import de.markusfisch.android.shadereditor.project.EditingSession.Saved;
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

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

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
		assertNull(project.prepare(BuiltinNames.ALL));
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
		Preparation preparation = project.prepare(BuiltinNames.ALL);
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

		assertTrue(new File(root, "c.glsl").delete());

		assertEquals(path("main.glsl"), open().running());
	}

	@Test
	public void editingAnotherFileKeepsWhatRunsU10() throws IOException {
		write("main.glsl", "#include \"lib/common.glsl\"\n" + SHADER);
		write("lib/common.glsl", "// common\n");
		OpenProject project = open();
		assertNull(project.load(path("lib/common.glsl")));
		project.show(path("lib/common.glsl"));

		project.edit(path("lib/common.glsl"), "// edited\n");

		assertEquals(path("main.glsl"), project.running());
		Preparation preparation = project.prepare(BuiltinNames.ALL);
		assertEquals("// edited\n", new String(preparation.files()
				.get(path("lib/common.glsl")).bytes(), StandardCharsets.UTF_8));
		assertEquals("// common\n", read("lib/common.glsl"));
	}

	@Test
	public void refreshFindsAChangedManifestU10() throws IOException {
		write("main.glsl", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		assertEquals(path("main.glsl"), project.running());

		write("shader-project.json", "{\"entry\": \"b.glsl\"}");
		project.refresh();

		assertEquals(path("b.glsl"), project.running());
	}

	@Test
	public void switchingFilesKeepsTextAndEditorState() throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		Object undoOfA = new Object();
		assertNull(project.load(path("a.glsl")));
		project.edit(path("a.glsl"), "a!");
		project.setEditorState(path("a.glsl"), undoOfA);

		assertNull(project.load(path("b.glsl")));
		project.edit(path("b.glsl"), "b!");
		assertNull(project.load(path("a.glsl")));

		assertEquals("a!", project.text(path("a.glsl")));
		assertSame(undoOfA, project.editorState(path("a.glsl")));
		assertEquals("b!", project.text(path("b.glsl")));
		assertEquals(Set.of(path("a.glsl"), path("b.glsl")),
				project.unsaved());

		project.edit(path("b.glsl"), "b");
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
	}

	@Test
	public void keepsDraftsOfSeveralFilesAcrossTheProcess() throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		project.load(path("a.glsl"));
		project.load(path("b.glsl"));
		project.edit(path("a.glsl"), "a!");
		project.edit(path("b.glsl"), "b!");

		assertTrue(project.persist());
		OpenProject restarted = open();

		assertEquals(Set.of(path("a.glsl"), path("b.glsl")),
				restarted.unsaved());
		assertNull(restarted.load(path("b.glsl")));
		assertEquals("b!", restarted.text(path("b.glsl")));
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
		project.load(path("a.glsl"));
		project.load(path("b.glsl"));
		project.edit(path("a.glsl"), "a!");
		project.edit(path("b.glsl"), "b!");

		Map<ProjectPath, Outcome> outcomes = project.save(true);

		assertTrue(outcomes.get(path("a.glsl")) instanceof Saved);
		assertEquals(new Failed("the card was removed"),
				outcomes.get(path("b.glsl")));
		assertEquals("a!", read("a.glsl"));
		assertEquals("b", read("b.glsl"));
		assertEquals(Set.of(path("b.glsl")), project.unsaved());
		assertEquals(Set.of(path("b.glsl")), open().unsaved());

		store.failing = null;
		outcomes = project.save(true);

		assertEquals(Set.of(path("b.glsl")), outcomes.keySet());
		assertEquals("b!", read("b.glsl"));
		assertTrue(project.unsaved().isEmpty());
		assertTrue(open().unsaved().isEmpty());
		project.edit(path("a.glsl"), "a!!");
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		project.edit(path("a.glsl"), "a!");
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void reloadsFromFileOnRequest() throws IOException {
		OpenProject project = editedElsewhere();

		assertNull(project.reload(path("a.glsl")));

		assertEquals("theirs", project.text(path("a.glsl")));
		assertNull(project.editorState(path("a.glsl")));
		assertTrue(project.unsaved().isEmpty());
		assertTrue(project.conflicts().isEmpty());
	}

	@Test
	public void keepsEditsUntilAnExplicitSave() throws IOException {
		OpenProject project = editedElsewhere();

		project.keepEdits(path("a.glsl"));

		assertTrue(project.conflicts().isEmpty());
		assertTrue(project.save(false).get(path("a.glsl")) instanceof Conflict);
		assertEquals("theirs", read("a.glsl"));
		assertTrue(project.save(true).get(path("a.glsl")) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void savesACopyBesideTheChangedFile() throws IOException {
		OpenProject project = editedElsewhere();

		OpenProject.Copy copy = project.saveCopy(path("a.glsl"));

		assertNull(copy.problem());
		assertEquals(path("a copy.glsl"), copy.path());
		assertEquals("mine", read("a copy.glsl"));
		assertEquals("theirs", read("a.glsl"));
		assertEquals("mine", project.text(path("a copy.glsl")));
		assertEquals("theirs", project.text(path("a.glsl")));
		assertTrue(project.unsaved().isEmpty());
		assertTrue(project.conflicts().isEmpty());
		write("b.txt", "b");
		project.load(path("b.txt"));
		project.edit(path("b.txt"), "b!");
		write("b copy.txt", "taken");
		assertEquals(path("b copy 2.txt"),
				project.saveCopy(path("b.txt")).path());
	}

	@Test
	public void refreshReadsUneditedFilesAgain() throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		project.load(path("a.glsl"));
		project.load(path("b.glsl"));
		project.setEditorState(path("a.glsl"), new Object());
		project.edit(path("b.glsl"), "b!");
		write("a.glsl", "a2");

		assertEquals(Set.of(path("a.glsl")), project.refresh());

		assertEquals("a2", project.text(path("a.glsl")));
		assertNull(project.editorState(path("a.glsl")));
		assertEquals("b!", project.text(path("b.glsl")));
		assertTrue(project.conflicts().isEmpty());
	}

	@Test
	public void refreshForgetsWhatListingsTold() {
		FailingFiles store = new FailingFiles(new DirectoryProjectFiles(root));
		OpenProject project = open(store);

		project.refresh();

		assertEquals(1, store.forgotten);
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

		project.prepare(BuiltinNames.ALL);

		assertEquals(List.of(new OpenProject.Used(path("main.glsl"), false),
						new OpenProject.Used(path("lib/common.glsl"), false),
						new OpenProject.Used(path("noise.png"), true)),
				project.used());
		assertTrue(project.uses(path("lib/common.glsl")));
		assertFalse(project.uses(path("other.glsl")));
		assertFalse(project.uses(ProjectManifest.FILE));
	}

	@Test
	public void leavesOutFilesTheBuildCouldNotRead() throws IOException {
		write("main.glsl", "#include \"missing.glsl\"\n" + SHADER);
		OpenProject project = open();

		project.prepare(BuiltinNames.ALL);

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
		for (String file : List.of("main.glsl", "tight.glsl",
				"lib/common.glsl", "declared.glsl", "disabled.glsl",
				"commented.glsl", "notes.txt")) {
			assertNull(project.load(path(file)));
		}

		assertTrue(project.canRun(path("main.glsl")));
		assertTrue(project.canRun(path("tight.glsl")));
		assertFalse(project.canRun(path("lib/common.glsl")));
		assertFalse(project.canRun(path("declared.glsl")));
		assertFalse(project.canRun(path("disabled.glsl")));
		assertFalse(project.canRun(path("commented.glsl")));
		assertFalse(project.canRun(path("notes.txt")));
		assertFalse(project.canRun(path("other.glsl")));
		project.edit(path("lib/common.glsl"), "void main() {}");
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
			project.load(path(file));
		}
		project.edit(path("lib/x.glsl"), "mine");
		project.edit(path("lib/deep/y.glsl"), "mine");
		project.edit(path("library.glsl"), "mine");
		write("lib/x.glsl", "theirs");
		project.refresh();
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
		project.load(path("a.glsl"));
		List<Runnable> posted = new ArrayList<>();
		int[] heard = {0};
		project.setListener(posted::add, () -> ++heard[0]);

		project.edit(path("a.glsl"), "a!");
		project.edit(path("a.glsl"), "a!!");
		project.setDiagnostics(List.of(ProjectDiagnostic.error("e")));

		assertEquals(0, heard[0]);
		assertEquals(1, posted.size());
		posted.remove(0).run();
		assertEquals(1, heard[0]);
		project.edit(path("a.glsl"), "a!!!");
		project.setDiagnostics(List.of(ProjectDiagnostic.error("e")));
		assertTrue(posted.isEmpty());
	}

	@Test
	public void tellsTheListenerWhatTheScreensMark() throws IOException {
		write("main.glsl", "#include \"lib.glsl\"\n" + SHADER);
		write("lib.glsl", "// lib\n");
		write("b.glsl", SHADER);
		OpenProject project = open();
		project.load(path("lib.glsl"));
		List<Runnable> posted = new ArrayList<>();
		project.setListener(posted::add, () -> {
		});

		project.edit(path("lib.glsl"), "// edited\n");
		assertEquals(1, drain(posted));
		project.save(true);
		assertEquals(1, drain(posted));
		project.prepare(BuiltinNames.ALL);
		assertEquals(1, drain(posted));
		project.setDiagnostics(List.of(ProjectDiagnostic.error("e")));
		assertEquals(1, drain(posted));
		project.runThisFile(path("b.glsl"));
		assertEquals(1, drain(posted));
		project.edit(path("lib.glsl"), "// mine\n");
		drain(posted);
		write("lib.glsl", "// theirs\n");
		project.refresh();
		assertEquals(Set.of(path("lib.glsl")), project.conflicts().keySet());
		assertEquals(1, drain(posted));
		project.refresh();
		assertEquals(0, drain(posted));

		project.setListener(null, null);
		project.keepEdits(path("lib.glsl"));
		assertEquals(0, drain(posted));
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
	public void opensOnlyTextInTheEditor() throws IOException {
		write("image.png", "not really");
		Files.write(new File(root, "data.bin").toPath(), new byte[]{0, 1, 2});
		write("notes.txt", "notes");
		OpenProject project = open();

		assertNotNull(project.load(path("image.png")));
		assertNotNull(project.load(path("data.bin")));
		assertNotNull(project.load(path("missing.glsl")));
		assertNull(project.load(path("notes.txt")));
		assertEquals("notes", project.text(path("notes.txt")));
	}

	@Test
	public void keepsTheProjectFilesWhenOpening() throws IOException {
		write("main.glsl", SHADER);
		byte[] before = Files.readAllBytes(new File(root, "main.glsl")
				.toPath());

		OpenProject project = open();
		project.load(path("main.glsl"));
		project.persist();
		project.refresh();

		assertArrayEquals(before, Files.readAllBytes(
				new File(root, "main.glsl").toPath()));
		assertEquals(Set.of("main.glsl"), Set.of(root.list()));
	}

	private OpenProject editedElsewhere() throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		project.load(path("a.glsl"));
		project.setEditorState(path("a.glsl"), new Object());
		project.edit(path("a.glsl"), "mine");
		write("a.glsl", "theirs");
		project.refresh();
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		return project;
	}

	private OpenProject open() {
		return open(new DirectoryProjectFiles(root));
	}

	private OpenProject open(ProjectFiles store) {
		return OpenProject.open(id, store, new ProjectSessions(sessionsDir));
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
