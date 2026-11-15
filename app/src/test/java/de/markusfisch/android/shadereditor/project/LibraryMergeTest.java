package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.markusfisch.android.shadereditor.project.LibraryArchive.Limits;

/**
 * An import only adds to the library: what is there stays as it is,
 * and what cannot be added whole is not added at all.
 */
public class LibraryMergeTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// Export, then import into an empty library: the same files, byte
	// for byte, their times, and what is kept beside them. A second
	// import of the same archive adds nothing.
	@Test
	public void anExportComesBackAsItWas() throws Exception {
		File source = temp.newFolder("source");
		LibraryExportTest.put(source, "Plasma.frag", "plasma");
		LibraryExportTest.put(source, "Größe.frag", "size");
		LibraryExportTest.put(source, "Ink/main.frag", "ink");
		LibraryExportTest.put(source, "Ink/paper.frag", "paper");
		LibraryExportTest.put(source, "Ink/notes/README.md", "# Ink");
		LibraryExportTest.put(source, "textures/noise.png", "\u0089PNG\u0000");
		LibraryExportTest.put(source, "README.md", "# Mine");
		assertTrue(new File(source, "ideas").mkdir());
		assertTrue(new File(source, "Ink/empty").mkdir());
		LibrarySettings kept = settings("source-settings");
		assertTrue(kept.setTemplate(path("Plasma.frag")));
		assertTrue(kept.setQuality(path("Ink/main.frag"), .5f));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LibraryExport.write(new DirectoryProjectFiles(source), kept.read(),
				"1", System.currentTimeMillis(), Limits.DEFAULT, out);

		File root = temp.newFolder("Shaders");
		LibrarySettings settings = settings("settings");
		LibraryMerge.Result result = place(root, settings,
				out.toByteArray());

		assertNull(result.problem());
		assertEquals(tree(source), tree(root));
		assertEquals(3, result.shaders());
		assertEquals(2, result.files());
		assertEquals(0, result.there());
		assertEquals(path("Plasma.frag"), settings.template());
		assertEquals(.5f, settings.quality(path("Ink/main.frag")), 0f);
		assertEquals(new File(source, "Ink/main.frag").lastModified() /
						2000,
				new File(root, "Ink/main.frag").lastModified() / 2000);

		LibraryMerge.Result again = place(root, settings, out.toByteArray());
		assertNull(again.problem());
		assertEquals(0, again.shaders() + again.files());
		assertEquals(5, again.there());
		assertEquals(tree(source), tree(root));
	}

	// A shader whose name is taken comes in beside it, a shader of
	// several files as a whole; a shared file stays as it is and is
	// named. What is kept of the archive's files goes to where they
	// are now, and nothing of the library changes.
	@Test
	public void whatIsThereStaysAsItIs() throws Exception {
		File root = temp.newFolder("Shaders");
		LibraryExportTest.put(root, "Plasma.frag", "mine");
		LibraryExportTest.put(root, "Same.frag", "same");
		LibraryExportTest.put(root, "Ink/main.frag", "my ink");
		LibraryExportTest.put(root, "textures/noise.png", "my noise");
		LibrarySettings settings = settings("settings");
		assertTrue(settings.setTemplate(path("Same.frag")));
		assertTrue(settings.setQuality(path("Plasma.frag"), .25f));
		Map<String, String> before = tree(root);

		LibraryMerge.Result result = place(root, settings, new Zips()
				.add(".shadereditor/library.txt", "template\tPlasma.frag\n" +
						"quality\t0.5\tPlasma.frag\n" +
						"quality\t0.125\tInk/main.frag\n" +
						"quality\t9\tSame.frag\n")
				.add("Plasma.frag", "theirs")
				.add("Plasma 2.frag", "their second")
				.add("Same.frag", "same")
				.add("Ink/main.frag", "their ink")
				.add("Ink/sub/main.frag", "inner")
				.add("textures/noise.png", "their noise")
				.add("lib/common.glsl", "common")
				.end());

		assertNull(result.problem());
		// The archive has a "Plasma 2.frag" of its own.
		assertEquals(Map.of(path("Plasma.frag"), path("Plasma 3.frag"),
				path("Ink"), path("Ink 2")), result.renamed());
		assertEquals(List.of(path("textures/noise.png")), result.kept());
		assertEquals(3, result.shaders());
		assertEquals(1, result.files());
		assertEquals(1, result.there());
		Map<String, String> after = tree(root);
		assertEquals("theirs", after.remove("Plasma 3.frag"));
		assertEquals("their second", after.remove("Plasma 2.frag"));
		assertEquals("their ink", after.remove("Ink 2/main.frag"));
		assertEquals("inner", after.remove("Ink 2/sub/main.frag"));
		assertEquals("common", after.remove("lib/common.glsl"));
		assertEquals(before, after);
		// Only of the files this import made.
		assertEquals(.25f, settings.quality(path("Plasma.frag")), 0f);
		assertEquals(.5f, settings.quality(path("Plasma 3.frag")), 0f);
		assertEquals(.125f, settings.quality(path("Ink 2/main.frag")), 0f);
		assertEquals(1f, settings.quality(path("Same.frag")), 0f);
		assertEquals(path("Same.frag"), settings.template());
	}

	// Names that differ only by case are one name: a shader comes in
	// under another, a shared file stays, and a folder refuses.
	@Test
	public void aNameInAnotherCaseIsTaken() throws Exception {
		File root = temp.newFolder("Shaders");
		LibraryExportTest.put(root, "plasma.frag", "mine");
		LibraryExportTest.put(root, "textures/Noise.png", "my noise");
		Map<String, String> before = tree(root);

		LibraryMerge.Result result = place(root, settings("settings"),
				new Zips().add("Plasma.frag", "theirs")
						.add("textures/noise.png", "their noise").end());
		assertEquals(Map.of(path("Plasma.frag"), path("Plasma 2.frag")),
				result.renamed());
		assertEquals(List.of(path("textures/noise.png")), result.kept());

		LibraryMerge.Result refused = place(root, settings("settings"),
				new Zips().add("New.frag", "new")
						.add("Textures/other.png", "other").end());
		assertEquals("the library has Textures under a name that differs " +
				"only by case, which a phone cannot keep apart",
				refused.problem());
		before.put("Plasma 2.frag", "theirs");
		assertEquals(before, tree(root));
	}

	// A write that is refused half way leaves nothing of the import:
	// not the files made before it, not their folders, not the hidden
	// folder a shader is unpacked in. One left by a process that died
	// is removed by the next import.
	@Test
	public void aRefusedWriteLeavesNothing() throws Exception {
		File root = temp.newFolder("Shaders");
		LibraryExportTest.put(root, "Mine.frag", "mine");
		LibraryExportTest.put(root, ".shadereditor-import/0/main.frag",
				"left behind");
		Map<String, String> before = tree(root);
		before.remove(".shadereditor-import/0/main.frag");
		File staging = temp.newFolder();
		LibraryImport.Archive archive = LibraryImport.read(
				new ByteArrayInputStream(new Zips()
						.add("A.frag", "a")
						.add("new/deep/B.frag", "b")
						.add("Ink/main.frag", "ink")
						.add("Ink/paper.frag", "paper")
						.add("Z.frag", "z").end()), staging, Limits.DEFAULT);
		// A file of the shader cannot be read anymore.
		assertTrue(archive.files().get(3).file().delete());

		LibraryMerge.Result result = LibraryMerge.place(
				new DirectoryProjectFiles(root), archive,
				settings("settings"), (path, time) -> {
				});

		assertTrue(result.problem(), result.problem().startsWith(
				"Ink/paper.frag cannot be written: "));
		assertEquals(before, tree(root));
		assertFalse(new File(root, ".shadereditor-import").exists());
		assertFalse(new File(root, "new").exists());
	}

	private LibraryMerge.Result place(File root, LibrarySettings settings,
			byte[] archive) throws Exception {
		return LibraryMerge.place(new DirectoryProjectFiles(root),
				LibraryImport.read(new ByteArrayInputStream(archive),
						temp.newFolder(), Limits.DEFAULT), settings,
				(path, time) -> assertTrue(new File(root, path.toString())
						.setLastModified(time)));
	}

	private LibrarySettings settings(String name) {
		return new LibrarySettings(new File(temp.getRoot(), name));
	}

	// Every file with its text, and every empty folder.
	private static Map<String, String> tree(File root) throws IOException {
		Map<String, String> tree = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root.toPath())) {
			for (Path path : paths.collect(Collectors.toList())) {
				String name = root.toPath().relativize(path).toString()
						.replace(File.separatorChar, '/');
				if (Files.isRegularFile(path)) {
					tree.put(name, new String(Files.readAllBytes(path),
							StandardCharsets.UTF_8));
				} else if (!name.isEmpty() &&
						path.toFile().list().length == 0) {
					tree.put(name + "/", "");
				}
			}
		}
		return tree;
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
