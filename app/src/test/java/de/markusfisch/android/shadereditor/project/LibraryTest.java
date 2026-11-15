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

	// Both are there: the target's name was free when the redo was
	// noted, so it is what was put aside, and the folder under the
	// library's name was made since. It goes aside too, under the
	// next free name, and nobody has to remove anything by hand.
	@Test
	public void aFolderMadeUnderTheLibrarysNameDuringARedoIsPutAsideToo()
			throws IOException {
		Library library = library();
		move(library, 0);
		write(new File(temp.getRoot(), "files/library-redo"),
				"Shaders before redo\nlibrary before redo\n");
		File aside = new File(temp.getRoot(),
				"user-files/Shaders before redo");
		assertTrue(library.folder().renameTo(aside));
		// Another app makes the folder again through the provider.
		write(new File(library.folder(), "Made since.frag"), MAIN);
		List<String> relocated = new ArrayList<>();

		LibraryMove.Result result = library.move(database(), "Untitled",
				new LibraryMove.Chosen(0, 0), null, (from, to) ->
						relocated.add(from.getName() + " -> " + to.getName()));
		assertNull(result.problem());
		assertEquals(2, result.moved().size());
		assertTrue(new File(aside, "Plasma.frag").isFile());
		assertTrue(new File(temp.getRoot(),
				"user-files/Shaders before redo 2/Made since.frag").isFile());
		assertFalse(new File(library.folder(), "Made since.frag").exists());
		assertTrue(new File(temp.getRoot(),
				"files/library before redo/moved").isFile());
		assertFalse(new File(temp.getRoot(), "files/library-redo").exists());
		// The session goes with the library the redo was begun for.
		assertEquals(List.of("Shaders -> Shaders before redo"), relocated);
	}

	// While a redo is unfinished nothing is written into what the app
	// keeps about the library, whoever asks, so the folder that was
	// just put aside is not made again beside it.
	@Test
	public void nothingIsWrittenIntoWhatIsKeptWhileARedoIsUnfinished()
			throws IOException {
		Library library = library();
		move(library, 0);
		File files = new File(temp.getRoot(), "files");
		write(new File(files, "library-redo"),
				"Shaders before redo\nlibrary before redo\n");
		assertTrue(library.folder().renameTo(new File(temp.getRoot(),
				"user-files/Shaders before redo")));
		assertTrue(new File(files, "library").renameTo(
				new File(files, "library before redo")));

		assertFalse(library.previews().put(path("Alpha.frag"), "r",
				Pictures.png(1)));
		assertFalse(library.settings().setQuality(path("Alpha.frag"), .5f));
		assertFalse(library.settings().setTemplate(path("Alpha.frag")));
		assertFalse(library.note().noteName(9, "Nine.frag"));
		assertFalse(new File(files, "library").exists());
		// So the redo can still be finished.
		assertNull(library.finishRedo(null));
		assertTrue(library.previews().put(path("Alpha.frag"), "r",
				Pictures.png(1)));
	}

	// Every change goes in one way: the redo is finished first, or
	// the change is refused with the reason.
	@Test
	public void aQualityOrAPictureIsNotKeptPastAnUnfinishedRedo()
			throws IOException {
		Library library = library();
		move(library, 0);
		String blocked = "a redo of the move is not finished: what was " +
				"open of the library cannot follow it";
		assertEquals(blocked, library.putAside((from, to) -> false));
		assertEquals(blocked, library.setQuality(path("Plasma.frag"), .5f,
				(from, to) -> false));
		assertEquals(blocked, library.setPreview(path("Plasma.frag"), "r",
				Pictures.png(1), (from, to) -> false));
		assertFalse(new File(temp.getRoot(), "files/library").exists());

		// Finished on the way in, the change is made in the new one.
		assertNull(library.setQuality(path("Plasma.frag"), .5f,
				(from, to) -> true));
		assertNull(library.setPreview(path("Plasma.frag"), "r",
				Pictures.png(1), null));
		assertEquals(.5f, library.settings().quality(path("Plasma.frag")),
				0f);
		assertEquals(1f, new LibrarySettings(new File(temp.getRoot(),
				"files/library before redo/settings"))
				.quality(path("Plasma.frag")), 0f);
	}

	// What is kept is not changed while it still has to follow a
	// rename: the change would land on the wrong file.
	@Test
	public void nothingIsChangedWhileARenameIsNotFollowed()
			throws IOException {
		Library library = library();
		move(library, 2);
		File settings = new File(temp.getRoot(), "files/library/settings");
		FileTexts.Kept kept = library.kept();
		assertTrue(settings.setReadable(false, false));
		String waiting = "what is kept about the files of the library " +
				"has not followed a rename yet; try again";
		try {
			assertFalse(settings.canRead());
			assertTrue(kept.follow(new Journal.Move(path("Clouds.frag"),
					path("Sky.frag"))));
			assertEquals(waiting, library.setPreview(path("Clouds.frag"),
					"r", Pictures.png(8), null));
			assertEquals(waiting, library.create(null, "Untitled",
					MAIN.getBytes(StandardCharsets.UTF_8), null, null)
					.problem());
			assertEquals(waiting, move(library, 2).problem());
		} finally {
			settings.setReadable(true);
		}
		// Followed on the way in, the change is made.
		assertNull(library.setPreview(path("Clouds.frag"), "r",
				Pictures.png(8), null));
		assertEquals(.5f, library.settings().quality(path("Sky.frag")), 0f);
		assertArrayEquals(Pictures.png(5),
				library.previews().get(path("Sky.frag")).png());
		assertArrayEquals(Pictures.png(8),
				library.previews().get(path("Clouds.frag")).png());
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

	// A shader of several files made elsewhere, as an import from
	// ShaderToy is: a folder of its own, or nothing at all.
	@Test
	public void aFolderOfTextsGetsAnUnusedNameOrIsNotMade()
			throws IOException {
		Library library = library();
		Map<String, String> texts = new java.util.LinkedHashMap<>();
		texts.put("README.md", "# Ink Drop\n");
		texts.put("bufferA.frag", MAIN);
		texts.put("main.frag", MAIN);

		Library.Created first = library.addFolder("Ink Drop", "ShaderToy",
				texts, "main.frag", null);
		assertNull(first.problem());
		assertEquals(path("Ink Drop/main.frag"), first.path());
		assertEquals(Map.of("Ink Drop/README.md", "",
				"Ink Drop/bufferA.frag", "", "Ink Drop/main.frag", ""),
				names(library.folder()));
		assertEquals(MAIN, text(new File(library.folder(),
				"Ink Drop/bufferA.frag")));
		// A taken name gets a number, whatever its case, and a title
		// that is no name is made one.
		write(new File(library.folder(), "ink drop 2"), "a file\n");
		assertEquals(path("ink drop 3/main.frag"), library.addFolder(
				"ink drop", "ShaderToy", texts, "main.frag", null).path());
		assertEquals(path("a_b/main.frag"), library.addFolder(" a/b ",
				"ShaderToy", texts, "main.frag", null).path());
		assertEquals(path("ShaderToy/main.frag"), library.addFolder(null,
				"ShaderToy", texts, "main.frag", null).path());
		assertEquals(MAIN, text(new File(library.folder(),
				"Ink Drop/main.frag")));

		// One file that cannot be written leaves no folder behind, and
		// a path that leaves the folder is not written at all.
		texts.put("main.frag/below", MAIN);
		Library.Created refused = library.addFolder("Half", "ShaderToy",
				texts, "main.frag", null);
		assertNull(refused.path());
		assertTrue(refused.problem(), refused.problem().startsWith(
				"Half/main.frag/below cannot be written: "));
		assertFalse(new File(library.folder(), "Half").exists());
		texts.remove("main.frag/below");
		texts.put("../Out.frag", MAIN);
		assertEquals("Half/../Out.frag is not a path of a file",
				library.addFolder("Half", "ShaderToy", texts, "main.frag",
						null).problem());
		assertFalse(new File(library.folder(), "Out.frag").exists());
		assertFalse(new File(library.folder(), "Half").exists());
	}

	@Test
	public void aNewShaderIsAFileFromTheBuiltInTemplate()
			throws IOException {
		Library library = library();
		byte[] builtIn = MAIN.getBytes(StandardCharsets.UTF_8);

		Library.Created first = library.create(null, "Untitled", builtIn,
				Pictures.png(7), null);
		assertNull(first.problem());
		assertEquals(path("Untitled.frag"), first.path());
		assertEquals(MAIN, text(new File(library.folder(),
				"Untitled.frag")));
		Previews.Picture picture = library.previews().get(first.path());
		assertArrayEquals(Pictures.png(7), picture.png());
		assertEquals(((ProjectFiles.Content) library.files().read(
				first.path())).revision(), picture.revision());
		// It runs as it is.
		assertNotNull(Preparation.prepare(library.files(), first.path(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL).plan());

		// The next ones get the next names, whatever the case.
		assertEquals(path("Untitled 2.frag"), library.create(null,
				"Untitled", builtIn, null, null).path());
		assertEquals(path("untitled 3.frag"), library.create("untitled",
				"Untitled", builtIn, null, null).path());
		assertEquals(path("a_b.frag"), library.create(" a/b.glsl ",
				"Untitled", builtIn, null, null).path());
		assertNull(library.previews().get(path("Untitled 2.frag")));
		assertEquals(MAIN, text(new File(library.folder(),
				"Untitled.frag")));
	}

	@Test
	public void aNewShaderIsACopyOfTheOneChosenForIt() throws IOException {
		Library library = library();
		move(library, 2);
		byte[] builtIn = "// built in\n".getBytes(StandardCharsets.UTF_8);

		Library.Created created = library.create("Clouds", "Untitled",
				builtIn, Pictures.png(7), null);
		assertEquals(path("Clouds 2.frag"), created.path());
		assertEquals(MAIN, text(new File(library.folder(),
				"Clouds 2.frag")));
		assertEquals(.5f, library.settings().quality(created.path()), 0f);
		assertArrayEquals(Pictures.png(5),
				library.previews().get(created.path()).png());
		// The chosen file as it is now, not as it was moved.
		write(new File(library.folder(), "Clouds.frag"), "// edited\n");
		assertEquals("// edited\n", text(new File(library.folder(),
				library.create(null, "Untitled", builtIn, null, null).path()
						.toString())));

		// Gone, or no text: the built-in one, as when the id was gone.
		write(new File(library.folder(), "Clouds.frag"),
				new String(Pictures.png(1), StandardCharsets.ISO_8859_1),
				StandardCharsets.ISO_8859_1);
		Library.Created plain = library.create("Plain", "Untitled",
				builtIn, Pictures.png(7), null);
		assertEquals("// built in\n", text(new File(library.folder(),
				"Plain.frag")));
		assertEquals(1f, library.settings().quality(plain.path()), 0f);
		assertArrayEquals(Pictures.png(7),
				library.previews().get(plain.path()).png());
		assertTrue(new File(library.folder(), "Clouds.frag").delete());
		assertEquals("// built in\n", text(new File(library.folder(),
				library.create("Other", "Untitled", builtIn, null, null).path()
						.toString())));
	}

	@Test
	public void aLibraryThatTakesNoFileSaysWhy() {
		Library library = library();
		library.files();
		assertTrue(library.folder().setWritable(false));
		try {
			Library.Created created = library.create(null, "Untitled",
					MAIN.getBytes(StandardCharsets.UTF_8), null, null);
			assertNull(created.path());
			assertTrue(created.problem(), created.problem().startsWith(
					"Untitled.frag cannot be written: "));
		} finally {
			library.folder().setWritable(true);
		}
	}

	@Test
	public void aRedoThatCannotBeFinishedStopsNewShadersToo()
			throws IOException {
		Library library = library();
		move(library, 0);
		write(new File(temp.getRoot(), "files/library-redo"), "Shaders b");
		String blocked = "a redo of the move was begun, and its note " +
				"says nothing";
		Library.Created created = library.create(null, "Untitled",
				MAIN.getBytes(StandardCharsets.UTF_8), null, null);
		assertNull(created.path());
		assertEquals(blocked, created.problem());
		assertEquals(blocked, library.setTemplate(null, null));
		assertEquals(blocked, library.dress(path("Plasma.frag"),
				new byte[0], null, null));
		assertFalse(new File(library.folder(), "Untitled.frag").exists());
	}

	// The limit of a template is the limit of any text file.
	@Test
	public void aLargeTemplateIsCopiedWhole() throws IOException {
		Library library = library();
		String source = "//" + "x".repeat((1 << 20) + 1) + "\n" + MAIN;
		write(new File(library.folder(), "Template.frag"), source);
		assertNull(library.setTemplate(path("Template.frag"), null));
		Library.Created created = library.create(null, "Untitled",
				"// built in\n".getBytes(StandardCharsets.UTF_8), null, null);
		assertNull(created.problem());
		assertEquals(source, text(new File(library.folder(),
				created.path().toString())));
	}

	// Only a template that is gone or is no text gives way to the
	// built-in one; one that cannot be read now is told.
	@Test
	public void aTemplateThatCannotBeReadIsToldNotReplaced()
			throws IOException {
		Library library = library();
		File template = new File(library.folder(), "Template.frag");
		write(template, MAIN);
		assertNull(library.setTemplate(path("Template.frag"), null));
		assertTrue(template.setReadable(false, false));
		try {
			assertFalse(template.canRead());
			Library.Created created = library.create(null, "Untitled",
					"// built in\n".getBytes(StandardCharsets.UTF_8), null,
					null);
			assertNull(created.path());
			assertTrue(created.problem(), created.problem().startsWith(
					"Template.frag, which new shaders are copied from, " +
							"cannot be read: "));
			assertFalse(new File(library.folder(), "Untitled.frag")
					.exists());
		} finally {
			template.setReadable(true);
		}
	}

	// A copy in the root of a shader in a folder would look for its
	// includes and textures in the wrong place.
	@Test
	public void theTemplateMustBeInTheLibrarysRoot() throws IOException {
		Library library = library();
		write(new File(library.folder(), "sub/Template.frag"),
				"#include \"helper.glsl\"\n" + MAIN);
		write(new File(library.folder(), "sub/helper.glsl"), "// helper\n");
		write(new File(library.folder(), "Root.frag"), MAIN);
		assertNull(library.setTemplate(path("Root.frag"), null));
		assertEquals("the shader new ones are copied from must be in the " +
				"library itself, not in a folder of it",
				library.setTemplate(path("sub/Template.frag"), null));
		assertEquals(path("Root.frag"), library.settings().template());
		Library.Created created = library.create(null, "Untitled",
				"// built in\n".getBytes(StandardCharsets.UTF_8), null, null);
		assertNotNull(Preparation.prepare(library.files(), created.path(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL).plan());
	}

	// The file is there, so it is told with what could not be kept,
	// and trying that again makes no second file.
	@Test
	public void aNewShaderSaysWhatOfItsSettingsWasNotKept()
			throws IOException {
		Library library = library();
		write(new File(library.folder(), "Template.frag"), MAIN);
		assertNull(library.setTemplate(path("Template.frag"), null));
		assertTrue(library.settings().setQuality(path("Template.frag"),
				.25f));
		assertTrue(library.previews().put(path("Template.frag"), "r",
				Pictures.png(4)));
		byte[] builtIn = "// built in\n".getBytes(StandardCharsets.UTF_8);
		File state = new File(temp.getRoot(), "files/library");
		File pictures = new File(state, "previews");
		assertTrue(state.setWritable(false));
		assertTrue(pictures.setWritable(false));
		Library.Created created;
		try {
			created = library.create(null, "Untitled", builtIn, null, null);
			assertEquals(path("Untitled.frag"), created.path());
			assertEquals("the quality and the picture of Untitled.frag " +
					"could not be kept", created.problem());
			assertEquals(1f, library.settings().quality(created.path()),
					0f);
		} finally {
			state.setWritable(true);
			pictures.setWritable(true);
		}
		assertNull(library.dress(created.path(), builtIn, null, null));
		assertEquals(.25f, library.settings().quality(created.path()), 0f);
		assertArrayEquals(Pictures.png(4),
				library.previews().get(created.path()).png());
		assertFalse(new File(library.folder(), "Untitled 2.frag").exists());
	}

	// A picture copied from a file that changed since it was drawn
	// is still a picture of what it was drawn from.
	@Test
	public void aCopiedPictureKeepsTheRevisionItWasMadeFrom()
			throws IOException {
		Library library = library();
		move(library, 2);
		String drawnFrom = library.previews().get(path("Clouds.frag"))
				.revision();
		write(new File(library.folder(), "Clouds.frag"),
				"// edited since the picture\n" + MAIN);
		Library.Created created = library.create(null, "Untitled",
				MAIN.getBytes(StandardCharsets.UTF_8), null, null);
		assertNull(created.problem());
		assertEquals(drawnFrom,
				library.previews().get(created.path()).revision());
		assertFalse(drawnFrom.equals(((ProjectFiles.Content)
				library.files().read(created.path())).revision()));
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

	// An example or shared text: a file of the root, its texture in
	// textures/ once, nothing of the user's written over.
	@Test
	public void aShaderAddedFromTextIsAFileWithItsTextures()
			throws IOException {
		Library library = library();
		MoveDatabase database = database();
		database.rows.add(new MoveDatabase.Row(3, "Plasma",
				"uniform sampler2D noise;\n// again\n" + MAIN, .25f, 0,
				Pictures.png(7)));

		Library.Created first = library.add("Plasma", "Untitled",
				database.convert(1), 1f, null);
		assertNull(first.problem());
		assertEquals(path("Plasma.frag"), first.path());
		assertTrue(new File(library.folder(), "textures/noise.png").isFile());
		assertTrue(text(new File(library.folder(), "Plasma.frag"))
				.contains("textures/noise.png"));

		// The texture that is there is used; the name gets a number.
		Library.Created second = library.add("Plasma", "Untitled",
				database.convert(3), .25f, null);
		assertNull(second.problem());
		assertEquals(path("Plasma 2.frag"), second.path());
		assertEquals(.25f, library.settings().quality(second.path()), 0f);
		assertNotNull(library.previews().get(second.path()));

		// Another file where its texture goes stays as it is.
		write(new File(library.folder(), "textures/noise.png"), "mine");
		Library.Created refused = library.add("Plasma", "Untitled",
				database.convert(1), 1f, null);
		assertNull(refused.path());
		assertTrue(refused.problem(), refused.problem().contains(
				"textures/noise.png"));
		assertEquals("mine", text(new File(library.folder(),
				"textures/noise.png")));
		assertFalse(new File(library.folder(), "Plasma 3.frag").exists());
	}

	@Test
	public void aCopyLiesAsInItsFolderAndSharesWhatIsThere()
			throws IOException {
		Library library = library();
		File folder = library.folder();
		new File(folder, "Ink").mkdirs();
		new File(folder, "lib").mkdirs();
		Files.write(new File(folder, "Tilt.frag").toPath(),
				MAIN.getBytes(StandardCharsets.UTF_8));
		Files.write(new File(folder, "lib/noise.glsl").toPath(),
				new byte[]{1});

		ProjectPath tilt = path("Tilt.frag");
		ProjectPath sdf = path("lib/sdf.glsl");
		assertEquals(Map.of(tilt, path("Tilt 2.frag"), sdf, sdf),
				library.places(tilt, List.of(tilt, sdf)));

		ProjectPath ink = path("Ink/main.frag");
		ProjectPath pass = path("Ink/ink.frag");
		ProjectPath noise = path("lib/noise.glsl");
		assertEquals(Map.of(ink, path("Ink 2/main.frag"),
						pass, path("Ink 2/ink.frag")),
				library.places(ink, List.of(ink, pass, noise)));
	}

	// A lib/ the library has with other bytes is kept and named; a copy
	// that fails half way leaves nothing behind.
	@Test
	public void aCopyNamesWhatItKeptAndCanBeUndone() throws IOException {
		Library library = library();
		File folder = library.folder();
		new File(folder, "lib").mkdirs();
		Files.write(new File(folder, "lib/noise.glsl").toPath(),
				"mine".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(folder, "lib/same.glsl").toPath(),
				"same".getBytes(StandardCharsets.UTF_8));
		ProjectPath ink = path("Ink/main.frag");
		ProjectPath pass = path("Ink/ink.frag");
		ProjectPath noise = path("lib/noise.glsl");
		ProjectPath same = path("lib/same.glsl");
		Map<ProjectPath, ProjectPath> places = library.places(ink,
				List.of(ink, pass, noise, same));

		Library.Copy copy = library.copy(places);
		byte[] main = MAIN.getBytes(StandardCharsets.UTF_8);
		assertTrue(copy.write(ink, main, null) instanceof ProjectFiles.Done);
		assertTrue(copy.write(pass, main, null) instanceof ProjectFiles.Done);
		assertTrue(copy.write(noise, "theirs".getBytes(
				StandardCharsets.UTF_8), null) instanceof ProjectFiles.Done);
		assertTrue(copy.write(same, "same".getBytes(
				StandardCharsets.UTF_8), null) instanceof ProjectFiles.Done);
		assertEquals(List.of(ink, pass), copy.copied());
		assertEquals(List.of(noise), copy.kept());
		assertEquals("mine", text(new File(folder, "lib/noise.glsl")));
		assertTrue(new File(folder, "Ink/main.frag").isFile());

		assertNull(copy.undo());
		assertFalse(new File(folder, "Ink").exists());
		assertTrue(new File(folder, "lib/noise.glsl").isFile());
		assertEquals(List.of(), copy.copied());
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

	@Test
	public void onlyFilesOfTheRootAreConvertedFromShaderToy() {
		assertTrue(Library.convertsShaderToy(path("Ported.frag")));
		assertTrue(Library.convertsShaderToy(path("notes.txt")));
		assertFalse(Library.convertsShaderToy(path("Port/Image.frag")));
		assertFalse(Library.convertsShaderToy(path("Port/Common.glsl")));
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
