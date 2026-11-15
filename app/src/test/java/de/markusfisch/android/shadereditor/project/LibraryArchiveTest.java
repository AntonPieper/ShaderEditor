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

/** The library's own export and import, through its one way in. */
public class LibraryArchiveTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// A library that is exported and imported into another has the
	// same shaders with their qualities, and no staging folder is left.
	@Test
	public void aLibraryComesBackInAnother() throws IOException {
		Library source = library("a");
		LibraryExportTest.put(source.folder(), "Plasma.frag", "plasma");
		LibraryExportTest.put(source.folder(), "Ink/main.frag", "ink");
		assertNull(source.setQuality(path("Plasma.frag"), .5f, null));
		assertNull(source.setTemplate(path("Plasma.frag"), null));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Library.Exported exported = source.export("1", 1000, out, null);
		assertNull(exported.problem());
		assertEquals(2, exported.files());

		Library library = library("b");
		File staging = new File(temp.getRoot(), "staging");
		Library.Imported imported = library.take(new ByteArrayInputStream(
				out.toByteArray()), staging, null);

		assertNull(imported.problem());
		assertEquals(2, imported.shaders());
		assertEquals("ink", new String(Files.readAllBytes(new File(
				library.folder(), "Ink/main.frag").toPath()),
				StandardCharsets.UTF_8));
		assertEquals(.5f, library.settings().quality(path("Plasma.frag")),
				0f);
		assertEquals(path("Plasma.frag"), library.settings().template());
		assertFalse(staging.exists());
	}

	// While a redo of the move is unfinished nothing of the library is
	// read or written, also not by these two.
	@Test
	public void bothWaitForAnUnfinishedRedo() throws IOException {
		Library library = library("a");
		LibraryExportTest.put(library.folder(), "Plasma.frag", "plasma");
		Files.write(new File(temp.getRoot(), "a/files/library-redo")
				.toPath(), new byte[]{1});

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(library.export("1", 1000, out, null).problem()
				.startsWith("a redo of the move"));
		assertEquals(0, out.size());
		assertTrue(library.take(new ByteArrayInputStream(new Zips()
				.add("New.frag", "new").end()), new File(temp.getRoot(),
				"staging"), null).problem().startsWith("a redo of the move"));
		assertFalse(new File(library.folder(), "New.frag").exists());
	}

	// A refused archive leaves the library as it was and no staging.
	@Test
	public void aRefusedArchiveAddsNothing() throws IOException {
		Library library = library("a");
		File staging = new File(temp.getRoot(), "staging");
		Library.Imported imported = library.take(new ByteArrayInputStream(
				new Zips().add("Good.frag", "good")
						.add("../evil.frag", "evil").end()), staging, null);

		assertEquals("\"../evil.frag\" is not a name inside a library",
				imported.problem());
		assertEquals(0, library.folder().list().length);
		assertFalse(new File(temp.getRoot(), "a/user-files/evil.frag")
				.exists());
		assertFalse(staging.exists());
	}

	private Library library(String name) {
		File userFiles = new File(temp.getRoot(), name + "/user-files");
		File files = new File(temp.getRoot(), name + "/files");
		assertTrue(userFiles.mkdirs() && files.mkdirs());
		assertTrue(new File(userFiles, Library.FOLDER).mkdir());
		return new Library(userFiles, files);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
