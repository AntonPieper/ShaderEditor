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
import java.util.Set;

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
	public void findsWhatRunsAfterTheManifestChangedU10() throws IOException {
		write("main.glsl", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		assertEquals(path("main.glsl"), project.running());

		write("shader-project.json", "{\"entry\": \"b.glsl\"}");
		project.findRunning();

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

		assertArrayEquals(before, Files.readAllBytes(
				new File(root, "main.glsl").toPath()));
		assertEquals(Set.of("main.glsl"), Set.of(root.list()));
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
}
