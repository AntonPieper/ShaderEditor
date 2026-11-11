package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Entries;

public class DraftFilesTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void readsDraftsOverStore() {
		MemoryProjectFiles store = new MemoryProjectFiles()
				.put("main.glsl", "stored")
				.put("lib.glsl", "library");
		DraftFiles files = new DraftFiles(store);
		files.put(path("main.glsl"), new Draft("edited", "r0"));
		files.put(path("new.glsl"), new Draft("fresh", null));

		assertEquals("edited", text(files.read(path("main.glsl"))));
		assertEquals("fresh", text(files.read(path("new.glsl"))));
		assertEquals("library", text(files.read(path("lib.glsl"))));
		assertNull(files.read(path("missing.glsl")));
		files.put(path("main.glsl"), new Draft("edited again", "r0"));
		assertEquals("edited again", text(files.read(path("main.glsl"))));
		assertEquals(List.of(path("main.glsl"), path("new.glsl")),
				new ArrayList<>(files.paths()));

		assertTrue(files.remove(path("main.glsl")));
		assertFalse(files.remove(path("main.glsl")));
		assertEquals("stored", text(files.read(path("main.glsl"))));
	}

	@Test
	public void draftRevisionFollowsText() {
		DraftFiles files = new DraftFiles(new MemoryProjectFiles()
				.put("main.glsl", "stored"));
		files.put(path("main.glsl"), new Draft("a", "r0"));
		String first = revision(files.read(path("main.glsl")));
		files.put(path("main.glsl"), new Draft("a", "r0"));
		assertEquals(first, revision(files.read(path("main.glsl"))));
		files.put(path("main.glsl"), new Draft("b", "r0"));
		String second = revision(files.read(path("main.glsl")));
		assertNotEquals(first, second);
		assertNotEquals("r0", second);
	}

	@Test
	public void preparationSeesDraftedInclude() throws IOException {
		File root = temp.newFolder("root");
		write(new File(root, "main.glsl"), "#include \"lib.glsl\"\n" +
				"void main() {}\n");
		write(new File(root, "lib.glsl"), "float stored;\n");
		DraftFiles files = new DraftFiles(new DirectoryProjectFiles(root));
		files.put(path("lib.glsl"), new Draft("float drafted;\n",
				revision(files.store().read(path("lib.glsl")))));

		Preparation preparation = Preparation.prepare(files,
				path("main.glsl"), ProjectLimits.DEFAULT, BuiltinNames.ALL);

		assertTrue(preparation.diagnostics().toString(),
				preparation.diagnostics().isEmpty());
		String source = String.join("\n",
				preparation.plan().programs().get(0).lines());
		assertTrue(source, source.contains("float drafted;"));
		assertFalse(source, source.contains("float stored;"));
		assertEquals(revision(files.read(path("lib.glsl"))),
				dependency(preparation, "lib.glsl").revision());
		assertEquals("float stored;\n", new String(Files.readAllBytes(
				new File(root, "lib.glsl").toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void listsAsStoreDoes() throws IOException {
		File root = temp.newFolder("root");
		write(new File(root, "main.glsl"), "void main() {}");
		DraftFiles files = new DraftFiles(new DirectoryProjectFiles(root));
		files.put(path("main.glsl"), new Draft("edited", null));
		Entries entries = (Entries) files.list(null);
		assertEquals(1, entries.entries().size());
		assertEquals("main.glsl", entries.entries().get(0).name());
	}

	private static SourceAssembly.Dependency dependency(
			Preparation preparation, String path) {
		for (SourceAssembly.Dependency dependency :
				preparation.dependencies()) {
			if (dependency.path().equals(path(path))) {
				return dependency;
			}
		}
		throw new AssertionError("not read: " + path);
	}

	private static void write(File file, String text) throws IOException {
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private static String text(ProjectFiles.Read read) {
		assertNotNull(read);
		return new String(((Content) read).bytes(), StandardCharsets.UTF_8);
	}

	private static String revision(ProjectFiles.Read read) {
		return ((Content) read).revision();
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
