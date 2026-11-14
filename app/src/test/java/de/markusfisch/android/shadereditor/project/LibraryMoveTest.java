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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.markusfisch.android.shadereditor.project.LibraryMove.Result;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.LibraryTexture;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

public class LibraryMoveTest {
	private static final String MAIN = "void main() {\n" +
			"\tgl_FragColor = vec4(1.0);\n}\n";
	private static final byte[] NOISE = Pictures.png(1);
	private static final byte[] SKY_STRIP = Pictures.png(2);
	private static final long DAY = MoveDatabase.DAY;

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// The process dies at a step: nothing after it happens, and
	// nothing catches it.
	private static final class Death extends Error {
	}

	// One start of the app: what it keeps is read from storage again.
	private final class Start {
		final File root;
		final File library;
		final DirectoryProjectFiles files;
		final MoveNote note;
		final LibrarySettings settings;
		final Previews previews;
		final Map<String, Long> stamped = new TreeMap<>();
		long lastOpened;
		boolean selectionFails;
		int steps;

		Start(File root) {
			this.root = root;
			library = new File(root, "user-files/Shaders");
			library.mkdirs();
			files = new DirectoryProjectFiles(library);
			note = new MoveNote(new File(root, "library/moved"));
			settings = new LibrarySettings(new File(root,
					"library/settings"));
			previews = new Previews(new File(root, "library/previews"));
		}

		Result move(MoveDatabase database) {
			return move(database, 0, -1);
		}

		// Dies before step die, counted from 0; never when negative.
		Result move(MoveDatabase database, long template, int die) {
			LibraryMove move = new LibraryMove(database, files, note,
					settings, previews, (path, time) -> {
				stamped.put(path.toString(), time);
				new File(library, path.toString()).setLastModified(time);
			}, this::select, "Untitled",
					new LibraryMove.Chosen(template, lastOpened));
			move.between = () -> {
				if (steps++ == die) {
					throw new Death();
				}
			};
			return move.run();
		}

		// What a session keeps as the file it shows, as a file.
		private boolean select(ProjectPath path) {
			try {
				write(new File(root, "shown"), path.toString());
				return !selectionFails;
			} catch (IOException e) {
				return false;
			}
		}

		String text(String path) throws IOException {
			File file = new File(library, path);
			return file.isFile() ? new String(Files.readAllBytes(
					file.toPath()), StandardCharsets.UTF_8) : null;
		}

		// Every file of the library and what the app keeps beside it,
		// with its bytes, and the file times.
		Map<String, String> everything() throws IOException {
			Map<String, String> all = new TreeMap<>();
			collect(all, library, "");
			for (ProjectFiles.Entry entry : ((ProjectFiles.Entries)
					files.list(null)).entries()) {
				if (entry.folder()) {
					continue;
				}
				ProjectPath path = path(entry.name());
				all.put("time of " + entry.name(), String.valueOf(
						new File(library, entry.name()).lastModified()));
				all.put("quality of " + entry.name(),
						String.valueOf(settings.quality(path)));
				Previews.Picture picture = previews.get(path);
				all.put("picture of " + entry.name(), picture == null
						? "none"
						: StoredFiles.revision(picture.png()) + " of " +
								picture.revision());
			}
			all.put("template", String.valueOf(settings.template()));
			File shown = new File(root, "shown");
			all.put("shown", shown.isFile() ? new String(
					Files.readAllBytes(shown.toPath()),
					StandardCharsets.UTF_8) : "none");
			all.put("selected", String.valueOf(note.selected()));
			return all;
		}

		private void collect(Map<String, String> all, File folder,
				String prefix) throws IOException {
			for (File file : folder.listFiles()) {
				if (file.isDirectory()) {
					collect(all, file, prefix + file.getName() + "/");
				} else {
					all.put(prefix + file.getName(), StoredFiles.revision(
							Files.readAllBytes(file.toPath())));
				}
			}
		}
	}

	private Start start(File root, long lastOpened) {
		Start start = new Start(root);
		start.lastOpened = lastOpened;
		return start;
	}

	private MoveDatabase database() {
		MoveDatabase database = new MoveDatabase();
		database.textures.put("noise", new LibraryTexture(false, 256, 128));
		database.textures.put("sky", new LibraryTexture(true, 64, 96));
		database.images.put("noise", NOISE);
		database.images.put("sky", SKY_STRIP);
		database.add(1, "Plasma", "uniform sampler2D noise;\n" + MAIN);
		database.add(2, "Plasma", MAIN);
		database.add(3, null, "// untitled\n" + MAIN);
		database.add(4, "", "// empty\n" + MAIN);
		database.add(5, "a/b: c?", "// invalid\n" + MAIN);
		database.add(6, "Sky", "uniform samplerCube sky;\n" + MAIN);
		database.add(7, "Feedback", "uniform sampler2D backbuffer;" +
				"///p:noise;\n" + MAIN);
		database.add(8, "plasma.glsl", "// lower case\n" + MAIN);
		return database;
	}

	@Test
	public void everyShaderBecomesAFileThatBuilds() throws IOException {
		MoveDatabase database = database();
		Start start = new Start(temp.getRoot());
		Result result = start.move(database);

		assertTrue(result.failed().isEmpty());
		assertTrue(result.skipped().isEmpty());
		assertEquals(List.of("1 Plasma.frag", "2 Plasma 2.frag",
				"3 Untitled.frag", "4 Untitled 2.frag", "5 a_b_ c_.frag",
				"6 Sky.frag", "7 Feedback.frag", "8 plasma 3.frag"),
				moved(result));
		assertEquals(Set.of("Plasma.frag", "Plasma 2.frag", "Untitled.frag",
				"Untitled 2.frag", "a_b_ c_.frag", "Sky.frag",
				"Feedback.frag", "plasma 3.frag", "textures/noise.png",
				"textures/sky.png", "presets/noise.frag"), files(start));
		assertEquals("#pragma se input \"textures/noise.png\" " +
				"min=nearest mag=linear wrap=repeat\n" +
				"uniform sampler2D noise;\n" + MAIN,
				start.text("Plasma.frag"));
		assertArrayEquals(NOISE, Files.readAllBytes(new File(start.library,
				"textures/noise.png").toPath()));
		assertArrayEquals(SKY_STRIP, Files.readAllBytes(new File(
				start.library, "textures/sky.png").toPath()));
		assertTrue(start.text("Feedback.frag").contains(
				"initial=\"presets/noise.frag\""));
		for (LibraryMove.Moved moved : result.moved()) {
			Preparation preparation = Preparation.prepare(start.files,
					moved.path(), ProjectLimits.DEFAULT, BuiltinNames.ALL);
			assertNotNull(moved.path() + ": " + preparation.diagnostics(),
					preparation.plan());
		}
	}

	@Test
	public void pictureQualityDateAndTemplateGoWithTheFile()
			throws IOException {
		MoveDatabase database = new MoveDatabase();
		database.rows.add(new MoveDatabase.Row(3, "Clouds", MAIN, .25f, 5 * DAY,
				Pictures.png(9)));
		database.rows.add(new MoveDatabase.Row(4, "Bare", MAIN, 1f, 0, null));
		Start start = new Start(temp.getRoot());
		Result result = start.move(database, 3, -1);

		assertEquals(List.of("3 Clouds.frag", "4 Bare.frag"), moved(result));
		Previews.Picture picture = start.previews.get(path("Clouds.frag"));
		assertArrayEquals(Pictures.png(9), picture.png());
		// The picture is of the file as it is now.
		assertEquals(((ProjectFiles.Content) start.files.read(
				path("Clouds.frag"))).revision(), picture.revision());
		assertNull(start.previews.get(path("Bare.frag")));
		assertEquals(.25f, start.settings.quality(path("Clouds.frag")), 0f);
		assertEquals(1f, start.settings.quality(path("Bare.frag")), 0f);
		assertEquals(path("Clouds.frag"), start.settings.template());
		assertEquals(5 * DAY, new File(start.library,
				"Clouds.frag").lastModified());
		assertEquals(Map.of("Clouds.frag", 5 * DAY), start.stamped);
		// The note says which file a database shader became.
		assertEquals(path("Clouds.frag"),
				new Start(temp.getRoot()).note.moved(3));
	}

	@Test
	public void aSecondRunMovesNothingAgain() throws IOException {
		MoveDatabase database = database();
		Start first = new Start(temp.getRoot());
		first.move(database);
		Map<String, String> before = first.everything();
		database.conversions = 0;

		Start second = new Start(temp.getRoot());
		Result result = second.move(database, 1, -1);
		assertTrue(result.moved().isEmpty());
		assertTrue(result.failed().isEmpty());
		assertEquals(8, result.skipped().size());
		assertEquals(path("Plasma 2.frag"), result.skipped().get(1).path());
		assertEquals(before, second.everything());
		// Not even read again, and a template chosen since stays.
		assertEquals(0, database.conversions);
		assertNull(second.settings.template());
	}

	@Test
	public void whatTheUserDidToAMovedFileStays() throws IOException {
		MoveDatabase database = database();
		Start first = new Start(temp.getRoot());
		first.move(database);
		write(new File(first.library, "Plasma.frag"), "// mine\n");
		write(new File(first.library, "textures/noise.png"), "painted");
		assertTrue(new File(first.library, "Sky.frag").delete());
		assertTrue(new File(first.library, "Feedback.frag").renameTo(
				new File(first.library, "Loop.frag")));
		first.settings.setQuality(path("Plasma.frag"), .5f);

		Start second = new Start(temp.getRoot());
		// A shader that came into the database since reads the same
		// texture, and gets a name that was not given before.
		database.add(9, "Sky", "uniform sampler2D noise;\n" + MAIN);
		Result result = second.move(database);
		assertEquals(List.of("9 Sky 2.frag"), moved(result));
		assertEquals("// mine\n", second.text("Plasma.frag"));
		assertEquals("painted", second.text("textures/noise.png"));
		assertNull(second.text("Sky.frag"));
		assertNull(second.text("Feedback.frag"));
		assertNotNull(second.text("Loop.frag"));
		assertEquals(.5f, second.settings.quality(path("Plasma.frag")), 0f);
	}

	@Test
	public void aDeathAtAnyStepAndASecondRunGiveTheSameLibrary()
			throws IOException {
		MoveDatabase database = database();
		Start whole = new Start(temp.newFolder("whole"));
		whole.lastOpened = 7;
		whole.move(database, 2, -1);
		assertEquals("Feedback.frag", whole.everything().get("shown"));
		Map<String, String> expected = whole.everything();
		int steps = whole.steps;
		assertTrue(steps > 40);

		for (int die = 0; die < steps; ++die) {
			File root = temp.newFolder("death" + die);
			try {
				start(root, 7).move(database, 2, die);
				throw new AssertionError("no step " + die);
			} catch (Death death) {
				// The process is gone; storage is what it left.
			}
			// It dies again at its second step, then runs.
			try {
				start(root, 7).move(database, 2, 1);
			} catch (Death death) {
				// Gone again.
			}
			Start last = start(root, 7);
			Result result = last.move(database, 2, -1);
			assertTrue("death at " + die + ": " + result.failed(),
					result.failed().isEmpty());
			assertEquals("death at " + die, expected, last.everything());
			assertEquals("death at " + die, 8,
					result.moved().size() + result.skipped().size());
		}
	}

	@Test
	public void aNameInTheLibraryIsNotTakenAgain() throws IOException {
		Start start = new Start(temp.getRoot());
		write(new File(start.library, "plasma.FRAG"), "// mine\n");
		new File(start.library, "Untitled.frag").mkdirs();
		MoveDatabase database = new MoveDatabase()
				.add(1, "Plasma", MAIN)
				.add(2, null, MAIN);
		Result result = start.move(database);
		assertEquals(List.of("1 Plasma 2.frag", "2 Untitled 2.frag"),
				moved(result));
		assertEquals("// mine\n", start.text("plasma.FRAG"));
	}

	@Test
	public void aTextureThatCannotBeReadLeavesItsShaderInTheDatabase()
			throws IOException {
		MoveDatabase database = database();
		database.broken.put("noise", "The library texture \"noise\" " +
				"could not be read from the database; try again");
		Start first = new Start(temp.getRoot());
		Result result = first.move(database);
		assertEquals(List.of("1 Plasma: The library texture \"noise\" " +
						"could not be read from the database; try again",
				"7 Feedback: The library texture \"noise\" could not be " +
						"read from the database; try again"),
				failed(result));
		assertEquals(6, result.moved().size());
		assertNull(first.text("Plasma.frag"));
		// The next start tries them again, under the names they got.
		database.broken.clear();
		Result second = new Start(temp.getRoot()).move(database);
		assertEquals(List.of("1 Plasma.frag", "7 Feedback.frag"),
				moved(second));
		assertEquals(6, second.skipped().size());
		assertTrue(second.failed().isEmpty());
	}

	@Test
	public void anotherFileWhereATextureGoesFailsOnlyItsShaders()
			throws IOException {
		Start start = new Start(temp.getRoot());
		write(new File(start.library, "textures/noise.png"), "mine");
		write(new File(start.library, "textures/sky.png"),
				new String(SKY_STRIP, StandardCharsets.ISO_8859_1),
				StandardCharsets.ISO_8859_1);
		Result result = start.move(database());
		assertEquals(List.of("1 Plasma: textures/noise.png is another " +
				"file than the one this shader reads; rename it, then " +
				"the shader is moved at the next start",
				"7 Feedback: textures/noise.png is another file than " +
						"the one this shader reads; rename it, then the " +
						"shader is moved at the next start"),
				failed(result));
		// The same bytes are the same texture.
		assertNotNull(start.text("Sky.frag"));
		assertEquals("mine", start.text("textures/noise.png"));
	}

	@Test
	public void aLibraryThatTakesNoFileMovesNothing() throws IOException {
		Start start = new Start(temp.getRoot());
		assertTrue(start.library.setWritable(false));
		try {
			Result result = start.move(new MoveDatabase().add(1, "A", MAIN));
			assertTrue(result.moved().isEmpty());
			assertEquals(1, result.failed().size());
			assertTrue(result.failed().get(0).reason(),
					result.failed().get(0).reason().startsWith(
							"A.frag cannot be written: "));
		} finally {
			start.library.setWritable(true);
		}
		assertNull(new Start(temp.getRoot()).note.moved(1));
		assertEquals(List.of("1 A.frag"), moved(new Start(temp.getRoot())
				.move(new MoveDatabase().add(1, "A", MAIN))));
	}

	@Test
	public void whatTheConversionChangedIsToldWithTheShader()
			throws IOException {
		MoveDatabase database = new MoveDatabase().add(1, "Drops",
				"void main() {\n\tdiscard;\n}\n");
		Result result = new Start(temp.getRoot()).move(database);
		assertEquals(1, result.moved().size());
		assertFalse(result.moved().get(0).changes().isEmpty());
		assertEquals(2, result.moved().get(0).changes().get(0).line());
	}

	// A name that was noted says only that it was meant for the
	// shader. Another file under it is nobody's proof of a copy.
	@Test
	public void aFileUnderANotedNameThatIsNotTheShaderIsNotTakenForIt()
			throws IOException {
		Start first = new Start(temp.getRoot());
		assertTrue(first.note.noteName(1, "Alpha.frag"));
		write(new File(first.library, "Alpha.frag"), "// someone else's\n");
		MoveDatabase database = new MoveDatabase().add(1, "Alpha", MAIN);

		Result result = first.move(database);
		assertTrue(result.moved().isEmpty());
		assertEquals(List.of("1 Alpha: Alpha.frag is another file than " +
				"this shader; it is moved as Alpha 2.frag at the next " +
				"start"), failed(result));
		assertEquals("// someone else's\n", first.text("Alpha.frag"));
		assertNull(new Start(temp.getRoot()).note.moved(1));
		assertNull(first.previews.get(path("Alpha.frag")));

		Start second = new Start(temp.getRoot());
		assertEquals(List.of("1 Alpha 2.frag"), moved(second.move(database)));
		assertEquals(MAIN, second.text("Alpha 2.frag"));
		assertEquals("// someone else's\n", second.text("Alpha.frag"));
	}

	@Test
	public void aRowThatCannotBeReadFailsAloneAndTheOthersMove()
			throws IOException {
		MoveDatabase database = new MoveDatabase()
				.add(1, "Alpha", MAIN).add(2, "Beta", MAIN);
		database.unreadable.add(1L);
		Start start = new Start(temp.getRoot());
		Result result = start.move(database);
		assertNull(result.problem());
		assertEquals(List.of("1 Alpha: it cannot be read: database read " +
				"failed"), failed(result));
		assertEquals(List.of("2 Beta.frag"), moved(result));
		database.unreadable.clear();
		assertEquals(List.of("1 Alpha.frag"),
				moved(new Start(temp.getRoot()).move(database)));
	}

	@Test
	public void aDatabaseThatCannotBeListedIsOneFailureOfTheWholeRun()
			throws IOException {
		MoveDatabase database = new MoveDatabase().add(1, "Alpha", MAIN);
		database.unlistable = "disk I/O error";
		Start start = new Start(temp.getRoot());
		Result result = start.move(database);
		assertEquals("the database cannot be read: disk I/O error",
				result.problem());
		assertTrue(result.moved().isEmpty());
		assertTrue(result.failed().isEmpty());
		assertTrue(files(start).isEmpty());
	}

	@Test
	public void theFileHoldsTheTextTheUserWrote() throws IOException {
		String source = "// Gr\u00fc\u00dfe \u4e16\u754c\r\n" +
				"uniform sampler2D noise; ///min:l;mag:l;\n" +
				"/* \u00e9t\u00e9 */ void main() {\u00a0\n" +
				"\tgl_FragColor = vec4(1.0); // \ud83d\ude00\n}\n";
		MoveDatabase database = database();
		database.rows.clear();
		database.add(1, "Alpha", source);
		Start start = new Start(temp.getRoot());
		Result result = start.move(database);

		assertEquals(List.of("1 Alpha.frag"), moved(result));
		// Every character is there; only what the file form needs
		// was added, and line ends are line feeds.
		assertEquals("// Gr\u00fc\u00dfe \u4e16\u754c\n" +
				"#pragma se input \"textures/noise.png\" " +
				"wrap=repeat\n" +
				"uniform sampler2D noise;\n" +
				"/* \u00e9t\u00e9 */ void main() {\u00a0\n" +
				"\tgl_FragColor = vec4(1.0); // \ud83d\ude00\n}\n",
				start.text("Alpha.frag"));
		// And it draws: the engine reads a space for each.
		Preparation preparation = Preparation.prepare(start.files,
				path("Alpha.frag"), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNotNull(preparation.diagnostics().toString(),
				preparation.plan());
	}

	@Test
	public void aShaderThatCannotBeKeptAsWrittenStaysInTheDatabase()
			throws IOException {
		// The old renderer read "float ab": a file would read "a b".
		MoveDatabase database = new MoveDatabase()
				.add(1, "Joined", "float a\u200bb;\n" + MAIN)
				.add(2, "Fine", MAIN);
		Start start = new Start(temp.getRoot());
		Result result = start.move(database);
		assertEquals(List.of("2 Fine.frag"), moved(result));
		assertEquals(1, result.failed().size());
		// Nor a comment that is rewritten and holds more than ASCII.
		assertEquals(List.of("1 Lost: line 1 has the character \u2603 in " +
				"a comment the conversion takes away"), failed(
				new Start(temp.newFolder()).move(new MoveDatabase().add(1,
						"Lost", "uniform sampler2D backbuffer; " +
								"///min:l;mag:l; \u2603\n" + MAIN))));
		assertTrue(result.failed().get(0).reason(),
				result.failed().get(0).reason().startsWith(
						"line 1 has the character"));
		assertNull(start.text("Joined.frag"));
		assertNull(new Start(temp.getRoot()).note.moved(1));
	}

	@Test
	public void aNoteThatCannotBeReadMovesNothing() throws IOException {
		MoveDatabase database = new MoveDatabase().add(1, "Alpha", MAIN);
		Start first = new Start(temp.getRoot());
		first.move(database);
		File note = new File(temp.getRoot(), "library/moved");
		assertTrue(note.setReadable(false, false));
		try {
			assertFalse(note.canRead());
			Start second = new Start(temp.getRoot());
			Result result = second.move(database);
			assertEquals("the note of what was moved before cannot be " +
					"read, so nothing is moved", result.problem());
			assertTrue(result.moved().isEmpty());
			assertNull(second.text("Alpha 2.frag"));
		} finally {
			note.setReadable(true);
		}
		assertEquals(1, new Start(temp.getRoot()).move(database)
				.skipped().size());
	}

	@Test
	public void aMoveReadsTheNoteWhenItRunsNotWhenItWasMade()
			throws IOException {
		MoveDatabase database = new MoveDatabase().add(1, "Alpha", MAIN);
		Start first = new Start(temp.getRoot());
		Start second = new Start(temp.getRoot());
		assertEquals(1, first.move(database).moved().size());
		Result late = second.move(database);
		assertTrue(late.moved().isEmpty());
		assertEquals(1, late.skipped().size());
		assertNull(second.text("Alpha 2.frag"));
	}

	@Test
	public void aShaderLargerThanAFileMayBeIsNotMoved() throws IOException {
		MoveDatabase database = new MoveDatabase()
				.add(1, "Large", "//" + "x".repeat(16 * 1024 * 1024) +
						"\n" + MAIN)
				.add(2, "Small", MAIN);
		Start start = new Start(temp.getRoot());
		Result result = start.move(database);
		assertEquals(List.of("1 Large: it is larger than 16 MiB, the " +
				"most text a file may hold"), failed(result));
		assertEquals(List.of("2 Small.frag"), moved(result));
		// Told by its size, before its text was read.
		assertEquals(1, database.conversions);
		assertNull(start.text("Large.frag"));
	}

	@Test
	public void whatTheLibraryShowsFirstIsSettledOnce() throws IOException {
		MoveDatabase database = database();
		Start first = start(temp.getRoot(), 7);
		first.selectionFails = true;
		first.move(database);
		// It could not be kept, so it is not settled.
		assertEquals("false", first.everything().get("selected"));

		Start second = start(temp.getRoot(), 7);
		assertTrue(second.move(database).moved().isEmpty());
		assertEquals("Feedback.frag", second.everything().get("shown"));
		assertEquals("true", second.everything().get("selected"));

		// The user opens another shader; no later start undoes that.
		write(new File(temp.getRoot(), "shown"), "Sky.frag");
		Start third = start(temp.getRoot(), 7);
		third.move(database);
		assertEquals("Sky.frag", third.everything().get("shown"));
	}

	@Test
	public void aShaderThatWasNotMovedIsNotShownByALaterStart()
			throws IOException {
		MoveDatabase database = database();
		database.broken.put("noise", "broken");
		Start first = start(temp.getRoot(), 7);
		assertEquals(2, first.move(database).failed().size());
		assertEquals("none", first.everything().get("shown"));
		assertEquals("true", first.everything().get("selected"));
		database.broken.clear();
		Start second = start(temp.getRoot(), 7);
		assertEquals(2, second.move(database).moved().size());
		assertEquals("none", second.everything().get("shown"));
	}

	private static List<String> moved(Result result) {
		List<String> moved = new ArrayList<>();
		for (LibraryMove.Moved shader : result.moved()) {
			moved.add(shader.id() + " " + shader.path());
		}
		return moved;
	}

	private static List<String> failed(Result result) {
		List<String> failed = new ArrayList<>();
		for (LibraryMove.Failed shader : result.failed()) {
			failed.add(shader.id() + " " + shader.title() + ": " +
					shader.reason());
		}
		return failed;
	}

	private static Set<String> files(Start start) throws IOException {
		Set<String> files = new HashSet<>();
		for (String key : start.everything().keySet()) {
			if (!key.contains(" of ") && !key.equals("template") &&
					!key.equals("shown") && !key.equals("selected")) {
				files.add(key);
			}
		}
		return files;
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
