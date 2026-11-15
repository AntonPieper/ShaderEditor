package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.markusfisch.android.shadereditor.project.LibraryArchive.Limits;
import de.markusfisch.android.shadereditor.project.LibraryArchive.Refused;

public class LibraryExportTest {
	private static final long TIME = 1794751402000L;

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// Every file under its path with its bytes, in the order of the
	// paths behind the text file; an empty folder is an entry, a folder
	// with files is not, and dot names are left out.
	@Test
	public void writesTheFilesAsTheyAreInTheOrderOfTheirPaths()
			throws Exception {
		File root = temp.newFolder("Shaders");
		put(root, "Plasma.frag", "plasma");
		put(root, "Größe.frag", "size");
		put(root, "Ink/main.frag", "ink");
		put(root, "Ink/paper.frag", "paper");
		put(root, "textures/noise.png", "\u0089PNG");
		put(root, "README.md", "# Mine");
		put(root, ".hidden", "no");
		put(root, ".git/config", "no");
		assertTrue(new File(root, "ideas").mkdir());
		LibrarySettings settings = new LibrarySettings(
				temp.newFile("settings"));
		assertTrue(settings.setTemplate(path("Plasma.frag")));
		assertTrue(settings.setQuality(path("Ink/main.frag"), .5f));
		assertTrue(settings.setQuality(path("Gone.frag"), .25f));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LibraryExport.Written written = LibraryExport.write(
				new DirectoryProjectFiles(root), settings.read(), "2.36.3",
				TIME, Limits.DEFAULT, out);

		assertEquals(6, written.files());
		assertEquals(2, written.hidden());
		Map<String, String> entries = entries(out.toByteArray());
		assertEquals(List.of(".shadereditor/library.txt",
				"Größe.frag", "Ink/main.frag", "Ink/paper.frag",
				"Plasma.frag", "README.md", "ideas/", "textures/noise.png"),
				new ArrayList<>(entries.keySet()));
		assertEquals("size", entries.get("Größe.frag"));
		assertEquals("\u0089PNG", entries.get("textures/noise.png"));
		// What is kept of a file that is not written is not told.
		assertEquals("format\t1\napp\t2.36.3\n" +
				"exported\t2026-11-15T14:03:22Z\n" +
				"template\tPlasma.frag\nquality\t0.5\tInk/main.frag\n",
				entries.get(".shadereditor/library.txt"));
	}

	// Two exports of one library are the same bytes; only the time of
	// the export tells them apart.
	@Test
	public void theSameLibraryGivesTheSameBytes() throws Exception {
		File root = temp.newFolder("Shaders");
		put(root, "b.frag", "b");
		put(root, "a/main.frag", "a");
		assertArrayEquals(export(root, TIME), export(root, TIME));
		assertFalse(java.util.Arrays.equals(export(root, TIME),
				export(root, TIME + 60000)));
	}

	// Some thousand files are streamed one after the other: nothing
	// is held but the names.
	@Test
	public void writesSomeThousandFiles() throws Exception {
		File root = temp.newFolder("Shaders");
		for (int i = 0; i < 3000; ++i) {
			put(root, "f" + (i % 30) + "/s" + i + ".frag", "void main(){}");
		}
		int[] entries = {0};
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertEquals(3000, LibraryExport.write(
				new DirectoryProjectFiles(root), new LibrarySettings(
						temp.newFile("settings")).read(), "1", TIME,
				Limits.DEFAULT, out).files());
		try (ZipInputStream zip = new ZipInputStream(
				new ByteArrayInputStream(out.toByteArray()))) {
			while (zip.getNextEntry() != null) {
				++entries[0];
			}
		}
		assertEquals(3001, entries[0]);
	}

	// What Import would refuse is not written: the limits, and a name
	// an archive cannot hold, each told by its reason.
	@Test
	public void refusesWhatAnImportWouldRefuse() throws Exception {
		File root = temp.newFolder("Shaders");
		put(root, "a.frag", "12345678");
		put(root, "b.frag", "12345678");
		assertEquals("it holds more than 2 files", refusal(root,
				new Limits(2, 1000)));
		assertEquals("it holds more than 0 MiB", refusal(root,
				new Limits(10, 15)));
		put(root, "what?.frag", "x");
		assertEquals("\"what?.frag\" has a character no folder holds " +
				"in a name", refusal(root, Limits.DEFAULT));
		assertEquals("the library holds no files", refusal(
				temp.newFolder("empty"), Limits.DEFAULT));
	}

	private String refusal(File root, Limits limits) throws IOException {
		LibrarySettings.State settings = new LibrarySettings(
				new File(temp.getRoot(), "none")).read();
		return assertThrows(Refused.class, () -> LibraryExport.write(
				new DirectoryProjectFiles(root), settings, "1", TIME, limits,
				OutputStream.nullOutputStream())).getMessage();
	}

	private byte[] export(File root, long time) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LibraryExport.write(new DirectoryProjectFiles(root),
				new LibrarySettings(new File(temp.getRoot(), "none")).read(),
				"1", time, Limits.DEFAULT, out);
		return out.toByteArray();
	}

	static Map<String, String> entries(byte[] archive) throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(
				new ByteArrayInputStream(archive))) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
				entries.put(entry.getName(), new String(zip.readAllBytes(),
						StandardCharsets.UTF_8));
			}
		}
		return entries;
	}

	static void put(File root, String path, String text)
			throws IOException {
		File file = new File(root, path);
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
		assertTrue(file.setLastModified(TIME - 86400000));
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
