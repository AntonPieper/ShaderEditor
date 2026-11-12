package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.EditingSession.Conflict;
import de.markusfisch.android.shadereditor.project.EditingSession.Failed;
import de.markusfisch.android.shadereditor.project.EditingSession.Outcome;
import de.markusfisch.android.shadereditor.project.EditingSession.Saved;
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

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	private static final class FailingFiles implements ProjectFiles {
		private final ProjectFiles files;
		private ProjectPath failing;

		private FailingFiles(ProjectFiles files) {
			this.files = files;
		}

		@Override
		public Read read(ProjectPath path) {
			return files.read(path);
		}

		@Override
		public Listing list(ProjectPath folder) {
			return files.list(folder);
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
