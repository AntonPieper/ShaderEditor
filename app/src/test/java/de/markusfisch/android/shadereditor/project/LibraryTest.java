package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class LibraryTest {
	private static final String MAIN = "void main() {\n" +
			"\tgl_FragColor = vec4(1.0);\n}\n";

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void theLibraryIsAFolderOfTheUserFilesAndItsStateIsNot()
			throws IOException {
		Library library = library();
		LibraryMove.Result result = move(library, 2);
		assertEquals(2, result.moved().size());

		assertEquals(new File(temp.getRoot(), "user-files/Shaders"),
				library.folder());
		assertEquals(Map.of("Clouds.frag", "", "Plasma.frag", "",
				"textures/noise.png", ""), names(library.folder()));
		assertEquals(Map.of("moved", "", "settings", ""),
				withoutPreviews(new File(temp.getRoot(), "files/library")));
		// A later start reads all of it again.
		Library again = library();
		assertEquals(path("Clouds.frag"), again.note().moved(2));
		assertEquals(path("Clouds.frag"), again.settings().template());
		assertEquals(.5f, again.settings().quality(path("Clouds.frag")), 0f);
		assertTrue(again.previews().get(path("Plasma.frag")) != null);
		assertEquals(2 * MoveDatabase.DAY, new File(again.folder(),
				"Clouds.frag").lastModified());
		assertTrue(move(again, 2).moved().isEmpty());
	}

	@Test
	public void aRedoPutsTheLibraryAsideAndMovesIntoANewOne()
			throws IOException {
		Library library = library();
		move(library, 2);
		write(new File(library.folder(), "Plasma.frag"), "// mine\n");
		write(new File(library.folder(), "Own.frag"), "// made here\n");
		library.settings().setQuality(path("Own.frag"), .25f);

		assertNull(library.putAside(null));
		assertFalse(library.folder().exists());
		LibraryMove.Result result = move(library, 2);
		assertEquals(2, result.moved().size());
		assertTrue(result.skipped().isEmpty());

		// The new library is as the database has it.
		assertEquals(Map.of("Clouds.frag", "", "Plasma.frag", "",
				"textures/noise.png", ""), names(library.folder()));
		assertTrue(text(new File(library.folder(), "Plasma.frag"))
				.endsWith(MAIN));
		assertEquals(1f, library.settings().quality(path("Own.frag")), 0f);
		// The old one is whole, under another name, with its state.
		File aside = new File(temp.getRoot(),
				"user-files/Shaders before redo");
		assertEquals(Map.of("Clouds.frag", "", "Plasma.frag", "",
				"Own.frag", "", "textures/noise.png", ""), names(aside));
		assertEquals("// mine\n", text(new File(aside, "Plasma.frag")));
		assertEquals(.25f, new LibrarySettings(new File(temp.getRoot(),
				"files/library before redo/settings"))
				.quality(path("Own.frag")), 0f);

		// A second redo takes the next name and keeps the first.
		assertNull(library.putAside(null));
		assertTrue(new File(temp.getRoot(),
				"user-files/Shaders before redo 2/Plasma.frag").isFile());
		assertEquals("// mine\n", text(new File(aside, "Plasma.frag")));
		assertTrue(new File(temp.getRoot(),
				"files/library before redo 2/moved").isFile());
	}

	// Every point a redo can stop at: after its note, after the first
	// rename, after the second, after what was open followed.
	@Test
	public void aRedoThatDiedAtAnyPointIsFinishedBeforeAnythingMoves()
			throws IOException {
		for (int point = 0; point <= 3; ++point) {
			File root = temp.newFolder("died" + point);
			Library library = library(root);
			move(library, 0);
			write(new File(library.folder(), "Plasma.frag"), "// mine\n");
			write(new File(root, "files/library-redo"),
					"Shaders before redo\nlibrary before redo\n");
			File aside = new File(root, "user-files/Shaders before redo");
			if (point >= 1) {
				assertTrue(library.folder().renameTo(aside));
			}
			if (point >= 2) {
				assertTrue(new File(root, "files/library").renameTo(
						new File(root, "files/library before redo")));
			}
			List<String> relocated = new ArrayList<>();
			Library.Relocation relocation = (from, to) -> relocated.add(
					from.getName() + " -> " + to.getName());

			LibraryMove.Result result = library(root).move(database(),
					"Untitled", new LibraryMove.Chosen(0, 0), null,
					relocation);
			// Not skipped by the note of the library that was put aside.
			assertNull("point " + point, result.problem());
			assertEquals("point " + point, 2, result.moved().size());
			assertTrue(text(new File(library.folder(), "Plasma.frag"))
					.endsWith(MAIN));
			assertEquals("// mine\n", text(new File(aside, "Plasma.frag")));
			assertTrue(new File(root,
					"files/library before redo/moved").isFile());
			assertFalse(new File(root, "files/library-redo").exists());
			// What was open of the old library went with it, once.
			assertEquals(List.of("Shaders -> Shaders before redo"),
					relocated);
		}
	}

	// A redo that cannot be finished is not half a library to work
	// in: nothing moves, nothing is made, and no second redo starts.
	@Test
	public void aRedoThatCannotBeFinishedStopsEverything()
			throws IOException {
		Library library = library();
		move(library, 0);
		File files = new File(temp.getRoot(), "files");
		write(new File(files, "library-redo"),
				"Shaders before redo\nlibrary before redo\n");
		assertTrue(library.folder().renameTo(new File(temp.getRoot(),
				"user-files/Shaders before redo")));
		assertTrue(files.setWritable(false));
		String blocked = "a redo of the move is not finished: library " +
				"cannot be renamed to library before redo";
		try {
			LibraryMove.Result result = move(library, 0);
			assertEquals(blocked, result.problem());
			assertTrue(result.skipped().isEmpty());
			assertTrue(result.moved().isEmpty());
			// No empty library was made to look moved.
			assertFalse(library.folder().exists());
			assertEquals(blocked, library.putAside(null));
		} finally {
			files.setWritable(true);
		}
		assertEquals("Shaders before redo\nlibrary before redo\n",
				text(new File(files, "library-redo")));
		// Once it can be finished, it is, and the move runs.
		assertEquals(2, move(library, 0).moved().size());
		assertFalse(new File(files, "library-redo").exists());
		assertTrue(new File(files, "library before redo/moved").isFile());
	}

	// A folder under the target's name is no proof that the rename
	// was made while the source is there too.
	@Test
	public void bothFoldersThereIsNotAFinishedRename() throws IOException {
		Library library = library();
		move(library, 0);
		write(new File(temp.getRoot(),
				"user-files/Shaders before redo/Other.frag"), MAIN);
		write(new File(temp.getRoot(), "files/library-redo"),
				"Shaders before redo\nlibrary before redo\n");
		LibraryMove.Result result = move(library, 0);
		assertEquals("a redo of the move is not finished: both Shaders " +
				"and Shaders before redo are there", result.problem());
		assertTrue(new File(library.folder(), "Plasma.frag").isFile());
		assertTrue(new File(temp.getRoot(), "files/library/moved").isFile());
	}

	@Test
	public void aRedoIsNotFinishedUntilWhatWasOpenFollowed()
			throws IOException {
		Library library = library();
		move(library, 0);
		assertEquals("a redo of the move is not finished: what was open " +
				"of the library cannot follow it",
				library.putAside((from, to) -> false));
		assertTrue(new File(temp.getRoot(), "files/library-redo").isFile());
		assertEquals("a redo of the move is not finished: what was open " +
				"of the library cannot follow it", library.move(database(),
				"Untitled", new LibraryMove.Chosen(0, 0), null,
				(from, to) -> false).problem());
		assertNull(library.finishRedo((from, to) -> true));
		assertEquals(2, move(library, 0).moved().size());
	}

	@Test
	public void aRedoNoteNothingWroteStopsEverythingToo()
			throws IOException {
		Library library = library();
		move(library, 0);
		write(new File(temp.getRoot(), "files/library-redo"), "Shaders b");
		assertEquals("a redo of the move was begun, and its note says " +
				"nothing", move(library, 0).problem());
		assertTrue(new File(library.folder(), "Plasma.frag").isFile());
	}

	@Test
	public void nothingToPutAsideIsNoFailure() {
		Library library = library();
		assertNull(library.putAside(null));
		assertFalse(new File(temp.getRoot(),
				"user-files/Shaders before redo").exists());
		assertFalse(new File(temp.getRoot(), "files/library-redo").exists());
	}

	private static LibraryMove.Result move(Library library, long template) {
		return library.move(database(), "Untitled",
				new LibraryMove.Chosen(template, 0), null, null);
	}

	private Library library() {
		return library(temp.getRoot());
	}

	private static Library library(File root) {
		File userFiles = new File(root, "user-files");
		File files = new File(root, "files");
		userFiles.mkdirs();
		files.mkdirs();
		return new Library(userFiles, files);
	}

	private static MoveDatabase database() {
		MoveDatabase database = new MoveDatabase();
		database.textures.put("noise", new de.markusfisch.android
				.shadereditor.project.legacy.LegacyShaderConverter
				.LibraryTexture(false, 8, 8));
		database.images.put("noise", Pictures.png(1));
		database.add(1, "Plasma", "uniform sampler2D noise;\n" + MAIN);
		database.rows.add(new MoveDatabase.Row(2, "Clouds", MAIN, .5f,
				2 * MoveDatabase.DAY, Pictures.png(5)));
		return database;
	}

	// The files under folder by their path, without their content.
	private static Map<String, String> names(File folder) {
		Map<String, String> names = new TreeMap<>();
		collect(names, folder, "");
		return names;
	}

	private static Map<String, String> withoutPreviews(File folder) {
		Map<String, String> names = names(folder);
		names.keySet().removeIf(name -> name.startsWith("previews/"));
		return names;
	}

	private static void collect(Map<String, String> names, File folder,
			String prefix) {
		for (File file : folder.listFiles()) {
			if (file.isDirectory()) {
				collect(names, file, prefix + file.getName() + "/");
			} else {
				names.put(prefix + file.getName(), "");
			}
		}
	}

	private static String text(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()),
				StandardCharsets.UTF_8);
	}

	private static void write(File file, String text) throws IOException {
		write(file, text, StandardCharsets.UTF_8);
	}

	private static void write(File file, String text,
			java.nio.charset.Charset charset) throws IOException {
		file.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(text.getBytes(charset));
		}
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
