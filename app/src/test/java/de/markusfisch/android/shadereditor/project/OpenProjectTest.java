package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.OpenProject.Conflict;
import de.markusfisch.android.shadereditor.project.OpenProject.Failed;
import de.markusfisch.android.shadereditor.project.OpenProject.Outcome;
import de.markusfisch.android.shadereditor.project.OpenProject.Saved;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.JsonSyntax;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.LexedLine;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Syntax;

public class OpenProjectTest {
	private static final String SHADER = """
			#version 300 es
			precision mediump float;
			out vec4 color;
			void main() {
				color = vec4(1.0);
			}
			""";
	private static final Object NOTHING = new Object();

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private final Steps steps = new Steps();
	private final Steps.Queue worker = steps.worker;
	private final Steps.Queue main = steps.main;
	private File root;
	private File sessionsDir;
	private String id;
	private long budget = OpenProject.MAX_BUDGET;

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
		assertNull(prepare(project));
	}

	@Test
	public void aPictureIsToldByItsBytesNotItsName() throws IOException {
		write("shader.png", SHADER);
		File photo = new File(root, "photo.frag");
		Files.write(photo.toPath(), new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47,
				0x0d, 0x0a, 0x1a, 0x0a, 0});
		OpenProject project = open();
		project.looksOn(worker);
		List<Boolean> told = new ArrayList<>();

		project.picture(path("shader.png"), told::add);
		project.picture(path("photo.frag"), told::add);
		steps.settle();

		assertEquals(List.of(false, true), told);
	}

	@Test
	public void readsTheFolderTextsAsTheEditorHasThem() throws IOException {
		write("main.frag", SHADER);
		write("pass.frag", "#include \"lib/sdf.glsl\" // the field\n" +
				"#pragma se input \"main.frag\" uniform=last\n" +
				"// #include \"no.glsl\"\n");
		write("lib/sdf.glsl", "float sdf;\r\n");
		write("shader-project.json", "{}");
		File photo = new File(root, "noise.frag");
		Files.write(photo.toPath(), new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47,
				0x0d, 0x0a, 0x1a, 0x0a, 0});
		Files.write(new File(root, "lib/bytes.glsl").toPath(),
				new byte[]{(byte) 0xff, (byte) 0xfe, 0});
		OpenProject project = open();
		Document main = document(project, "main.frag");
		main.replace(0, 0, Text.of("// edited\n"));

		OpenProject.FolderTexts read = await(project::folderTexts);

		assertEquals(Set.of(path("main.frag"), path("pass.frag"),
				path("lib/sdf.glsl")), read.texts().keySet());
		assertEquals(List.of(path("lib/sdf.glsl"), path("main.frag")),
				read.names().get(path("pass.frag")));
		assertEquals(List.of(), read.names().get(path("main.frag")));
		assertEquals(main.text().toString(),
				read.texts().get(path("main.frag")).toString());
		// Not in memory: as stored, its lines as a document has them.
		assertEquals("float sdf;\n",
				read.texts().get(path("lib/sdf.glsl")).toString());
		assertNull(project.document(path("lib/sdf.glsl")));
		assertEquals(1, read.unread());
	}

	@Test
	public void runsMainWithoutManifestU10() throws IOException {
		write("main.frag", SHADER);
		write("other.glsl", SHADER);

		assertEquals(path("main.frag"), open().running());
	}

	@Test
	public void runsTheManifestEntryOverMainU10() throws IOException {
		write("main.frag", SHADER);
		write("passes/b.glsl", SHADER);
		write("shader-project.json", "{\"entry\": \"passes/b.glsl\"}");

		assertEquals(path("passes/b.glsl"), open().running());
	}

	@Test
	public void runsMainBesideAnInvalidManifestU10() throws IOException {
		write("main.frag", SHADER);
		write("shader-project.json", "{\"projectVersion\": 2}");

		OpenProject project = open();

		assertEquals(path("main.frag"), project.running());
		Preparation preparation = prepare(project);
		assertNull(preparation.plan());
		assertEquals(ProjectManifest.FILE,
				preparation.diagnostics().get(0).file());
	}

	@Test
	public void remembersRunThisFileOverManifestU10() throws IOException {
		write("main.frag", SHADER);
		write("b.glsl", SHADER);
		write("c.glsl", SHADER);
		String manifest = "{\"entry\": \"b.glsl\"}";
		write("shader-project.json", manifest);
		OpenProject project = open();

		project.runThisFile(path("c.glsl"));
		settle();

		assertEquals(path("c.glsl"), project.running());
		assertEquals(path("c.glsl"), open().running());
		assertEquals(manifest, read("shader-project.json"));
		write("shader-project.json", "{\"entry\": \"main.frag\"}");
		assertEquals(path("c.glsl"), open().running());
	}

	@Test
	public void forgetsAChosenFileThatIsGoneU10() throws IOException {
		write("main.frag", SHADER);
		write("c.glsl", SHADER);
		open().runThisFile(path("c.glsl"));
		settle();

		assertTrue(new File(root, "c.glsl").delete());

		assertEquals(path("main.frag"), open().running());
	}

	@Test
	public void findsWhatRunsWithoutReadingAFileWhole() {
		List<Long> limits = new ArrayList<>();
		ProjectFiles huge = new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				throw new AssertionError("read all of " + path);
			}

			@Override
			public Read read(ProjectPath path, long limit) {
				limits.add(limit);
				return new Failed(ProjectFiles.TOO_LARGE);
			}
		};

		assertNull(open(huge).running());

		assertEquals(List.of(Document.MAX_BYTES, Document.MAX_BYTES),
				limits);
	}

	@Test
	public void buildAndCopyReportAFileOverTheLimitWithoutReadingIt()
			throws IOException {
		write("main.frag", "#include \"big.glsl\"\n" + SHADER);
		ProjectPath big = path("big.glsl");
		DirectoryProjectFiles files = new DirectoryProjectFiles(root);
		OpenProject project = open(new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				assertNotEquals(big, path);
				return files.read(path);
			}

			@Override
			public Read read(ProjectPath path, long limit) {
				return big.equals(path)
						? new Failed(ProjectFiles.TOO_LARGE)
						: files.read(path, limit);
			}
		});
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;

		Preparation preparation = prepare(project);
		Map<ProjectPath, String> problems = await(done -> project.copyTo(
				destination, List.of(big, path("main.frag")), done));

		assertNull(preparation.plan());
		assertTrue(preparation.diagnostics().get(0).message(),
				preparation.diagnostics().get(0).message().contains(
						"big.glsl: it is too large"));
		assertEquals("Could not read big.glsl: it is too large",
				problems.get(big));
		assertNull(problems.get(path("main.frag")));
		assertNull(destination.text("big.glsl"));
	}

	// What a file sees of the others is what the last build read.
	@Test
	public void aFileSeesTheNamesOfTheFilesItIncludes() throws IOException {
		write("main.frag", "#include \"lib/common.glsl\"\n" + SHADER);
		write("lib/common.glsl", "// Half of x.\nfloat half(float x) " +
				"{ return x * 0.5; }\n");
		OpenProject project = open();
		shown(project, "main.frag");
		Knowledge.Scope scope = project.scope(path("main.frag"));
		// Nothing is built yet.
		assertEquals(List.of(), scope.names());

		prepare(project);
		assertEquals(List.of("half"), scope.names());
		Knowledge.Symbol half = scope.find("half").get(0);
		assertEquals("lib/common.glsl", half.file());
		assertEquals("float half(float x)", half.detail());
		assertEquals("Half of x.", half.documentation());
		assertEquals(List.of(), project.scope(path("lib/common.glsl"))
				.names());

		// The text of an edit is what the next build reads.
		edit(project, document(project, "lib/common.glsl"),
				"float third(float x) { return x / 3.0; }\n");
		prepare(project);
		assertEquals(List.of("third"), scope.names());
	}

	// The editor asks once for what the file it shows sees, and keeps
	// showing that file when it is renamed.
	@Test
	public void aRenamedFileStillSeesWhatItIncludes() throws IOException {
		write("main.frag", "#include \"lib/common.glsl\"\n" + SHADER);
		write("lib/common.glsl", "float half(float x) { return x * 0.5; }\n");
		OpenProject project = open();
		shown(project, "main.frag");
		project.runThisFile(path("main.frag"));
		Knowledge.Scope scope = project.scope(path("main.frag"));
		prepare(project);
		assertEquals(List.of("half"), scope.names());

		OpenProject.Moved moved = await(done -> project.rename(
				path("main.frag"), path("image.frag"), done));
		assertTrue(moved instanceof OpenProject.Renamed);
		prepare(project);
		assertEquals(List.of("half"), scope.names());
		assertEquals("lib/common.glsl", scope.find("half").get(0).file());
		// What is asked for the old path sees nothing, as before.
		assertEquals(List.of(), project.scope(path("main.frag")).names());
	}

	// A build for tuning has one number replaced: what the files know
	// of each other stays as the build before it left it.
	@Test
	public void aBuildForTuningLeavesWhatFilesKnowOfEachOther()
			throws IOException {
		write("main.frag", "#include \"lib/common.glsl\"\n" + SHADER);
		write("lib/common.glsl", "float half(float x) { return x * 0.5; }\n");
		OpenProject project = open();
		shown(project, "main.frag");
		Knowledge.Scope scope = project.scope(path("main.frag"));
		prepare(project);
		List<String> names = scope.names();
		assertEquals(List.of("half"), names);

		int at = ("#include \"lib/common.glsl\"\n" + SHADER).indexOf("1.0");
		OpenProject.Build tuned = await(done -> project.prepare(
				BuiltinNames.ALL, new OpenProject.Replacement(
						path("main.frag"), at, at + 3, "se_tune0"), done));
		assertTrue(tuned.preparation().temporary());
		// The same list: nothing was read again.
		assertSame(names, scope.names());
	}

	@Test
	public void editingAnotherFileKeepsWhatRunsU10() throws IOException {
		write("main.frag", "#include \"lib/common.glsl\"\n" + SHADER);
		write("lib/common.glsl", "// common\n");
		OpenProject project = open();
		document(project, "lib/common.glsl");
		project.show(path("lib/common.glsl"));

		edit(project, "lib/common.glsl", "// edited\n");

		assertEquals(path("main.frag"), project.running());
		Preparation preparation = prepare(project);
		assertEquals("// edited\n", built(preparation, "lib/common.glsl"));
		assertEquals("// common\n", read("lib/common.glsl"));
	}

	@Test
	public void anInvalidManifestShowsWhenNothingRuns() throws IOException {
		write("shader-project.json", "{\"entri\": \"a.frag\"}");
		OpenProject project = open();
		OpenProject.Build build = build(project);

		assertNull(build.preparation());
		assertEquals(List.of(new ProjectDiagnostic(
				ProjectDiagnostic.Severity.ERROR, "Unknown key \"entri\"",
				path("shader-project.json"), 1)), build.nothingRuns());
	}

	@Test
	public void theManifestOfTheRootIsEditedAsTheManifest()
			throws IOException {
		write("shader-project.json", "{}");
		write("main.frag", SHADER);
		write("b.glsl", SHADER);
		write("notes.md", "# notes");
		write("noise.png", "x");
		write("lib/shader-project.json", "{}");
		OpenProject project = open();

		Syntax manifest = project.syntax(path("shader-project.json"));
		settle();
		List<List<String>> offered = new ArrayList<>();
		String line = "{\"entry\": \"";
		manifest.complete(LexedLine.of(manifest, line, false,
						Syntax.TEXT_START, new LineTokens()), line.length(), 0,
				Knowledge.NONE, true, List.of(),
				(words, position) -> offered.add(words));
		assertEquals(List.of(List.of("b.glsl", "main.frag")), offered);
		assertSame(JsonSyntax.INSTANCE,
				project.syntax(path("lib/shader-project.json")));
		assertSame(GlslSyntax.INSTANCE, project.syntax(path("main.frag")));
	}

	@Test
	public void doesNotDeliverABuildOfAnOlderText() throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		document(project, "main.frag");
		edit(project, "main.frag", SHADER + "// one\n");
		List<OpenProject.Build> builds = new ArrayList<>();

		project.prepare(BuiltinNames.ALL, builds::add);
		edit(project, "main.frag", SHADER + "// two\n");
		settle();

		assertTrue(builds.isEmpty());
		assertEquals(SHADER + "// two\n",
				built(prepare(project), "main.frag"));
	}

	// What the programs say of the lines comes with a build, with the
	// texts of the documents it read, and never with a build for tuning.
	@Test
	public void aBuildTellsWhichLinesItDoesNotCompile() throws IOException {
		String text = SHADER.replace("precision", "#define AA 1\n" +
				"#if AA > 1\nfloat off;\n#endif\nprecision");
		write("main.frag", text);
		OpenProject project = open();
		Document main = document(project, "main.frag");
		OpenProject.Build build = await(done -> project.prepare(
				BuiltinNames.ALL, done));
		assertSame(main.text(), build.texts().get(path("main.frag")));
		assertEquals(List.of(3), build.lines().off(path("main.frag")));
		int at = text.indexOf("1.0");
		OpenProject.Build tuned = await(done -> project.prepare(
				BuiltinNames.ALL, new OpenProject.Replacement(
						path("main.frag"), at, at + 3, "se_tune0"), done));
		assertNull(tuned.lines());
		assertNull(tuned.again(path("main.frag"), 3));
	}

	// What a build tells of a file holds while every other document has
	// the text the build read; and its lines are told again for another
	// text of it.
	@Test
	public void aBuildHoldsWhileTheOtherDocumentsHaveItsTexts()
			throws IOException {
		String text = SHADER.replace("void main",
				"#if 0\nfloat off;\n#endif\nvoid main");
		write("pass.frag", text);
		write("main.frag", "#pragma se input \"pass.frag\"\n" +
				"uniform sampler2D pass;\n" + SHADER);
		OpenProject project = open();
		Document main = document(project, "main.frag");
		Document pass = document(project, "pass.frag");
		OpenProject.Build build = await(done -> project.prepare(
				BuiltinNames.ALL, done));
		ProjectPath at = path("pass.frag");
		assertTrue(build.holds(project, at));
		assertSame(pass.text(), build.texts().get(at));
		assertEquals(List.of(4), build.lines().off(at));
		assertEquals(List.of(), build.again(at, 3).off(
				text.replace("#if 0", "#if 1")));
		// Its own text the editor checks; any other drops it.
		pass.replace(0, 0, Text.of("// x\n"));
		assertTrue(build.holds(project, at));
		assertFalse(build.holds(project, path("main.frag")));
		main.replace(0, 0, Text.of("// x\n"));
		assertFalse(build.holds(project, at));
	}

	// A build with one range of a document replaced: the build has it,
	// the document, the journal and the file do not, and the next build
	// is of the text as it is.
	@Test
	public void aReplacementIsInOneBuildOnly() throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		Document main = document(project, "main.frag");
		int at = SHADER.indexOf("1.0");
		OpenProject.Build replaced = await(done -> project.prepare(
				BuiltinNames.ALL, new OpenProject.Replacement(
						path("main.frag"), at, at + 3, "se_tune0"), done));

		assertEquals(SHADER.replace("1.0", "se_tune0"),
				built(replaced.preparation(), "main.frag"));
		assertTrue(replaced.preparation().temporary());
		assertEquals(SHADER, main.text().toString());
		assertFalse(main.isModified());
		assertEquals(SHADER, read("main.frag"));
		Preparation next = prepare(project);
		assertEquals(SHADER, built(next, "main.frag"));
		assertFalse(next.temporary());
	}

	// While a number is tuned, the draft written at a pause, Save a copy
	// and a save take the documents' text, never a build's.
	@Test
	public void aReplacementNeverReachesTheJournalOrASave()
			throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		document(project, "main.frag");
		String edited = SHADER + "// edited\n";
		edit(project, "main.frag", edited);
		int at = edited.indexOf("1.0");
		OpenProject.Replacement replacement = new OpenProject.Replacement(
				path("main.frag"), at, at + 3, "se_tune0");
		OpenProject.Build replaced = await(done -> project.prepare(
				BuiltinNames.ALL, replacement, done));
		assertTrue(built(replaced.preparation(), "main.frag")
				.contains("se_tune0"));

		flush(project);
		assertEquals(edited, journaled("main.frag"));
		MemoryProjectFiles copy = new MemoryProjectFiles();
		copy.writable = true;
		Map<ProjectPath, String> copied = await(done -> project.copyTo(copy,
				List.of(path("main.frag")), done));
		assertNull(copied.get(path("main.frag")));
		assertEquals(edited, new String(copy.read(path("main.frag")).bytes(),
				StandardCharsets.UTF_8));
		save(project, true);
		assertEquals(edited, read("main.frag"));
	}

	// A file renamed while a number of it is tuned: the build asked for
	// before is of the text as it was, the moved document and file hold
	// the user's text, and the old path has no document to replace in.
	@Test
	public void aReplacementOfAFileRenamedMeanwhileStaysInTheBuild()
			throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		document(project, "main.frag");
		int at = SHADER.indexOf("1.0");
		OpenProject.Replacement replacement = new OpenProject.Replacement(
				path("main.frag"), at, at + 3, "se_tune0");
		List<OpenProject.Build> builds = new ArrayList<>();
		project.prepare(BuiltinNames.ALL, replacement, builds::add);
		OpenProject.Moved moved = await(done -> project.rename(
				path("main.frag"), path("b.frag"), done));

		assertEquals(new OpenProject.Renamed(path("main.frag"),
				path("b.frag")), moved);
		for (OpenProject.Build build : builds) {
			assertTrue(build.preparation().temporary());
		}
		assertEquals(SHADER, project.document(path("b.frag")).text()
				.toString());
		assertEquals(SHADER, read("b.frag"));
		OpenProject.Build after = await(done -> project.prepare(
				BuiltinNames.ALL, replacement, done));
		assertNull(after.preparation());
	}

	@Test
	public void aReplacementOfAFileWithoutDocumentRunsNothing()
			throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		OpenProject.Build build = await(done -> project.prepare(
				BuiltinNames.ALL, new OpenProject.Replacement(
						path("main.frag"), 0, 1, "x"), done));

		assertNull(build.preparation());
		assertEquals(path("main.frag"), build.nothingRuns().get(0).file());
	}

	@Test
	public void refreshFindsAChangedManifestU10() throws IOException {
		write("main.frag", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		assertEquals(path("main.frag"), project.running());

		write("shader-project.json", "{\"entry\": \"b.glsl\"}");
		refresh(project);

		assertEquals(path("b.glsl"), project.running());
	}

	@Test
	public void switchingFilesKeepsUnsavedTextAndSeparateHistories()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		Document b = document(project, "b.glsl");
		a.type(1, 1, Text.of("!"));
		b.type(1, 1, Text.of("?"));
		List<Runnable> posted = new ArrayList<>();
		project.setListener(posted::add, () -> {
		});

		assertSame(a, project.show(path("a.glsl")));
		assertSame(b, project.show(path("b.glsl")));
		assertSame(a, project.show(path("a.glsl")));

		assertEquals(0, drain(posted));
		assertEquals(1, a.revision());
		assertEquals("a!", a.text().toString());
		assertEquals("b?", b.text().toString());
		assertEquals(Set.of(path("a.glsl"), path("b.glsl")),
				project.unsaved());
		a.undo();
		assertEquals("a", a.text().toString());
		assertEquals("b?", b.text().toString());
		assertEquals(Set.of(path("b.glsl")), project.unsaved());
		assertEquals(1, drain(posted));
		b.undo();
		assertFalse(b.canUndo());
		assertTrue(a.canRedo());
	}

	@Test
	public void restoresDraftsIntoDocumentsAfterTheProcessDied()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		edit(project, document(project, "a.glsl"), "a!");
		edit(project, document(project, "b.glsl"), "b!");

		project.persist();
		settle();
		OpenProject restarted = open();

		assertEquals(Set.of(path("a.glsl"), path("b.glsl")),
				restarted.unsaved());
		assertNull(restarted.document(path("b.glsl")));
		Document b = document(restarted, "b.glsl");
		assertEquals("b!", b.text().toString());
		assertTrue(b.isModified());
		assertFalse(b.canUndo());
		assertEquals("a", read("a.glsl"));

		assertTrue(save(restarted, false).get(path("a.glsl")) instanceof
				Saved);
		assertEquals("a!", read("a.glsl"));
		assertNull(restarted.document(path("a.glsl")));
		assertNull(journaled("a.glsl"));
		assertTrue(kept().isEmpty());
	}

	@Test
	public void reopensTheTextOfTheLastCompleteRecordWhereverItWasCut()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		List<Long> ends = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		for (String text : List.of("one", "one two", "three two",
				"three two\nfour")) {
			edit(project, a, text);
			flush(project);
			ends.add(file.length());
			texts.add(text);
		}
		byte[] full = Files.readAllBytes(file.toPath());
		assertTrue("appended, not rewritten", ends.get(1) > ends.get(0));

		for (long cut = ends.get(0); cut <= full.length; ++cut) {
			Files.write(file.toPath(), Arrays.copyOf(full, (int) cut));
			int complete = 0;
			while (complete + 1 < ends.size() &&
					ends.get(complete + 1) <= cut) {
				++complete;
			}
			OpenProject reopened = open();
			assertEquals("cut at " + cut, Set.of(path("a.glsl")),
					reopened.unsaved());
			assertEquals("cut at " + cut, texts.get(complete),
					text(reopened, "a.glsl"));
		}
	}

	@Test
	public void damagedJournalKeepsWhatIsGoodElseOpensTheStoredFile()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		int snapshot = (int) file.length();
		a.type(4, 4, Text.of("!"));
		flush(project);
		byte[] bytes = Files.readAllBytes(file.toPath());
		assertTrue(bytes.length > snapshot);

		// The checksum of the record appended, then of the snapshot.
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		assertEquals("mine", text(open(), "a.glsl"));
		bytes[snapshot - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		OpenProject reopened = open();
		Document stored = document(reopened, "a.glsl");
		flush(reopened);

		assertEquals("stored", stored.text().toString());
		assertFalse(stored.isModified());
		assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
	}

	@Test
	public void unreadableJournalIsToldUntilDismissed() throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);

		OpenProject restarted = open();
		assertNull(load(restarted, "a.glsl"));
		String reason = restarted.unreadable(path("a.glsl"));

		assertNotNull(reason);
		assertTrue(reason, reason.contains("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), restarted.unsaved());
		// Neither the stored text shown in its place nor nothing at all
		// is what a save of it writes.
		assertEquals(new OpenProject.Unreadable(reason, true),
				save(restarted, true).get(path("a.glsl")));
		assertEquals("stored", read("a.glsl"));
		OpenProject again = open();
		assertEquals(Set.of(path("a.glsl")), again.unsaved());
		assertEquals(new OpenProject.Unreadable(reason, true),
				save(again, true).get(path("a.glsl")));
		assertEquals(reason, again.unreadable(path("a.glsl")));
		assertNull(load(again, "a.glsl"));
		again.dismiss(path("a.glsl"));
		settle();
		assertNull(again.unreadable(path("a.glsl")));
		assertTrue(again.unsaved().isEmpty());
		assertFalse(file.exists());
		assertTrue(kept().isEmpty());
	}

	@Test
	public void dismissLeavesTheJournalWrittenSince() throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		OpenProject restarted = open();
		edit(restarted, document(restarted, "a.glsl"), "new work");

		// The snapshot is on its way when the old notice is dismissed.
		restarted.persist();
		restarted.dismiss(path("a.glsl"));
		settle();

		assertEquals("new work", journaled("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), restarted.unsaved());
		assertNull(restarted.unreadable(path("a.glsl")));
		assertNull(restarted.unprotected());
	}

	@Test
	public void closedProjectLeavesTheJournalOfTheNextOne()
			throws IOException {
		OpenProject first = open();
		Document old = document(first, "a.glsl", "stored");
		edit(first, old, "first");
		first.close();
		settle();
		OpenProject second = open();
		edit(second, document(second, "a.glsl"), "second");
		second.persist();
		settle();

		// Whoever kept the document of the closed project undoes in it.
		old.undo();
		first.persist();
		first.save(true, outcomes -> {
		});
		settle();
		steps.later.run();
		settle();

		assertEquals("second", journaled("a.glsl"));
		assertEquals("stored", read("a.glsl"));
		assertNull(first.document(path("a.glsl")));
	}

	@Test
	public void projectReplacedWithoutClosingWritesNoJournal()
			throws IOException {
		OpenProject first = open();
		Document old = document(first, "a.glsl", "stored");
		edit(first, old, "first");
		flush(first);
		OpenProject second = open();
		edit(second, document(second, "a.glsl"), "second");
		flush(second);

		old.undo();
		first.persist();
		settle();

		assertEquals("second", journaled("a.glsl"));
		assertNotNull(first.unprotected());
	}

	@Test
	public void projectReplacedThroughAnotherSpellingOfItsFolderWritesNoJournal()
			throws IOException {
		OpenProject first = open();
		Document old = document(first, "a.glsl", "stored");
		OpenProject second = OpenProject.open(id,
				new DirectoryProjectFiles(root), null, null,
				new ProjectSessions(new File(sessionsDir, ".")), worker,
				main, steps.timers, budget);
		edit(second, document(second, "a.glsl"), "second");
		flush(second);

		edit(first, old, "stale");
		first.persist();
		settle();

		assertEquals("second", journaled("a.glsl"));
		assertNotNull(first.unprotected());
	}

	@Test
	public void removingTheRecentOfAnOpenProjectKeepsItsSession()
			throws IOException {
		ProjectSessions sessions = new ProjectSessions(sessionsDir);
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");

		// Nothing is in the journal yet when the recent is removed.
		project.persist();
		assertTrue(sessions.remove(id));
		settle();

		assertNotNull(sessions.locator(id));
		assertEquals(Set.of(path("a.glsl")), sessions.unsaved(id));
		project.close();
		settle();
		assertTrue(sessions.remove(id));
		assertNotNull(sessions.locator(id));
		assertEquals("mine", text(open(), "a.glsl"));
	}

	@Test
	public void journalWithADamagedHeaderIsToldAndKept() throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		File file = damage(0);

		OpenProject restarted = open();

		// No file is known for it yet, but it is not gone.
		assertEquals(1, restarted.damagedJournals());
		assertTrue(restarted.unsaved().isEmpty());
		assertTrue(new ProjectSessions(sessionsDir).remember(id, "n", "l",
				1));
		assertTrue(new ProjectSessions(sessionsDir).recent(id).unsaved());
		assertNull(load(restarted, "a.glsl"));
		assertNotNull(restarted.unreadable(a));
		assertEquals(Set.of(a), restarted.unsaved());
		assertEquals("stored", restarted.document(a).text().toString());
		assertTrue(file.exists());
		restarted.dismiss(a);
		settle();
		assertFalse(file.exists());
		assertEquals(0, restarted.damagedJournals());
		assertTrue(restarted.unsaved().isEmpty());
	}

	@Test
	public void unreadableJournalIsToldAlsoWhenTheFileCannotBeOpened()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		File file = damage(-1);
		assertTrue(new File(root, "a.glsl").delete());

		OpenProject restarted = open();
		assertNotNull(load(restarted, "a.glsl"));

		assertNotNull(restarted.unreadable(a));
		assertEquals(Set.of(a), restarted.unsaved());
		assertTrue(file.exists());
		restarted.dismiss(a);
		settle();
		assertFalse(file.exists());
		assertTrue(restarted.unsaved().isEmpty());
	}

	@Test
	public void copyAndBuildTellUnsavedTextThatCannotBeRead()
			throws IOException {
		write("main.frag", "#include \"a.glsl\"\n" + SHADER);
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		damage(-1);
		OpenProject restarted = open();
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;

		Map<ProjectPath, String> problems = await(done -> restarted.copyTo(
				destination, List.of(a), done));
		Preparation preparation = prepare(restarted);

		// Not the stored text in place of the unsaved one.
		assertTrue(problems.get(a), problems.get(a).contains(
				"cannot be recovered"));
		assertNull(destination.text("a.glsl"));
		assertNull(preparation.plan());
		assertTrue(preparation.diagnostics().get(0).message(),
				preparation.diagnostics().get(0).message().contains(
						"cannot be recovered"));
	}

	@Test
	public void deletesDraftsOfTheFormatBeforeAndUnfinishedSnapshots()
			throws IOException {
		File drafts = new File(new File(sessionsDir, id), "drafts");
		assertTrue(drafts.mkdirs());
		File old = new File(drafts, "0123.draft");
		File half = new File(drafts, ".journal-1.tmp");
		Files.write(old.toPath(), new byte[]{1});
		Files.write(half.toPath(), new byte[]{2});

		OpenProject project = open();
		settle();

		assertFalse(old.exists());
		assertFalse(half.exists());
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void appendTheJournalDoesNotContinueIsSentAsASnapshot()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "one");
		flush(project);
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		assertTrue(file.delete());

		a.type(3, 3, Text.of(" two"));
		flush(project);

		assertEquals("one two", journaled("a.glsl"));
		assertNull(project.unprotected());
	}

	@Test
	public void savesEachFileInPlaceAndKeepsFailuresForRetry()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		FailingFiles store = new FailingFiles(new DirectoryProjectFiles(root));
		store.failing = path("b.glsl");
		OpenProject project = open(store);
		Document a = document(project, "a.glsl");
		Document b = document(project, "b.glsl");
		edit(project, a, "a!");
		edit(project, b, "b!");

		Map<ProjectPath, Outcome> outcomes = save(project, true);

		assertTrue(outcomes.get(path("a.glsl")) instanceof Saved);
		assertEquals(new Failed("the card was removed"),
				outcomes.get(path("b.glsl")));
		assertEquals("a!", read("a.glsl"));
		assertEquals("b", read("b.glsl"));
		assertFalse(a.isModified());
		assertTrue(b.isModified());
		assertEquals(Set.of(path("b.glsl")), project.unsaved());
		assertEquals(Set.of(path("b.glsl")), kept());

		store.failing = null;
		outcomes = save(project, true);

		assertEquals(Set.of(path("b.glsl")), outcomes.keySet());
		assertEquals("b!", read("b.glsl"));
		assertTrue(project.unsaved().isEmpty());
		assertTrue(kept().isEmpty());
		edit(project, a, "a!!");
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		a.undo();
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void savesTheTextADocumentHadWhenSaveWasAsked()
			throws IOException {
		write("a.glsl", "a");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "one");
		Map<?, ?>[] outcomes = new Map<?, ?>[1];

		project.save(false, saved -> outcomes[0] = saved);
		edit(project, a, "two");
		settle();

		assertTrue(outcomes[0].get(path("a.glsl")) instanceof Saved);
		assertEquals("one", read("a.glsl"));
		assertTrue(a.isModified());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		a.undo();
		assertFalse(a.isModified());
	}

	@Test
	public void reloadsFromFileOnRequestAsOneUndoableChange()
			throws IOException {
		OpenProject project = editedElsewhere();
		Document a = project.document(path("a.glsl"));

		assertNull(this.<String>await(done ->
				project.reload(path("a.glsl"), done)));

		assertSame(a, project.document(path("a.glsl")));
		assertEquals("theirs", a.text().toString());
		assertTrue(project.unsaved().isEmpty());
		assertTrue(project.conflicts().isEmpty());
		a.undo();
		assertEquals("mine", a.text().toString());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
	}

	@Test
	public void openingADocumentInMemoryLooksAtStorage() throws IOException {
		OpenProject project = open();
		document(project, "a.glsl", "stored");
		document(project, "b.glsl", "kept");
		shown(project, "c.glsl", "shown");
		edit(project, "b.glsl", "mine");
		assertTrue(new File(root, "a.glsl").delete());
		assertTrue(new File(root, "b.glsl").delete());

		// Gone without unsaved text: opened as a file that is gone, not
		// with the text it had.
		assertNotNull(load(project, "a.glsl"));
		assertNull(project.document(path("a.glsl")));
		// Gone with unsaved text: the text stays, in conflict.
		assertNull(load(project, "b.glsl"));
		assertEquals("mine", project.document(path("b.glsl")).text()
				.toString());
		assertEquals(Set.of(path("b.glsl")), project.conflicts().keySet());

		write("c.glsl", "theirs");
		Document c = document(project, "c.glsl");
		assertEquals("theirs", c.text().toString());
		c.undo();
		assertEquals("shown", c.text().toString());
	}

	@Test
	public void openAgainJoinsTheFirstSoAChangeElsewhereConflicts()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		List<String> problems = new ArrayList<>();
		project.load(path("a.glsl"), ProjectFiles.UNKNOWN, problems::add);
		worker.run();
		write("a.glsl", "theirs");
		project.load(path("a.glsl"), ProjectFiles.UNKNOWN, problems::add);
		worker.run();
		main.run();
		assertEquals(Arrays.asList(null, null), problems);
		Document a = project.document(path("a.glsl"));
		assertEquals("stored", a.text().toString());

		edit(project, a, "mine");

		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
		assertEquals("mine", a.text().toString());
	}

	@Test
	public void reloadKeepsTypingThatCameBeforeItsResult()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "mine");
		project.persist();
		settle();
		write("a.glsl", "theirs");
		project.reload(path("a.glsl"), problem -> {
		});
		worker.run();

		edit(project, a, "later");
		settle();

		assertEquals("later", a.text().toString());
		assertTrue(a.isModified());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
	}

	@Test
	public void listenerThatEditsWhileTheStoredTextIsTakenSaves()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		write("a.glsl", "theirs");
		a.addListener(new Document.Listener() {
			@Override
			public void onUpdate() {
				if (a.text().toString().equals("theirs")) {
					edit(project, a, "mine");
					project.persist();
				}
			}
		});

		assertEquals(Set.of(path("a.glsl")), refresh(project));

		assertEquals("mine", a.text().toString());
		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Saved);
		assertEquals("mine", read("a.glsl"));
	}

	@Test
	public void reloadOfAFileGoneKeepsTheDraftAndTheText()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "mine");
		project.persist();
		assertTrue(new File(root, "a.glsl").delete());

		assertNotNull(this.<String>await(done ->
				project.reload(path("a.glsl"), done)));

		assertEquals("mine", a.text().toString());
		assertTrue(a.isModified());
		assertEquals("mine", text(open(), "a.glsl"));
	}

	@Test
	public void copyKeepsTextTypedBeforeItsResult() throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		edit(project, a, "mine");
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;
		project.copyTo(destination, List.of(path("a.glsl")), problems -> {
		});
		worker.run();

		edit(project, a, "later");
		settle();
		project.persist();
		settle();

		assertEquals("mine", destination.text("a.glsl"));
		assertEquals("later", a.text().toString());
		assertTrue(a.isModified());
		OpenProject reopened = open();
		assertEquals("later", text(reopened, "a.glsl"));
		assertEquals(Set.of(path("a.glsl")), reopened.unsaved());
	}

	// The screen that asked may be gone before the copy is: the copy is
	// made all the same, and what waits for it hears so.
	@Test
	public void copyAskedBeforeTheFolderClosesIsMadeAndAnswered()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		edit(project, document(project, "a.glsl"), "mine");
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;

		Map<ProjectPath, String> problems = await(done -> {
			project.copyTo(destination, List.of(path("a.glsl")), done);
			project.close();
		});

		assertTrue(problems.containsKey(path("a.glsl")));
		assertNull(problems.get(path("a.glsl")));
		assertEquals("mine", destination.text("a.glsl"));
	}

	@Test
	public void copyAskedOfAClosedFolderIsAnsweredWithWhyNot()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		project.close();
		settle();
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;

		Map<ProjectPath, String> problems = await(done -> project.copyTo(
				destination, List.of(path("a.glsl")), done));

		assertNotNull(problems.get(path("a.glsl")));
		assertNull(destination.text("a.glsl"));
	}

	@Test
	public void typingWithinTheDelayWritesTheJournalOnce()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "0");
		steps.later.run();
		int once = worker.size();
		settle();

		for (int i = 1; i <= 10; ++i) {
			edit(project, a, "" + i);
		}
		steps.later.run();

		assertEquals(once, worker.size());
		settle();
		assertEquals("10", journaled("a.glsl"));
	}

	@Test
	public void flushCompletesOnlyAfterTheJournalTookTheText()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		List<String> flushed = new ArrayList<>();

		project.flush(() -> flushed.add(journaled("a.glsl")));
		main.run();

		assertTrue(flushed.isEmpty());
		settle();
		assertEquals(List.of("mine"), flushed);
	}

	@Test
	public void closeKeepsAnEditTheJournalRewritesItsRecordsFor()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		project.persist();
		settle();
		String more = "x".repeat(20000);
		edit(project, a, more);

		project.close();
		settle();

		assertEquals(more, journaled("a.glsl"));
	}

	@Test
	public void flushCompletesOnlyOnceTheJournalHoldsTheLastEdit()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		project.persist();
		settle();
		String more = "x".repeat(20000);
		edit(project, a, more);
		List<String> done = new ArrayList<>();

		project.flush(() -> done.add(journaled("a.glsl") + " " +
				project.unprotected()));
		settle();

		assertEquals(List.of(more + " null"), done);
	}

	@Test
	public void closeWritesWhatAFailedWriteDidNotWhoseAnswerNeverCame()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		// A file where the folder of the journal would be.
		File drafts = new File(new File(sessionsDir, id), "drafts");
		Files.write(drafts.toPath(), new byte[]{1});
		edit(project, a, "mine");
		project.persist();
		// The write failed; main has not heard yet.
		worker.run();
		assertTrue(drafts.delete());

		project.close();
		settle();

		assertEquals("mine", journaled("a.glsl"));
	}

	@Test
	public void flushAnswersForAWriteThatFailedUnheard() throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		File drafts = new File(new File(sessionsDir, id), "drafts");
		Files.write(drafts.toPath(), new byte[]{1});
		edit(project, a, "mine");
		project.persist();
		worker.run();
		assertTrue(drafts.delete());
		List<String> done = new ArrayList<>();

		project.flush(() -> done.add(journaled("a.glsl") + " " +
				project.unprotected()));
		settle();

		assertEquals(List.of("mine null"), done);
	}

	@Test
	public void closeRemovesTheJournalARemovalFailedToUnheard()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		project.persist();
		settle();
		a.undo();
		// Nothing can be deleted from the folder now.
		File drafts = new File(new File(sessionsDir, id), "drafts");
		assertTrue(drafts.setWritable(false));
		project.persist();
		worker.run();
		assertTrue(drafts.setWritable(true));
		assertEquals(Set.of(path("a.glsl")), kept());

		project.close();
		settle();

		assertTrue(kept().isEmpty());
	}

	@Test
	public void closeKeepsTheSelectionMadeWhileAnotherWasOnItsWay()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		project.show(path("a.glsl"));
		project.show(path("b.glsl"));
		project.runThisFile(path("b.glsl"));

		project.close();
		settle();

		ProjectSessions sessions = new ProjectSessions(sessionsDir);
		assertEquals(path("b.glsl"), sessions.shown(id));
		assertEquals(path("b.glsl"), sessions.running(id));
	}

	@Test
	public void tellsAndRetriesWhenTheProjectCannotBeKeptAsTheLastOpen()
			throws IOException {
		ProjectSessions sessions = new ProjectSessions(sessionsDir);
		assertTrue(sessions.remember(id, "name", "location", 1));
		OpenProject project = open();
		// A file cannot be renamed onto a folder.
		File last = new File(sessionsDir, "last");
		assertTrue(last.mkdir());

		project.remember();
		settle();
		assertNotNull(project.unkept());
		assertNull(sessions.last());

		assertTrue(last.delete());
		project.persist();
		settle();

		assertNull(project.unkept());
		assertEquals(id, sessions.last());
		assertTrue(sessions.recent(id).opened() > 1);
	}

	@Test
	public void switchingFilesAndRunningKeepTheTextAtOnce()
			throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		project.show(path("a.glsl"));
		settle();

		edit(project, a, "shown");
		project.show(path("main.frag"));
		settle();
		assertEquals("shown", journaled("a.glsl"));

		edit(project, a, "chosen");
		project.runThisFile(path("main.frag"));
		settle();
		assertEquals("chosen", journaled("a.glsl"));

		edit(project, a, "built");
		prepare(project);
		assertEquals("built", journaled("a.glsl"));
	}

	@Test
	public void selectionTheSessionCouldNotKeepIsSentAgain()
			throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		ProjectSessions sessions = new ProjectSessions(sessionsDir);
		// A file cannot be renamed onto a folder.
		File shown = new File(new File(sessionsDir, id), "shown");
		File running = new File(new File(sessionsDir, id), "running");
		assertTrue(shown.mkdir() && running.mkdir());

		project.show(path("main.frag"));
		project.runThisFile(path("main.frag"));
		settle();
		assertNotNull(project.unkept());

		assertTrue(shown.delete() && running.delete());
		project.persist();
		settle();

		assertNull(project.unkept());
		assertEquals(path("main.frag"), sessions.shown(id));
		assertEquals(path("main.frag"), sessions.running(id));
	}

	// The session ends with what is shown now, also when the file shown
	// goes back to what it keeps while a write of another is on its way.
	@Test
	public void aFenceKeepsTheFileShownNowOverTheWriteOnItsWay()
			throws IOException {
		write("main.frag", SHADER);
		OpenProject project = open();
		ProjectSessions sessions = new ProjectSessions(sessionsDir);
		project.show(path("main.frag"));
		settle();

		project.show(path("a.glsl"));
		project.show(path("main.frag"));
		flush(project);

		assertEquals(path("main.frag"), sessions.shown(id));
		assertNull(project.unkept());
	}

	@Test
	public void failedJournalWriteIsReportedAndRetriedOnTheNextFlush()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		File drafts = new File(new File(sessionsDir, id), "drafts");
		assertTrue(!drafts.exists() || drafts.delete());
		Files.write(drafts.toPath(), new byte[]{1});

		flush(project);

		assertNotNull(project.unprotected());
		assertTrue(drafts.delete());
		flush(project);
		assertNull(project.unprotected());
		assertEquals("mine", journaled("a.glsl"));
	}

	@Test
	public void keepingEditsOverOneRevisionConflictsWithTheNext()
			throws IOException {
		OpenProject project = editedElsewhere();
		String seen = project.seen(path("a.glsl"));
		assertNotNull(seen);

		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP, seen);
		settle();
		write("a.glsl", "again");

		assertTrue(save(project, true).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("again", read("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP, seen);
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
	}

	@Test
	public void cleanFileDeletedElsewhereKeepsItsTextAndIsNotBuilt()
			throws IOException {
		write(OpenProject.MAIN.toString(), SHADER);
		OpenProject project = open();
		Document main = shown(project, OpenProject.MAIN.toString());
		assertTrue(new File(root, OpenProject.MAIN.toString()).delete());

		refresh(project);

		assertEquals(FileTexts.Found.MISSING, project.found(OpenProject.MAIN));
		assertSame(main, project.document(OpenProject.MAIN));
		assertEquals(SHADER, main.text().toString());
		Preparation preparation = prepare(project);
		assertTrue(preparation == null || preparation.plan() == null);
	}

	@Test
	public void opensASessionOnceUntilItIsClosed() {
		LiveProjects live = live();
		OpenProject project = open(live, this::folder);

		assertSame(project, open(live, this::folder));
		assertEquals(OpenProject.Access.AVAILABLE,
				project.storage().access());
		project.close();
		assertNotSame(project, open(live, this::folder));
	}

	@Test
	public void reopeningAfterCloseReadsWhatTheProjectWroteLast()
			throws IOException {
		LiveProjects live = live();
		OpenProject project = open(live, this::folder);
		edit(project, document(project, "a.glsl", "stored"), "mine");
		project.close();
		OpenProject[] reopened = {null};

		live.open(id, this::folder, opened -> reopened[0] = opened);
		settle();

		assertEquals(Set.of(path("a.glsl")), reopened[0].unsaved());
		assertEquals("mine", text(reopened[0], "a.glsl"));
	}

	@Test
	public void openingIsAnsweredOnMainAfterWhatWasWrittenBefore()
			throws IOException {
		LiveProjects live = live();
		OpenProject project = open(live, this::folder);
		edit(project, document(project, "a.glsl", "stored"), "mine");
		// Its last writes wait on the worker.
		project.close();
		List<String> order = new ArrayList<>();
		List<OpenProject> reopened = new ArrayList<>();
		LiveProjects.Opener opener = locator -> {
			order.add("opened with " + journaled("a.glsl"));
			return folder(locator);
		};

		live.open(id, opener, opened -> {
			reopened.add(opened);
			order.add("answered");
		});
		live.open(id, opener, reopened::add);

		// Nothing ran yet: the caller was not held.
		assertTrue(order.isEmpty());
		worker.run();
		assertEquals(List.of("opened with mine"), order);
		assertTrue(reopened.isEmpty());
		main.run();
		assertEquals(List.of("opened with mine", "answered"), order);
		assertEquals(2, reopened.size());
		assertSame(reopened.get(0), reopened.get(1));
		assertNotSame(project, reopened.get(0));
	}

	@Test
	public void closingNeedsNothingOfTheWorkerWhileAnotherSessionOpens()
			throws IOException {
		LiveProjects live = live();
		OpenProject project = open(live, this::folder);
		String other = new ProjectSessions(sessionsDir).open(new Locator(
				Kind.DIRECTORY, temp.newFolder("other").getPath()));
		List<OpenProject> opened = new ArrayList<>();
		// On the worker and not run, as if held by slow storage.
		live.open(other, this::folder, opened::add);

		project.close();
		live.open(id, this::folder, opened::add);
		settle();

		assertEquals(2, opened.size());
		assertEquals(other, opened.get(0).id());
		assertNotSame(project, opened.get(1));
		// No method holds a lock a slow opener could keep from main.
		for (java.lang.reflect.Method method :
				LiveProjects.class.getDeclaredMethods()) {
			assertFalse(method.getName(), java.lang.reflect.Modifier
					.isSynchronized(method.getModifiers()));
		}
	}

	@Test
	public void noOneIsHandedAProjectAnotherCallerClosed() throws IOException {
		write("a.glsl", "stored");
		LiveProjects live = live();
		List<OpenProject> opened = new ArrayList<>();
		List<String> loaded = new ArrayList<>();

		// Both ask before it is open; the first closes what it gets.
		live.open(id, this::folder, project -> {
			opened.add(project);
			project.close();
		});
		live.open(id, this::folder, project -> {
			opened.add(project);
			project.load(path("a.glsl"), ProjectFiles.UNKNOWN,
					problem -> loaded.add(project.document(path("a.glsl"))
							.text().toString()));
		});
		settle();

		assertEquals(2, opened.size());
		assertNotSame(opened.get(0), opened.get(1));
		assertEquals(List.of("stored"), loaded);
	}

	@Test
	public void sessionWhoseFilesAreGoneOpensWithItsDrafts()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);

		OpenProject gone = open(live(), locator ->
				new SafProjects.Opened(null, null, "the folder is gone"));

		assertEquals(new OpenProject.Storage(OpenProject.Access.UNAVAILABLE,
				"the folder is gone"), gone.storage());
		assertEquals(Set.of(path("a.glsl")), gone.unsaved());
		assertEquals("mine", text(gone, "a.glsl"));
	}

	@Test
	public void loneDocumentRunsAsItsOwnEntry() {
		MemoryProjectFiles store = new MemoryProjectFiles();
		store.put("standalone.frag", SHADER);

		OpenProject project = OpenProject.open(id, store,
				path("standalone.frag"), null,
				new ProjectSessions(sessionsDir), worker, main, steps.timers,
				budget);

		assertEquals(path("standalone.frag"), project.running());
		assertNotNull(prepare(project).plan());
	}

	@Test
	public void refreshFindsWhatRunsFromTheManifestItTook()
			throws IOException {
		write(ProjectManifest.FILE.toString(), "{\"entry\":\"a.glsl\"}");
		write("a.glsl", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		document(project, ProjectManifest.FILE.toString());
		assertEquals(path("a.glsl"), project.running());
		write(ProjectManifest.FILE.toString(), "{\"entry\":\"b.glsl\"}");

		refresh(project);

		assertEquals(path("b.glsl"), project.running());
	}

	@Test
	public void refreshDoesNotUndoAChoiceMadeMeanwhile()
			throws IOException {
		write("main.frag", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		project.refresh(reread -> {
		});
		// Up to the entry found waiting on main.
		worker.run();
		main.run();
		worker.run();
		assertEquals(1, main.size());

		project.runThisFile(path("b.glsl"));
		settle();

		assertEquals(path("b.glsl"), project.running());
		assertEquals(path("b.glsl"),
				new ProjectSessions(sessionsDir).running(id));
	}

	@Test
	public void buildAskedBeforeAnotherEntryIsNotDelivered()
			throws IOException {
		write("main.frag", SHADER);
		write("b.glsl", SHADER);
		OpenProject project = open();
		List<OpenProject.Build> builds = new ArrayList<>();
		project.prepare(BuiltinNames.ALL, builds::add);
		worker.run();

		project.runThisFile(path("b.glsl"));
		settle();

		assertTrue(builds.isEmpty());
		assertTrue(project.used().isEmpty());
		OpenProject.Build build = build(project);
		assertEquals(List.of(runs("b.glsl")),
				project.used());
		project.setDiagnostics(build, List.of(ProjectDiagnostic.error("e")));
		assertEquals(1, project.errors());
	}

	@Test
	public void refreshMakesABuildPreparedBeforeStale() throws IOException {
		write("main.frag", "#include \"lib.glsl\"\n" + SHADER);
		write("lib.glsl", "float one() { return 1.0; }\n");
		OpenProject project = open();
		document(project, "main.frag");
		OpenProject.Build build = build(project);

		assertTrue(new File(root, "lib.glsl").delete());
		refresh(project);
		project.setDiagnostics(build, List.of(ProjectDiagnostic.error("e")));

		assertEquals(0, project.errors());
	}

	@Test
	public void conflictFollowsWhatStorageHoldsNow() throws IOException {
		OpenProject project = editedElsewhere();
		ProjectPath a = path("a.glsl");
		String theirs = project.seen(a);
		List<String> heard = new ArrayList<>();
		project.setListener(Runnable::run, () -> heard.add(project.seen(a)));

		write("a.glsl", "newer");
		refresh(project);
		String newer = project.seen(a);

		assertNotEquals(theirs, newer);
		assertEquals(List.of(newer), heard);
		// A dialog drawn for the revision before does nothing.
		project.resolve(a, OpenProject.Choice.RELOAD, theirs);
		settle();
		assertEquals("mine", project.document(a).text().toString());

		// A reload that finds yet another revision shows that one.
		write("a.glsl", "newest");
		project.resolve(a, OpenProject.Choice.RELOAD, newer);
		settle();
		String newest = project.seen(a);
		assertEquals("mine", project.document(a).text().toString());
		assertNotEquals(newer, newest);
		project.resolve(a, OpenProject.Choice.RELOAD, newest);
		settle();
		assertEquals("newest", project.document(a).text().toString());
		assertTrue(project.conflicts().isEmpty());
	}

	@Test
	public void refreshSeesWhatStorageHoldsOfUnsavedFilesNotInMemory()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		write("a.glsl", "theirs");
		OpenProject restarted = open();
		ProjectPath a = path("a.glsl");
		String theirs = restarted.seen(a);
		assertEquals(Set.of(a), restarted.conflicts().keySet());

		write("a.glsl", "newer");
		refresh(restarted);

		assertNotEquals(theirs, restarted.seen(a));
		assertNull(restarted.document(a));
		write("a.glsl", "stored");
		refresh(restarted);
		assertTrue(restarted.conflicts().isEmpty());
		assertEquals(Set.of(a), restarted.unsaved());
	}

	@Test
	public void unsavedTextStorageHoldsExactlyIsSaved() throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		flush(project);

		write("a.glsl", "mine");
		refresh(project);

		assertFalse(a.isModified());
		assertTrue(project.conflicts().isEmpty());
		assertTrue(project.unsaved().isEmpty());
		assertTrue(kept().isEmpty());
		write("a.glsl", "theirs");
		refresh(project);
		assertEquals("theirs", a.text().toString());

		// The same after the process died between the save and its
		// adoption, also for a file not opened again.
		edit(project, a, "mine again");
		flush(project);
		write("a.glsl", "mine again");
		OpenProject restarted = open();
		assertTrue(restarted.unsaved().isEmpty());
		assertTrue(restarted.conflicts().isEmpty());
		assertTrue(kept().isEmpty());
		Document reopened = document(restarted, "a.glsl");
		assertEquals("mine again", reopened.text().toString());
		assertFalse(reopened.isModified());
	}

	@Test
	public void savedFileNotInMemoryLosesItsJournalOnMainsWord()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		OpenProject restarted = open();

		Map<ProjectPath, Outcome> outcomes = save(restarted, true);

		assertTrue(outcomes.get(path("a.glsl")) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
		assertTrue(restarted.unsaved().isEmpty());
		assertTrue(kept().isEmpty());
		assertNull(restarted.unprotected());
	}

	@Test
	public void typingBeforeAReadArrivesIsInConflictWithWhatItRead()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		write("a.glsl", "theirs");
		String theirs = ((ProjectFiles.Content) new DirectoryProjectFiles(
				root).read(a)).revision();

		project.refresh(reread -> {
		});
		worker.run();
		edit(project, document, "mine");
		settle();

		assertEquals("mine", document.text().toString());
		assertEquals(Set.of(a), project.conflicts().keySet());
		assertEquals(theirs, project.seen(a));

		// The same for a reload the user typed into.
		write("a.glsl", "newer");
		String newer = ((ProjectFiles.Content) new DirectoryProjectFiles(
				root).read(a)).revision();
		project.reload(a, problem -> {
		});
		worker.run();
		edit(project, document, "mine too");
		settle();
		assertEquals("mine too", document.text().toString());
		assertEquals(newer, project.seen(a));
	}

	@Test
	public void reloadThatWaitsKeepsTypingThatCameAfterItWasAsked()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");
		project.persist();
		project.save(true, outcomes -> {
		});

		// The reload waits for the save; the user types meanwhile.
		project.reload(a, problem -> {
		});
		edit(project, document, "later");
		settle();

		assertEquals("later", document.text().toString());
		assertTrue(document.isModified());
		assertEquals("mine", read("a.glsl"));
		project.persist();
		settle();
		assertEquals("later", journaled("a.glsl"));
	}

	@Test
	public void reloadOfUnsavedTextNotInMemoryCanBeUndone()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		OpenProject restarted = open();
		ProjectPath a = path("a.glsl");
		assertNull(restarted.document(a));

		String problem = await(done -> restarted.reload(a, done));
		assertNull(problem);

		Document document = restarted.document(a);
		assertEquals("stored", document.text().toString());
		assertFalse(document.isModified());
		assertTrue(kept().isEmpty());
		document.undo();
		assertEquals("mine", document.text().toString());
		assertTrue(document.isModified());
	}

	@Test
	public void reloadKeepsWhatItReplacedWhateverBecomesOfTheHistory()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");
		flush(project);
		noHistories();

		String problem = await(done -> project.reload(a, done));

		assertNull(problem);
		assertEquals("stored", document.text().toString());
		assertFalse(document.isModified());
		// The file has no unsaved text and no journal, as the user asked.
		assertTrue(project.unsaved().isEmpty());
		assertNull(project.unprotected());
		assertTrue(project.settled(a));
		assertTrue(kept().isEmpty());
		// The process dies; undo of the file as stored leads back.
		Document restarted = document(open(), "a.glsl");
		assertEquals("stored", restarted.text().toString());
		assertFalse(restarted.isModified());
		restarted.undo();
		assertEquals("mine", restarted.text().toString());
		assertTrue(restarted.isModified());
	}

	@Test
	public void everythingTypedAfterAReloadIsKeptAndUndoLeadsBack()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");
		flush(project);
		noHistories();
		assertNull(reload(project, a));

		// Three writes of the journal, each by its timer.
		for (String typed : List.of("typed 1", "typed 2", "typed 3")) {
			edit(project, document, typed);
			steps.later.run();
			settle();
			assertEquals(typed, journaled("a.glsl"));
			assertNull(project.unprotected());
			assertTrue(project.settled(a));
		}

		// The process dies in the foreground.
		Document restarted = document(open(), "a.glsl");
		assertEquals("typed 3", restarted.text().toString());
		assertTrue(restarted.isModified());
		restarted.undo();
		assertEquals("mine", restarted.text().toString());
		restarted.redo();
		assertEquals("typed 3", restarted.text().toString());
	}

	@Test
	public void textTypedAfterUndoingPastAReloadIsKeptAndSavedLikeAny()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "typed 0");
		edit(project, document, "mine");
		flush(project);
		noHistories();
		assertNull(reload(project, a));
		document.undo();
		document.undo();
		assertEquals("typed 0", document.text().toString());

		edit(project, document, "my new work");
		flush(project);

		assertEquals("my new work", journaled("a.glsl"));
		assertNull(project.unprotected());
		assertEquals("my new work", text(open(), "a.glsl"));
		// Saved, nothing of it is left: no journal, no warning, and a
		// restart shows the file as saved.
		OpenProject saving = open();
		document(saving, "a.glsl");
		assertTrue(save(saving, true).get(a) instanceof Saved);
		assertTrue(kept().isEmpty());
		assertNull(saving.unprotected());
		assertTrue(saving.unsaved().isEmpty());
		OpenProject restarted = open();
		assertTrue(restarted.unsaved().isEmpty());
		Document saved = document(restarted, "a.glsl");
		assertEquals("my new work", saved.text().toString());
		assertFalse(saved.isModified());
	}

	@Test
	public void reloadOfALargeFileLeavesTypingKept() throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		String line = "u".repeat(99) + "\n";
		String stored = line.replace('u', 's').repeat(21_000);
		Document document = shown(project, "a.glsl", stored);
		edit(project, document, line.repeat(21_000));
		flush(project);

		String problem = await(done -> project.reload(a, done));

		// No history may keep both texts; the reload is made all the
		// same, and the text it replaced is kept beside the journal.
		assertNull(problem);
		assertTrue(document.historyChars() > Histories.MAX_CHARS);
		assertEquals(stored, document.text().toString());
		assertTrue(kept().isEmpty());
		assertEquals(line.repeat(21_000), new ProjectSessions(sessionsDir)
				.journal(id).replaced(a).get(0).text().toString());

		document.replace(0, 0, Text.of("my work of an hour "));
		steps.later.run();
		settle();
		assertNull(project.unprotected());
		flush(project);

		String reopened = text(open(), "a.glsl");
		assertTrue(reopened.startsWith("my work of an hour sss"));
	}

	@Test
	public void aSecondReloadKeepsWhatTheFirstReplacedToo()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		noHistories();
		edit(project, document, "first");
		assertNull(reload(project, a));
		edit(project, document, "second");
		assertNull(reload(project, a));
		assertEquals("stored", document.text().toString());

		Document restarted = document(open(), "a.glsl");

		assertEquals("stored", restarted.text().toString());
		restarted.undo();
		assertEquals("second", restarted.text().toString());
		restarted.undo();
		assertEquals("first", restarted.text().toString());
	}

	@Test
	public void typingAfterReloadWasAskedStaysAndNothingIsPutAside()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");
		flush(project);
		project.reload(a, problem -> {
		});
		edit(project, document, "mine, and more");
		settle();

		assertEquals("mine, and more", document.text().toString());
		// The journal holds the newest text again, and the older one
		// was not replaced, so it is not kept as if it were.
		assertEquals("mine, and more", journaled("a.glsl"));
		Document restarted = document(open(), "a.glsl");
		assertEquals("mine, and more", restarted.text().toString());
		assertFalse(restarted.canUndo());
	}

	@Test
	public void aProcessDyingInAReloadGivesTheFileAsStoredAndUndo()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		project.reload(a, problem -> {
		});
		// The worker put the journal aside; main never heard.
		worker.run();
		steps.main.clear();

		OpenProject restarted = open();

		assertTrue(restarted.unsaved().isEmpty());
		Document document = document(restarted, "a.glsl");
		assertEquals("stored", document.text().toString());
		assertFalse(document.isModified());
		document.undo();
		assertEquals("mine", document.text().toString());
	}

	@Test
	public void aJournalOfBeforeThatHeldReplacedTextBecomesUndo()
			throws IOException {
		ProjectPath a = path("a.glsl");
		write("a.glsl", "stored");
		Journal before = new ProjectSessions(sessionsDir).journal(id).own();
		assertTrue(before.snapshot(new Journal.Header(a, "sha256:0", false,
				null, true), 3, Text.of("mine")));

		OpenProject project = open();

		// The file is as stored, which the reload asked for, and the
		// text is one undo away.
		assertTrue(project.unsaved().isEmpty());
		Document document = document(project, "a.glsl");
		assertEquals("stored", document.text().toString());
		document.undo();
		assertEquals("mine", document.text().toString());
		assertTrue(document.isModified());
		document.redo();

		// Without a stored file to show, it stays unsaved text.
		project.close();
		settle();
		ProjectPath b = path("b.glsl");
		before = new ProjectSessions(sessionsDir).journal(id).own();
		assertTrue(before.snapshot(new Journal.Header(b, "sha256:0", false,
				null, true), 3, Text.of("theirs gone")));
		OpenProject next = open();
		assertEquals(Set.of(b), next.unsaved());
		assertEquals("theirs gone", text(next, "b.glsl"));
	}

	@Test
	public void whatAReloadReplacedOfAFileThatIsGoneComesBackAsItsUnsavedText()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		assertNull(reload(project, a));
		assertTrue(project.unsaved().isEmpty());
		// The process dies, and another app deletes the file.
		assertTrue(new File(root, "a.glsl").delete());

		OpenProject restarted = open();

		// No stored text is left for undo to lead back from: the text
		// is listed as unsaved text of a file that is not there.
		assertEquals(Set.of(a), restarted.unsaved());
		assertEquals("the file was deleted",
				restarted.conflicts().get(a));
		Document document = document(restarted, "a.glsl");
		assertEquals("mine", document.text().toString());
		assertTrue(document.isModified());
		// Kept, it is saved like any.
		restarted.resolve(a, OpenProject.Choice.KEEP, restarted.seen(a));
		assertTrue(save(restarted, true).get(a) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
	}

	@Test
	public void typingWhereAJournalCouldNotBeReadKeepsWhatItHolds()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, shown(project, "a.glsl", "stored"), "hidden work");
		flush(project);
		// The process dies; the journal cannot be read when the file is
		// opened again, so the stored text is shown.
		disk.denied = true;
		OpenProject unread = open();
		Document fallback = shown(unread, "a.glsl");
		assertEquals("stored", fallback.text().toString());
		assertNotNull(unread.unreadable(a));
		assertFalse(unread.damaged(a));
		disk.denied = false;

		// Typed into, without Discard and without Try again.
		edit(unread, fallback, "typed in its place");
		flush(unread);
		assertEquals("typed in its place", journaled("a.glsl"));

		// What the journal held was never shown, saved or given up: it
		// is kept, and undo leads to it once it can be read.
		Document reopened = document(open(), "a.glsl");
		assertEquals("typed in its place", reopened.text().toString());
		reopened.undo();
		assertEquals("hidden work", reopened.text().toString());
	}

	@Test
	public void aRenameThatCouldNotBeFollowedNeverCostsEitherText()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		ProjectPath b = path("b.glsl");
		edit(project, shown(project, "a.glsl", "stored"), "before rename");
		flush(project);
		// Storage renamed the file and the process died before the
		// journal followed; when it opens again nothing can be written.
		Journal journal = new ProjectSessions(sessionsDir).journal(id).own();
		assertTrue(journal.setNote(journal.noted(new Journal.Move(a, b))));
		assertTrue(new File(root, "a.glsl").renameTo(
				new File(root, "b.glsl")));
		disk.full = true;
		OpenProject failed = open();
		disk.full = false;
		// The text is still that of a.glsl, which is gone, and is
		// edited on; b.glsl is opened and edited by itself.
		edit(failed, document(failed, "a.glsl"), "after failed recovery");
		edit(failed, document(failed, "b.glsl"), "new b");
		flush(failed);

		OpenProject next = open();

		// Two texts, neither a successor of the other: both are there.
		assertEquals(Set.of(a, b), next.unsaved());
		assertEquals("after failed recovery", text(next, "a.glsl"));
		assertEquals("new b", text(next, "b.glsl"));
	}

	@Test
	public void aDeletionReplayedNeverTakesTextTypedAfterIt()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = shown(project, "a.glsl", "stored");
		edit(project, document, "delete this");
		flush(project);
		// The stored file goes, its journal cannot be removed.
		disk.undeleted = true;
		List<OpenProject.Moved> outcomes = new ArrayList<>();
		project.delete(a, Map.of(a, document.revision()), outcomes::add);
		edit(project, document, "typed after the deletion");
		settle();
		assertTrue(outcomes.get(0) instanceof OpenProject.Deleted);
		flush(project);
		assertEquals("typed after the deletion", journaled("a.glsl"));
		disk.undeleted = false;

		OpenProject next = open();

		// The deletion was for the text it was asked for, not this one.
		assertEquals(Set.of(a), next.unsaved());
		assertEquals("typed after the deletion", text(next, "a.glsl"));
	}

	// Delete a.glsl with its unsaved text while nothing of the session
	// can be deleted, so that its journal stays, noted to go.
	private OpenProject deletedButForItsJournal() throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = shown(project, "a.glsl", "stored");
		edit(project, document, "doomed");
		flush(project);
		disk.undeleted = true;
		List<OpenProject.Moved> outcomes = new ArrayList<>();
		project.delete(a, Map.of(a, document.revision()), outcomes::add);
		settle();
		assertTrue(outcomes.get(0) instanceof OpenProject.Deleted);
		assertFalse(new File(root, "a.glsl").exists());
		assertEquals("doomed", journaled("a.glsl"));
		return project;
	}

	@Test
	public void textTypedIntoWhatADeletionLeftBehindIsAnotherText()
			throws IOException {
		deletedButForItsJournal();
		ProjectPath a = path("a.glsl");
		// The process dies, and the opening after it cannot delete
		// either: the text is listed again, as of a file that is gone.
		OpenProject opened = open();
		disk.undeleted = false;
		assertEquals(Set.of(a), opened.unsaved());
		Document document = document(opened, "a.glsl");
		assertEquals("doomed", document.text().toString());

		// The user goes on with it.
		edit(opened, document, "doomed, and rescued");
		flush(opened);
		assertNull(opened.unprotected());
		assertTrue(opened.settled(a));
		assertEquals("doomed, and rescued", journaled("a.glsl"));

		// The deletion, finished at the next opening, takes what it
		// was asked for and not this.
		OpenProject next = open();
		assertEquals(Set.of(a), next.unsaved());
		assertEquals("doomed, and rescued", text(next, "a.glsl"));
		assertNull(new ProjectSessions(sessionsDir).journal(id).note());
	}

	@Test
	public void textKeptOfWhatADeletionLeftBehindIsAnotherText()
			throws IOException {
		deletedButForItsJournal();
		ProjectPath a = path("a.glsl");
		OpenProject opened = open();
		disk.undeleted = false;
		// Kept from its row, without opening it.
		assertNotNull(opened.conflict(a));
		opened.resolve(a, OpenProject.Choice.KEEP, opened.seen(a));
		settle();
		assertNull(opened.unprotected());

		OpenProject next = open();
		assertEquals(Set.of(a), next.unsaved());
		assertEquals("doomed", text(next, "a.glsl"));
	}

	@Test
	public void aNewFileOfADeletedNameKeepsWhatIsTypedIntoIt()
			throws IOException {
		OpenProject project = deletedButForItsJournal();
		ProjectPath a = path("a.glsl");
		// A new file of the name, opened while the journal of the old
		// one still cannot go.
		Document document = shown(project, "a.glsl", "new file");
		edit(project, document, "work in the new a");
		flush(project);
		assertTrue(project.settled(a));
		assertEquals("work in the new a", journaled("a.glsl"));

		// Deleting works again. Whatever the worker does next, the
		// deletion is over: the name has another file.
		disk.undeleted = false;
		refresh(project);
		assertTrue(project.settled(a));
		assertEquals("work in the new a", journaled("a.glsl"));

		OpenProject next = open();
		assertEquals(Set.of(a), next.unsaved());
		assertEquals("work in the new a", text(next, "a.glsl"));
	}

	@Test
	public void aNewFileOfARenamedFilesOldNameKeepsBothTexts()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		ProjectPath b = path("b.glsl");
		Document renamed = shown(project, "a.glsl", "stored");
		edit(project, renamed, "renamed text");
		flush(project);
		List<OpenProject.Moved> outcomes = new ArrayList<>();
		project.rename(a, b, outcomes::add);
		// Storage renames; then nothing of the session can be renamed,
		// so the journal cannot follow.
		while (!new File(root, "b.glsl").exists()) {
			assertTrue(worker.step() || main.step());
		}
		disk.unrenamed = true;
		settle();
		assertTrue(outcomes.get(0) instanceof OpenProject.Renamed);
		edit(project, renamed, "renamed text, more at b");
		flush(project);
		assertNotNull("told: not where it would be written",
				project.unprotected());

		// A new file of the old name, typed into.
		Document document = shown(project, "a.glsl", "new file");
		edit(project, document, "work in the new a");
		flush(project);

		// Renaming works again: the journal follows to b.glsl, and
		// what was typed into the new file is not part of it.
		disk.unrenamed = false;
		refresh(project);
		flush(project);
		assertNull(project.unprotected());
		assertEquals("work in the new a", journaled("a.glsl"));
		assertEquals("renamed text, more at b", journaled("b.glsl"));

		OpenProject next = open();
		assertEquals(Set.of(a, b), next.unsaved());
		assertEquals("work in the new a", text(next, "a.glsl"));
		assertEquals("renamed text, more at b", text(next, "b.glsl"));
	}

	@Test
	public void unsavedTextMissedWhileItsFolderCouldNotBeListedIsFoundLater()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, shown(project, "a.glsl", "stored"), "hidden");
		flush(project);
		assertTrue(new File(root, "a.glsl").delete());
		disk.unlisted = true;

		OpenProject unlisted = open();

		// Not known, and said so: it is not told that all is held.
		assertTrue(unlisted.unsaved().isEmpty());
		assertNotNull(unlisted.unprotected());
		disk.unlisted = false;
		refresh(unlisted);
		assertEquals(Set.of(a), unlisted.unsaved());
		assertNull(unlisted.unprotected());
		assertEquals("hidden", text(unlisted, "a.glsl"));
	}

	@Test
	public void reloadOfTextNotInMemoryKeepsWhatAnEarlierCallerTypes()
			throws IOException {
		budget = 0;
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, shown(project, "a.glsl", "stored"), "mine");
		flush(project);
		shown(project, "b.glsl", "b");
		assertNull(project.document(a));

		// Someone asked for the file before, and types once it is there.
		project.load(a, ProjectFiles.UNKNOWN, problem -> {
			project.show(a);
			edit(project, a.toString(), "later");
		});
		project.reload(a, problem -> {
		});
		settle();

		assertEquals("later", project.document(a).text().toString());
		assertTrue(project.document(a).isModified());
		flush(project);
		assertEquals("later", journaled("a.glsl"));
	}

	@Test
	public void reloadThatCannotReadSeesWhatIsThereInstead()
			throws IOException {
		OpenProject project = editedElsewhere();
		ProjectPath a = path("a.glsl");
		String theirs = project.seen(a);
		List<String> heard = new ArrayList<>();
		project.setListener(Runnable::run, () -> heard.add(
				project.conflicts().get(a)));
		assertTrue(new File(root, "a.glsl").delete());

		project.resolve(a, OpenProject.Choice.RELOAD, theirs);
		settle();

		assertEquals(FileTexts.Found.MISSING, project.found(a));
		assertNull(project.seen(a));
		assertEquals(List.of("the file was deleted"), heard);
		assertEquals("mine", project.document(a).text().toString());
		String problem = await(done -> project.reload(a, done));
		assertNotNull(problem);
	}

	@Test
	public void explicitSaveCreatesTheFileShownAgainAndSaysSo()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = shown(project, "a.glsl", "stored");
		// Deleted while the app is in front: no check saw it yet.
		assertTrue(new File(root, "a.glsl").delete());

		assertTrue(save(project, false).isEmpty());
		assertFalse(new File(root, "a.glsl").exists());
		assertTrue(save(project, true).get(a) instanceof
				OpenProject.Created);

		assertEquals("stored", read("a.glsl"));
		assertEquals(FileTexts.Found.SAME, project.found(a));
		assertFalse(document.isModified());
		// As it is in storage, nothing is told and nothing written.
		assertTrue(save(project, true).isEmpty());

		// A file that is there again is taken, not written over.
		assertTrue(new File(root, "a.glsl").delete());
		refresh(project);
		write("a.glsl", "theirs");
		assertTrue(save(project, true).get(a) instanceof
				OpenProject.Reread);
		assertEquals("theirs", read("a.glsl"));
		assertEquals("theirs", document.text().toString());
		document.undo();
		assertEquals("stored", document.text().toString());
	}

	@Test
	public void saveNeverBringsBackAFileWithoutUnsavedTextThatIsNotShown()
			throws IOException {
		OpenProject project = open();
		ProjectPath b = path("b.glsl");
		write("deleted.glsl", "gone");
		document(project, "deleted.glsl");
		document(project, "b.glsl", "bee");
		Document a = shown(project, "a.glsl", "stored");
		// Renamed and deleted by another app while the app is in front.
		assertTrue(new File(root, "b.glsl").renameTo(
				new File(root, "c.glsl")));
		assertTrue(new File(root, "deleted.glsl").delete());
		edit(project, a, "typed");

		Map<ProjectPath, Outcome> outcomes = save(project, true);

		assertEquals(Set.of(path("a.glsl")), outcomes.keySet());
		assertTrue(outcomes.get(path("a.glsl")) instanceof Saved);
		assertFalse(new File(root, "b.glsl").exists());
		assertFalse(new File(root, "deleted.glsl").exists());
		assertEquals("bee", read("c.glsl"));

		// Seen to be gone, they are no files of the folder anymore, and
		// a save still writes nothing.
		refresh(project);
		assertNull(project.document(b));
		assertNull(project.document(path("deleted.glsl")));
		edit(project, a, "typed more");
		assertEquals(Set.of(path("a.glsl")),
				save(project, true).keySet());
		assertFalse(new File(root, "b.glsl").exists());
		assertFalse(new File(root, "deleted.glsl").exists());
	}

	@Test
	public void saveMakesNoFolderThatWasDeletedOrRenamedElsewhere()
			throws IOException {
		OpenProject project = open();
		File notes = new File(root, "notes");
		File lib = new File(root, "lib");
		assertTrue(notes.mkdirs() && lib.mkdirs());
		document(project, "notes/x.glsl", "ex");
		document(project, "lib/y.glsl", "why");
		Document a = shown(project, "a.glsl", "stored");
		assertTrue(delete(notes));
		assertTrue(lib.renameTo(new File(root, "lib2")));
		edit(project, a, "typed");

		Map<ProjectPath, Outcome> outcomes = save(project, true);

		assertEquals(Set.of(path("a.glsl")), outcomes.keySet());
		assertFalse(notes.exists());
		assertFalse(lib.exists());
		assertEquals("why", read("lib2/y.glsl"));
	}

	@Test
	public void keepIsNotTakenForAFileThatIsNoTextAnymore()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = shown(project, "a.glsl", "stored");
		edit(project, document, "mine");
		// Another app puts a picture in its place.
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0,
				0, 0, 0};
		Files.write(new File(root, "a.glsl").toPath(), png);
		refresh(project);

		assertEquals(FileTexts.Found.NOT_TEXT, project.found(a));
		assertNotNull(project.conflict(a));
		project.resolve(a, OpenProject.Choice.KEEP, project.seen(a));
		project.resolve(a, OpenProject.Choice.RELOAD, project.seen(a));
		settle();

		// Nothing was decided: the text stays, and no save writes over
		// what is there.
		assertEquals("mine", document.text().toString());
		assertNotNull(project.conflict(a));
		assertTrue(save(project, true).get(a) instanceof Conflict);
		assertArrayEquals(png, Files.readAllBytes(
				new File(root, "a.glsl").toPath()));
	}

	@Test
	public void keepIsForTheRevisionSeenAlsoAfterARestartAndAnAutoSave()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, shown(project, "a.glsl", "stored"), "mine");
		flush(project);
		write("a.glsl", "theirs 1");
		refresh(project);
		project.resolve(a, OpenProject.Choice.KEEP, project.seen(a));
		flush(project);

		// The process dies; another app writes again before any save.
		OpenProject restarted = open();
		write("a.glsl", "theirs 2");
		shown(restarted, "a.glsl");

		// An auto-save never uses the consent, and an explicit save
		// finds a revision nobody consented to.
		assertTrue(save(restarted, false).get(a) instanceof Conflict);
		assertEquals("theirs 2", read("a.glsl"));
		assertTrue(save(restarted, true).get(a) instanceof Conflict);
		assertEquals("theirs 2", read("a.glsl"));
		assertNotNull(restarted.conflict(a));
		// Asked again, for what is there now.
		restarted.resolve(a, OpenProject.Choice.KEEP, restarted.seen(a));
		assertTrue(save(restarted, false).get(a) instanceof Conflict);
		assertEquals("theirs 2", read("a.glsl"));
		assertTrue(save(restarted, true).get(a) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
	}

	@Test
	public void aSaveIsDecidedWhenItRunsNotWhenItWasAskedFor()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = shown(project, "a.glsl", "stored");
		edit(project, document, "mine");
		flush(project);
		// A look at storage is under way; the save nobody asked for
		// waits behind it. Meanwhile the text is undone and another app
		// deletes the file.
		project.refresh(reread -> {
		});
		List<Map<ProjectPath, Outcome>> saved = new ArrayList<>();
		project.save(false, saved::add);
		document.undo();
		assertFalse(document.isModified());
		assertTrue(new File(root, "a.glsl").delete());
		settle();

		// Without unsaved text when it runs, it writes nothing.
		assertEquals(List.of(Map.of()), saved);
		assertFalse(new File(root, "a.glsl").exists());
		assertEquals(FileTexts.Found.MISSING, project.found(a));
	}

	@Test
	public void unsavedTextOfAFileDeletedElsewhereIsWrittenOnlyOnceKept()
			throws IOException {
		OpenProject project = open();
		ProjectPath b = path("b.glsl");
		Document document = document(project, "b.glsl", "bee");
		shown(project, "a.glsl", "stored");
		edit(project, document, "typed");
		assertTrue(new File(root, "b.glsl").delete());

		assertTrue(save(project, true).get(b) instanceof Conflict);
		assertFalse(new File(root, "b.glsl").exists());

		project.resolve(b, OpenProject.Choice.KEEP, project.seen(b));
		assertTrue(save(project, true).get(b) instanceof Saved);
		assertEquals("typed", read("b.glsl"));
	}

	@Test
	public void closeKeepsTheTextAndIgnoresWhatArrivesLater()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored");
		edit(project, a, "mine");
		List<String> loaded = new ArrayList<>();
		project.load(path("b.glsl"), ProjectFiles.UNKNOWN, loaded::add);

		project.close();
		settle();
		steps.later.run();

		assertTrue(loaded.isEmpty());
		assertEquals(0, worker.size());
		assertEquals("mine", journaled("a.glsl"));
	}

	@Test
	public void reloadOfARevisionNoLongerStoredKeepsTheText()
			throws IOException {
		OpenProject project = editedElsewhere();
		String seen = project.seen(path("a.glsl"));
		write("a.glsl", "again");

		project.resolve(path("a.glsl"), OpenProject.Choice.RELOAD, seen);
		settle();

		assertEquals("mine", project.document(path("a.glsl")).text()
				.toString());
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
	}

	@Test
	public void copyWritesTheCurrentTextsAndLeavesTheProjectAsItIs()
			throws IOException {
		write("b.glsl", "stored b");
		write("c.glsl", "stored c");
		OpenProject project = open();
		Document a = document(project, "a.glsl", "stored a");
		edit(project, a, "mine");
		flush(project);
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;
		destination.put("c.glsl", "taken");

		Map<ProjectPath, String> problems = await(done -> project.copyTo(
				destination, List.of(path("a.glsl"), path("b.glsl"),
						path("c.glsl"), path("gone.glsl")), done));

		assertEquals("mine", destination.text("a.glsl"));
		assertEquals("stored b", destination.text("b.glsl"));
		assertEquals("taken", destination.text("c.glsl"));
		assertNull(problems.get(path("a.glsl")));
		assertNull(problems.get(path("b.glsl")));
		assertNotNull(problems.get(path("c.glsl")));
		assertNotNull(problems.get(path("gone.glsl")));
		assertTrue(a.isModified());
		assertEquals("mine", journaled("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		assertEquals("stored a", read("a.glsl"));
	}

	@Test
	public void copiesTheUnsavedTextOfASessionWhoseFilesAreGone()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		OpenProject gone = open(live(), locator ->
				new SafProjects.Opened(null, null, "the folder is gone"));
		MemoryProjectFiles destination = new MemoryProjectFiles();
		destination.writable = true;

		Map<ProjectPath, String> problems = await(done -> gone.copyTo(
				destination, List.of(path("a.glsl")), done));

		assertNull(problems.get(path("a.glsl")));
		assertEquals("mine", destination.text("a.glsl"));
	}

	@Test
	public void keepsEditsUntilAnExplicitSave() throws IOException {
		OpenProject project = editedElsewhere();

		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP,
				project.seen(path("a.glsl")));

		assertTrue(project.conflicts().isEmpty());
		assertTrue(save(project, false).get(path("a.glsl")) instanceof
				Conflict);
		assertTrue(project.document(path("a.glsl")).isModified());
		assertEquals("theirs", read("a.glsl"));
		assertTrue(save(project, true).get(path("a.glsl")) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void savesNothingOfAFileInConflict() throws IOException {
		OpenProject project = editedElsewhere();

		assertTrue(save(project, true).get(path("a.glsl")) instanceof
				Conflict);

		assertEquals("theirs", read("a.glsl"));
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
	}

	@Test
	public void keptEditsStayKeptAfterARestart() throws IOException {
		OpenProject project = editedElsewhere();
		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP,
				project.seen(path("a.glsl")));
		flush(project);

		OpenProject restarted = open();

		assertTrue(restarted.conflicts().isEmpty());
		assertTrue(save(restarted, false).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
		assertTrue(save(restarted, true).get(path("a.glsl")) instanceof
				Saved);
		assertEquals("mine", read("a.glsl"));
		assertTrue(kept().isEmpty());
	}

	@Test
	public void keepingEditsOfAFileDeletedElsewhereCreatesItAgain()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		edit(project, document(project, "a.glsl"), "mine");
		assertTrue(new File(root, "a.glsl").delete());
		refresh(project);
		assertEquals("the file was deleted",
				project.conflicts().get(path("a.glsl")));
		assertNull(project.seen(path("a.glsl")));

		project.resolve(path("a.glsl"), OpenProject.Choice.KEEP, null);

		assertTrue(save(project, true).get(path("a.glsl")) instanceof Saved);
		assertEquals("mine", read("a.glsl"));
	}

	@Test
	public void fileChangedWhileTheProcessWasDeadIsInConflict()
			throws IOException {
		OpenProject project = open();
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		write("a.glsl", "theirs");

		OpenProject restarted = open();

		assertEquals(Set.of(path("a.glsl")), restarted.conflicts().keySet());
		assertNotNull(restarted.seen(path("a.glsl")));
		assertEquals("mine", text(restarted, "a.glsl"));
		assertTrue(save(restarted, true).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
	}

	@Test
	public void buildsAFileNotInMemoryFromItsJournal() throws IOException {
		write("main.frag", "#include \"lib.glsl\"\n" + SHADER);
		write("lib.glsl", "// lib\n");
		OpenProject project = open();
		edit(project, document(project, "lib.glsl"), "// edited\n");
		flush(project);

		OpenProject restarted = open();

		assertNull(restarted.document(path("lib.glsl")));
		assertEquals("// edited\n", built(prepare(restarted), "lib.glsl"));
		assertEquals("// lib\n", read("lib.glsl"));
	}

	@Test
	public void refreshGivesCleanDocumentsTheChangedFile()
			throws IOException {
		write("a.glsl", "a");
		write("b.glsl", "b");
		OpenProject project = open();
		Document a = document(project, "a.glsl");
		Document b = document(project, "b.glsl");
		edit(project, b, "b!");
		write("a.glsl", "a2");

		assertEquals(Set.of(path("a.glsl")), refresh(project));

		assertSame(a, project.document(path("a.glsl")));
		assertEquals("a2", a.text().toString());
		assertFalse(a.isModified());
		assertEquals("b!", b.text().toString());
		assertTrue(project.conflicts().isEmpty());
		assertTrue(refresh(project).isEmpty());
		edit(project, a, "a3");
		assertTrue(save(project, false).get(path("a.glsl")) instanceof Saved);
		assertEquals("a3", read("a.glsl"));
		a.undo();
		a.undo();
		assertEquals("a", a.text().toString());
		assertTrue(a.isModified());
	}

	@Test
	public void refreshForgetsWhatListingsTold() {
		FailingFiles store = new FailingFiles(new DirectoryProjectFiles(root));
		OpenProject project = open(store);

		refresh(project);

		assertEquals(1, store.forgotten);
	}

	@Test
	public void budgetHoldsAfterUndoToClean() throws IOException {
		budget = 3 * OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		List<Document> edited = new ArrayList<>();
		for (int i = 0; i < 5; ++i) {
			ProjectPath file = path(i + ".glsl");
			write(file.toString(), "" + i);
			// Edited as soon as it is there.
			project.load(file, ProjectFiles.UNKNOWN, problem -> {
				Document document = project.document(file);
				edit(project, document, "edited " + file);
				edited.add(document);
			});
			settle();
		}
		project.show(path("4.glsl"));
		// Kept while the journal does not hold them.
		assertTrue(used(project) > budget);

		for (Document document : edited) {
			document.undo();
		}
		flush(project);

		assertTrue(used(project) <= budget);
		assertTrue(project.unsaved().isEmpty());
		assertNull(project.document(path("0.glsl")));
	}

	@Test
	public void modifiedFileDroppedFromMemoryReopensAsItWas()
			throws IOException {
		budget = OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		Document a = shown(project, "a.glsl", "stored");
		edit(project, a, "mine");
		write("a.glsl", "theirs");
		refresh(project);
		flush(project);
		shown(project, "b.glsl", "b");

		assertNull(project.document(path("a.glsl")));
		assertEquals(Set.of(path("a.glsl")), project.unsaved());
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		Document reopened = shown(project, "a.glsl");
		assertNotSame(a, reopened);
		assertEquals("mine", reopened.text().toString());
		assertTrue(reopened.isModified());
		assertTrue(save(project, true).get(path("a.glsl")) instanceof
				Conflict);
		assertEquals("theirs", read("a.glsl"));
	}

	@Test
	public void undoAfterReopeningEqualsUndoBeforeTheDrop()
			throws IOException {
		budget = OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		Document a = shown(project, "a.glsl", "one");
		edit(project, a, "two");
		edit(project, a, "three");
		a.select(1, 2);
		flush(project);
		shown(project, "b.glsl", "b");
		assertNull(project.document(path("a.glsl")));

		Document reopened = shown(project, "a.glsl");

		assertEquals("three", reopened.text().toString());
		assertEquals(new Document.Selection(1, 2), reopened.selection());
		assertTrue(reopened.isModified());
		reopened.undo();
		assertEquals("two", reopened.text().toString());
		reopened.undo();
		assertEquals("one", reopened.text().toString());
		assertFalse(reopened.canUndo());
		// The history does not tell which text is the stored one; a look
		// at storage does.
		assertTrue(reopened.isModified());
		refresh(project);
		assertFalse(reopened.isModified());
		assertTrue(project.unsaved().isEmpty());
	}

	@Test
	public void historySurvivesARestartForTheSameTextOnly()
			throws IOException {
		OpenProject project = open();
		Document a = document(project, "a.glsl", "one");
		edit(project, a, "two");
		assertTrue(save(project, true).get(path("a.glsl")) instanceof Saved);
		flush(project);

		Document restarted = document(open(), "a.glsl");
		assertFalse(restarted.isModified());
		restarted.undo();
		assertEquals("one", restarted.text().toString());
		assertTrue(restarted.isModified());

		write("a.glsl", "theirs");
		Document changed = document(open(), "a.glsl");
		assertEquals("theirs", changed.text().toString());
		assertFalse(changed.canUndo());
		File history = new File(new File(sessionsDir, id), "history");
		assertEquals(0, history.list().length);
	}

	@Test
	public void historyOfAnotherTextTheFileCannotTellApartIsNotTaken()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "?");
		edit(project, document, "\uD800");
		// The history is kept for the one lone surrogate.
		flush(project);

		// Only the journal learns of the other.
		edit(project, document, "\uDC00");
		project.persist();
		settle();
		OpenProject restarted = open();
		Document recovered = document(restarted, "a.glsl");

		assertEquals("\uDC00", recovered.text().toString());
		assertTrue(recovered.isModified());
		assertFalse(recovered.canUndo());
		assertEquals(Set.of(a), restarted.unsaved());
		restarted.persist();
		settle();
		assertEquals("\uDC00", journaled("a.glsl"));
	}

	@Test
	public void textAFileCannotHoldIsNeverSaved() throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "?");
		edit(project, document, "\uD800");
		project.persist();
		settle();

		// Written, the lone surrogate would be the '?' storage holds.
		refresh(project);

		assertTrue(document.isModified());
		assertEquals(Set.of(a), project.unsaved());
		assertEquals("\uD800", journaled("a.glsl"));
		Outcome outcome = save(project, true).get(a);
		assertTrue(outcome instanceof Failed);
		assertTrue(((Failed) outcome).reason(),
				((Failed) outcome).reason().contains("line 1"));
		assertEquals("?", read("a.glsl"));
		assertTrue(document.isModified());
		MemoryProjectFiles copy = new MemoryProjectFiles();
		copy.writable = true;
		Map<ProjectPath, String> problems = await(done -> project.copyTo(
				copy, List.of(a), done));
		assertNotNull(problems.get(a));
		assertNull(copy.text("a.glsl"));
		assertEquals("\uD800", text(open(), "a.glsl"));

		// With its pair, it is text a file holds.
		OpenProject next = open();
		edit(next, document(next, "a.glsl"), "\uD83D\uDE00");
		assertTrue(save(next, true).get(a) instanceof Saved);
		assertFalse(next.document(a).isModified());
		assertEquals("\uD83D\uDE00", text(open(), "a.glsl"));
	}

	@Test
	public void unsavedTextNotInMemoryThatStorageHoldsIsSaved()
			throws IOException {
		budget = 0;
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = shown(project, "a.glsl", "stored");
		// Typed back, not undone.
		edit(project, document, "other");
		edit(project, document, "stored");
		flush(project);
		shown(project, "b.glsl", "b");
		assertNull(project.document(a));
		assertEquals(Set.of(a), project.unsaved());

		refresh(project);

		assertTrue(project.unsaved().isEmpty());
		assertTrue(kept().isEmpty());

		// The same when the project opens.
		Document again = shown(project, "a.glsl");
		edit(project, again, "other");
		edit(project, again, "stored");
		flush(project);
		assertEquals(Set.of(a), kept());
		assertTrue(open().unsaved().isEmpty());
		assertTrue(kept().isEmpty());
	}

	@Test
	public void unsavedTextStaysUnsavedWhateverItsHistoryTold()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "saved");
		assertTrue(save(project, true).get(a) instanceof Saved);
		// The history is kept while the text is the saved one.
		flush(project);
		edit(project, document, "other");
		edit(project, document, "saved");
		project.persist();
		settle();
		// Storage does not hold that text anymore.
		write("a.glsl", "theirs");

		OpenProject restarted = open();
		Document recovered = document(restarted, "a.glsl");

		assertEquals("saved", recovered.text().toString());
		assertTrue(recovered.isModified());
		assertEquals(Set.of(a), restarted.conflicts().keySet());
		assertEquals("saved", journaled("a.glsl"));
		// Storage tells when it is the saved text, not the history.
		write("a.glsl", "saved");
		refresh(restarted);
		assertFalse(recovered.isModified());
		assertTrue(kept().isEmpty());
	}

	@Test
	public void selectionSurvivesARestartWithoutAChangeOfText()
			throws IOException {
		OpenProject project = open();
		Document document = document(project, "a.glsl", "stored text");
		document.select(2, 4);
		flush(project);
		assertEquals(new Document.Selection(2, 4),
				document(open(), "a.glsl").selection());

		OpenProject next = open();
		Document edited = document(next, "a.glsl");
		edit(next, edited, "edited text");
		flush(next);
		edited.select(1, 1);
		flush(next);

		assertEquals(new Document.Selection(1, 1),
				document(open(), "a.glsl").selection());
	}

	@Test
	public void reloadKeepsTheTextItReplacedForUndoAfterARestart()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);

		String problem = await(done -> project.reload(a, done));

		assertNull(problem);
		assertTrue(kept().isEmpty());
		// The process dies in the foreground: nothing more is flushed.
		Document restarted = document(open(), "a.glsl");
		assertEquals("stored", restarted.text().toString());
		assertFalse(restarted.isModified());
		restarted.undo();
		assertEquals("mine", restarted.text().toString());
		assertTrue(restarted.isModified());
	}

	@Test
	public void typingAfterAReloadIsAStepOfItsOwnAfterARestart()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");
		flush(project);
		write("a.glsl", "theirs");
		assertNull(reload(project, a));
		edit(project, document, "theirs typed");
		// The journal's timer writes it.
		steps.later.run();
		settle();
		assertEquals("theirs typed", journaled("a.glsl"));

		// The process dies in the foreground.
		Document restarted = document(open(), "a.glsl");
		assertEquals("theirs typed", restarted.text().toString());
		assertTrue(restarted.isModified());
		restarted.undo();
		assertEquals("theirs", restarted.text().toString());
		restarted.undo();
		assertEquals("mine", restarted.text().toString());
		restarted.undo();
		assertEquals("stored", restarted.text().toString());
	}

	@Test
	public void dropsTheDocumentsUsedLeastRecentlyFirst() throws IOException {
		budget = 3 * OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		Document shown = shown(project, "shown.glsl", "s");
		List<Document> opened = new ArrayList<>();
		for (int i = 0; i <= 3; ++i) {
			opened.add(document(project, i + ".glsl", "" + i));
		}

		assertNull(project.document(path("0.glsl")));
		for (int i = 1; i <= 3; ++i) {
			assertSame(opened.get(i), project.document(path(i + ".glsl")));
		}
		assertSame(shown, project.document(path("shown.glsl")));
		// One is used again before another comes.
		assertSame(opened.get(1), document(project, "1.glsl"));
		document(project, "4.glsl", "4");
		assertNull(project.document(path("2.glsl")));
		assertSame(opened.get(1), project.document(path("1.glsl")));
		assertSame(opened.get(3), project.document(path("3.glsl")));
	}

	@Test
	public void documentOpenedAndNotShownCountsAgainstTheBudget()
			throws IOException {
		budget = OpenProject.cost(new Document(Text.of("0")));
		OpenProject project = open();
		Document small = shown(project, "a.glsl", "a");
		write("b.glsl", "larger than the budget");
		ProjectPath b = path("b.glsl");
		List<Document> told = new ArrayList<>();

		// A caller that does not show it has it only for the call.
		project.load(b, ProjectFiles.UNKNOWN,
				problem -> told.add(project.document(b)));
		settle();
		assertNotNull(told.get(0));
		assertNull(project.document(b));
		assertSame(small, project.document(path("a.glsl")));

		// One that shows it keeps it.
		Document shown = shown(project, "b.glsl");
		assertSame(shown, project.document(b));
		assertNotSame(told.get(0), shown);
		assertTrue(used(project) <= budget);
	}

	@Test
	public void documentStaysUntilEveryoneWhoAskedForItWasTold()
			throws IOException {
		budget = 0;
		OpenProject project = open();
		shown(project, "b.glsl", "b");
		write("a.glsl", "larger than the budget");
		ProjectPath a = path("a.glsl");
		List<Document> told = new ArrayList<>();

		// The first to be told shows another file before using it.
		project.load(a, ProjectFiles.UNKNOWN, problem -> {
			project.show(path("b.glsl"));
			told.add(project.document(a));
		});
		project.load(a, ProjectFiles.UNKNOWN,
				problem -> told.add(project.document(a)));
		settle();

		assertEquals(2, told.size());
		assertNotNull(told.get(0));
		assertSame(told.get(0), told.get(1));
		assertNull(project.document(a));
	}

	@Test
	public void keepsACleanDocumentUntilItsJournalIsRemoved()
			throws IOException {
		Document sample = new Document(Text.of("a"));
		sample.replace(0, 1, Text.of("a!"));
		budget = OpenProject.cost(sample);
		OpenProject project = open();
		Document a = document(project, "a.glsl", "a");
		edit(project, a, "a!");
		project.persist();
		settle();
		a.undo();
		// Another one, edited as soon as it is there, takes the budget.
		write("b.glsl", "b");
		ProjectPath b = path("b.glsl");
		project.load(b, ProjectFiles.UNKNOWN, problem -> edit(project,
				project.document(b), "b!"));
		settle();

		assertSame(a, project.document(path("a.glsl")));
		assertTrue(used(project) > budget);
		project.persist();
		settle();
		assertNull(project.document(path("a.glsl")));
		assertNotNull(project.document(b));
		assertEquals(Set.of(b), kept());
	}

	@Test
	public void renameKeepsTheDocumentWithItsUnsavedTextAndUndo()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		ProjectPath b = path("b.glsl");
		Document document = shown(project, "a.glsl", "stored");
		edit(project, document, "mine");
		document.select(1, 2);
		project.runThisFile(a);
		List<Integer> heard = new ArrayList<>();
		project.setListener(Runnable::run,
				() -> heard.add(project.events(0).size()));

		OpenProject.Moved moved = await(done -> project.rename(a, b, done));

		assertEquals(new OpenProject.Renamed(a, b), moved);
		assertEquals(List.of(new OpenProject.Event(1, moved)),
				project.events(0));
		assertTrue(heard.contains(1));
		assertSame(document, project.document(b));
		assertNull(project.document(a));
		assertEquals(Set.of(b), project.unsaved());
		assertEquals(b, project.shown());
		assertEquals(b, project.running());
		assertEquals("stored", read("b.glsl"));
		assertFalse(new File(root, "a.glsl").exists());
		assertEquals("mine", journaled("b.glsl"));
		assertNull(journaled("a.glsl"));
		flush(project);

		// After a restart: the text, the selection, undo and what the
		// session showed and ran are under the new path.
		OpenProject restarted = open();
		assertEquals(Set.of(b), restarted.unsaved());
		assertEquals(b, restarted.shown());
		assertEquals(b, restarted.running());
		Document reopened = document(restarted, "b.glsl");
		assertEquals("mine", reopened.text().toString());
		assertEquals(new Document.Selection(1, 2), reopened.selection());
		reopened.undo();
		assertEquals("stored", reopened.text().toString());
	}

	@Test
	public void renameOfAFolderTakesEveryFileHeldUnderIt()
			throws IOException {
		write("lib/unsaved.glsl", "u");
		write("lib/deep/clean.glsl", "c");
		write("lib/other.glsl", "o");
		write("libs.glsl", "not under lib");
		OpenProject first = open();
		edit(first, document(first, "lib/other.glsl"), "other, unsaved");
		flush(first);
		// Held without being in memory, in memory with unsaved text,
		// and in memory without.
		OpenProject project = open();
		Document unsaved = document(project, "lib/unsaved.glsl");
		edit(project, unsaved, "mine");
		Document clean = shown(project, "lib/deep/clean.glsl");
		Document beside = document(project, "libs.glsl");

		OpenProject.Moved moved = await(done -> project.rename(
				path("lib"), path("shaders/common"), done));

		assertEquals(new OpenProject.Renamed(path("lib"),
				path("shaders/common")), moved);
		assertSame(unsaved, project.document(
				path("shaders/common/unsaved.glsl")));
		assertSame(clean, project.document(
				path("shaders/common/deep/clean.glsl")));
		assertSame(beside, project.document(path("libs.glsl")));
		assertEquals(path("shaders/common/deep/clean.glsl"),
				project.shown());
		assertEquals(Set.of(path("shaders/common/unsaved.glsl"),
				path("shaders/common/other.glsl")), project.unsaved());
		assertEquals(project.unsaved(), kept());
		assertEquals("other, unsaved", text(project,
				"shaders/common/other.glsl"));
		assertTrue(save(project, true).values().stream().allMatch(
				outcome -> outcome instanceof Saved));
		assertEquals("mine", read("shaders/common/unsaved.glsl"));
		assertEquals("other, unsaved", read("shaders/common/other.glsl"));
		assertFalse(new File(root, "lib").exists());
	}

	@Test
	public void renameIsRefusedAsAValueAndChangesNothing()
			throws IOException {
		write("taken.glsl", "t");
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");

		// The target exists; the project holds a file at the target.
		OpenProject.Moved exists = await(done -> project.rename(a,
				path("taken.glsl"), done));
		assertEquals(ProjectFiles.Refusal.EXISTS,
				((OpenProject.Refused) exists).refusal());
		document(project, "taken.glsl");
		OpenProject.Moved held = await(done -> project.rename(
				path("other.glsl"), path("taken.glsl"), done));
		assertEquals(ProjectFiles.Refusal.EXISTS,
				((OpenProject.Refused) held).refusal());
		OpenProject.Moved itself = await(done -> project.rename(
				path("lib"), path("lib/inner"), done));
		assertEquals(ProjectFiles.Refusal.FAILED,
				((OpenProject.Refused) itself).refusal());
		assertSame(document, project.document(a));
		assertTrue(project.events(0).isEmpty());

		// The file vanished elsewhere: that is what is seen of it.
		assertTrue(new File(root, "a.glsl").delete());
		OpenProject.Moved absent = await(done -> project.rename(a,
				path("b.glsl"), done));
		assertEquals(ProjectFiles.Refusal.ABSENT,
				((OpenProject.Refused) absent).refusal());
		assertSame(document, project.document(a));
		assertEquals(FileTexts.Found.MISSING, project.found(a));
		assertEquals("the file was deleted", project.conflicts().get(a));
		flush(project);
		assertEquals("mine", journaled("a.glsl"));
	}

	@Test
	public void renameComesAfterASaveUnderWayAndSeesWhatChangedElsewhere()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		ProjectPath b = path("b.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");
		List<Object> order = new ArrayList<>();

		project.save(true, order::add);
		project.rename(a, b, order::add);
		settle();

		assertEquals(2, order.size());
		assertEquals(new OpenProject.Renamed(a, b), order.get(1));
		assertEquals("mine", read("b.glsl"));
		assertFalse(document.isModified());
		assertTrue(kept().isEmpty());

		// Renamed as it is, whatever it holds; looked at afterwards.
		write("b.glsl", "theirs");
		OpenProject.Moved back = await(done -> project.rename(b, a, done));
		assertEquals(new OpenProject.Renamed(b, a), back);
		assertEquals("theirs", document.text().toString());
		edit(project, document, "mine again");
		write("a.glsl", "theirs again");
		OpenProject.Moved again = await(done -> project.rename(a, b, done));
		assertEquals(new OpenProject.Renamed(a, b), again);
		assertEquals(Set.of(b), project.conflicts().keySet());
		assertEquals("mine again", document.text().toString());
	}

	@Test
	public void unsavedTextIsUnderOneOfTheTwoPathsWhereverARenameDies()
			throws IOException {
		ProjectPath a = path("a.glsl");
		ProjectPath b = path("b.glsl");
		// How far it gets: nothing done; storage renamed and main not
		// told; main told and the journal not moved; all of it.
		for (int reached = 0; reached <= 3; ++reached) {
			delete(new File(root, "b.glsl"));
			write("a.glsl", "stored");
			OpenProject project = open();
			refresh(project);
			Document document = document(project, "a.glsl");
			edit(project, document, "mine " + reached);
			flush(project);

			project.rename(a, b, moved -> {
			});
			if (reached >= 1) {
				worker.step();
			}
			if (reached >= 2) {
				main.run();
			}
			if (reached >= 3) {
				settle();
			}
			worker.clear();
			main.clear();

			OpenProject restarted = open();
			ProjectPath at = reached == 0 ? a : b;
			String name = "reached " + reached;
			assertEquals(name, Set.of(at), restarted.unsaved());
			assertEquals(name, Set.of(at), kept());
			assertTrue(name, restarted.conflicts().isEmpty());
			assertEquals(name, "mine " + reached, text(restarted,
					at.toString()));
			assertEquals(name, "stored", read(at.toString()));
			assertNull(name, new ProjectSessions(sessionsDir).journal(id)
					.note());
			assertTrue(save(restarted, true).get(at) instanceof Saved);
		}
	}

	@Test
	public void typingWhileARenameIsUnderWayIsKeptUnderTheNewPath()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		ProjectPath b = path("b.glsl");
		Document document = document(project, "a.glsl", "stored");
		edit(project, document, "mine");
		project.rename(a, b, moved -> {
		});
		// Storage renamed; main does not know yet, and the user types.
		worker.step();
		edit(project, document, "later");
		project.persist();
		settle();

		assertSame(document, project.document(b));
		assertEquals("later", journaled("b.glsl"));
		assertNull(journaled("a.glsl"));
		assertEquals(Set.of(b), kept());

		// The same when the project is closed at that moment.
		project.rename(b, a, moved -> {
		});
		worker.step();
		edit(project, document, "latest");
		project.close();
		settle();
		OpenProject restarted = open();
		assertEquals(Set.of(a), restarted.unsaved());
		assertEquals("latest", text(restarted, "a.glsl"));
		assertEquals(Set.of(a), kept());
	}

	@Test
	public void deleteAsksBeforeUnsavedTextGoesAndThenNothingBringsItBack()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		Document document = shown(project, "a.glsl", "stored");
		edit(project, document, "mine");
		flush(project);
		File histories = new File(new File(sessionsDir, id), "history");
		assertEquals(1, histories.list().length);

		// Not without being asked, and not for a text shown before.
		OpenProject.Moved first = await(done -> project.delete(a, Map.of(),
				done));
		Map<ProjectPath, Integer> texts =
				((OpenProject.Unsaved) first).texts();
		assertEquals(Set.of(a), texts.keySet());
		assertSame(document, project.document(a));
		assertEquals("stored", read("a.glsl"));
		edit(project, document, "mine, and more");
		OpenProject.Moved stale = await(done -> project.delete(a, texts,
				done));
		assertTrue(stale instanceof OpenProject.Unsaved);
		assertNotEquals(texts, ((OpenProject.Unsaved) stale).texts());
		assertEquals("stored", read("a.glsl"));
		flush(project);
		assertEquals("mine, and more", journaled("a.glsl"));

		OpenProject.Moved deleted = await(done -> project.delete(a,
				((OpenProject.Unsaved) stale).texts(), done));

		assertEquals(new OpenProject.Deleted(a), deleted);
		assertEquals(List.of(new OpenProject.Event(1, deleted)),
				project.events(0));
		assertFalse(new File(root, "a.glsl").exists());
		assertNull(project.document(a));
		assertNull(project.shown());
		assertTrue(project.unsaved().isEmpty());
		assertTrue(kept().isEmpty());
		assertEquals(0, histories.list().length);
		// Neither a document someone kept, nor a timer, a fence or the
		// closing of the project.
		edit(project, document, "typed into what was closed");
		steps.later.run();
		project.persist();
		flush(project);
		project.close();
		settle();
		assertTrue(kept().isEmpty());
		OpenProject restarted = open();
		assertTrue(restarted.unsaved().isEmpty());
		assertNull(restarted.shown());
		assertNotNull(load(restarted, "a.glsl"));
	}

	@Test
	public void deleteClosesWhatHasNoUnsavedTextAndKeepsWhatIsTypedSince()
			throws IOException {
		write("lib/clean.glsl", "c");
		write("lib/unsaved.glsl", "u");
		write("lib/other.glsl", "o");
		OpenProject first = open();
		edit(first, document(first, "lib/other.glsl"), "other, unsaved");
		flush(first);
		OpenProject project = open();
		ProjectPath clean = path("lib/clean.glsl");
		ProjectPath unsaved = path("lib/unsaved.glsl");
		ProjectPath other = path("lib/other.glsl");
		Document cleanDocument = shown(project, "lib/clean.glsl");
		Document unsavedDocument = document(project, "lib/unsaved.glsl");
		edit(project, unsavedDocument, "mine");
		List<Integer> heard = new ArrayList<>();
		project.setListener(Runnable::run,
				() -> heard.add(project.events(0).size()));

		// A file without unsaved text needs no asking.
		OpenProject.Moved one = await(done -> project.delete(clean,
				Map.of(), done));
		assertEquals(new OpenProject.Deleted(clean), one);
		assertNull(project.document(clean));
		assertTrue(heard.contains(1));
		assertFalse(new File(root, "lib/clean.glsl").exists());

		// A folder asks for every file with unsaved text under it, in
		// memory or not.
		OpenProject.Moved asked = await(done -> project.delete(path("lib"),
				Map.of(), done));
		Map<ProjectPath, Integer> texts =
				((OpenProject.Unsaved) asked).texts();
		assertEquals(Set.of(unsaved, other), texts.keySet());

		// Storage deleted it; before main knows, the user types.
		project.delete(path("lib"), texts, moved -> {
		});
		worker.step();
		edit(project, unsavedDocument, "later");
		settle();

		assertFalse(new File(root, "lib").exists());
		assertSame(unsavedDocument, project.document(unsaved));
		assertEquals(Set.of(unsaved), project.unsaved());
		assertEquals(FileTexts.Found.MISSING, project.found(unsaved));
		flush(project);
		assertEquals(Set.of(unsaved), kept());
		assertEquals("later", journaled("lib/unsaved.glsl"));
		assertNotSame(cleanDocument, project.document(clean));
	}

	@Test
	public void deleteOfAFileGoneAlreadyTakesTheProjectsPartOfIt()
			throws IOException {
		OpenProject project = editedElsewhere();
		ProjectPath a = path("a.glsl");
		flush(project);
		assertTrue(new File(root, "a.glsl").delete());
		refresh(project);
		assertEquals("the file was deleted", project.conflicts().get(a));

		OpenProject.Moved asked = await(done -> project.delete(a, Map.of(),
				done));
		OpenProject.Moved deleted = await(done -> project.delete(a,
				((OpenProject.Unsaved) asked).texts(), done));

		assertEquals(new OpenProject.Deleted(a), deleted);
		assertTrue(project.unsaved().isEmpty());
		assertTrue(project.conflicts().isEmpty());
		assertTrue(kept().isEmpty());
	}

	@Test
	public void deleteThatStorageRefusesLeavesEverythingAsItWas()
			throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open(new FailingFiles(
				new DirectoryProjectFiles(root)));
		ProjectPath a = path("a.glsl");
		Document document = document(project, "a.glsl");
		edit(project, document, "mine");
		OpenProject.Moved asked = await(done -> project.delete(a, Map.of(),
				done));

		OpenProject.Moved refused = await(done -> project.delete(a,
				((OpenProject.Unsaved) asked).texts(), done));

		assertEquals(ProjectFiles.Refusal.UNSUPPORTED,
				((OpenProject.Refused) refused).refusal());
		assertSame(document, project.document(a));
		assertTrue(project.events(0).isEmpty());
		assertEquals("stored", read("a.glsl"));
		edit(project, document, "mine still");
		flush(project);
		assertEquals("mine still", journaled("a.glsl"));
		assertNull(new ProjectSessions(sessionsDir).journal(id).note());
	}

	@Test
	public void renameTheProcessDiedInIsFinishedWhenTheProjectOpens()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		ProjectPath b = path("b.glsl");
		Document document = shown(project, "a.glsl", "stored");
		edit(project, document, "mine");
		document.select(1, 2);
		project.runThisFile(a);
		flush(project);
		// The rename was noted and storage made it; then the process
		// died.
		Journal journal = new ProjectSessions(sessionsDir).journal(id).own();
		assertTrue(journal.setNote(journal.noted(new Journal.Move(a, b))));
		assertTrue(new File(root, "a.glsl").renameTo(
				new File(root, "b.glsl")));

		OpenProject restarted = open();

		assertEquals(Set.of(b), restarted.unsaved());
		assertEquals(Set.of(b), kept());
		assertTrue(restarted.conflicts().isEmpty());
		assertEquals(b, restarted.shown());
		assertEquals(b, restarted.running());
		Document reopened = document(restarted, "b.glsl");
		assertEquals("mine", reopened.text().toString());
		assertEquals(new Document.Selection(1, 2), reopened.selection());
		reopened.undo();
		assertEquals("stored", reopened.text().toString());
		assertNull(new ProjectSessions(sessionsDir).journal(id).note());
	}

	@Test
	public void renameThatStorageDidNotMakeIsDroppedWhenTheProjectOpens()
			throws IOException {
		OpenProject project = open();
		ProjectPath a = path("a.glsl");
		edit(project, document(project, "a.glsl", "stored"), "mine");
		flush(project);
		// The process died after noting the rename, before storage.
		Journal journal = new ProjectSessions(sessionsDir).journal(id).own();
		assertTrue(journal.setNote(journal.noted(new Journal.Move(a, path("b.glsl")))));

		OpenProject restarted = open();

		assertEquals(Set.of(a), restarted.unsaved());
		assertEquals("mine", text(restarted, "a.glsl"));
		assertNull(new ProjectSessions(sessionsDir).journal(id).note());

		// A deletion that storage made takes the journal with it.
		journal = new ProjectSessions(sessionsDir).journal(id).own();
		assertTrue(journal.setNote(journal.noted(new Journal.Move(a, null))));
		assertTrue(new File(root, "a.glsl").delete());
		OpenProject after = open();
		assertTrue(after.unsaved().isEmpty());
		assertTrue(kept().isEmpty());
		assertNull(new ProjectSessions(sessionsDir).journal(id).note());
	}

	@Test
	public void budgetIsAnEighthOfTheHeapWithinItsBounds() {
		long mib = 1024 * 1024;

		assertEquals(8 * mib, OpenProject.budget(32 * mib));
		assertEquals(16 * mib, OpenProject.budget(128 * mib));
		assertEquals(32 * mib, OpenProject.budget(512 * mib));
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
		write("main.frag", SHADER);
		assertTrue(new File(root, "main.frag").setLastModified(
				1_700_000_000_000L));

		OpenProject.Item item = open().list(null).items().get(0);

		assertEquals(SHADER.length(), item.size());
		assertEquals(1_700_000_000_000L, item.modified());
	}

	@Test
	public void keepsWhatTheLastBuildRead() throws IOException {
		write("main.frag", "#include \"lib/common.glsl\"\n" +
				"#pragma se input \"noise.png\"\n" +
				"uniform sampler2D noise;\n" + SHADER);
		write("lib/common.glsl", "// common\n");
		Files.write(new File(root, "noise.png").toPath(), Pictures.png(1));
		write("shader-project.json", "{}");
		write("other.glsl", SHADER);
		OpenProject project = open();
		assertEquals(List.of(), project.used());

		prepare(project);

		assertEquals(List.of(new OpenProject.Used(path("main.frag"), false,
								new FileRole(FileRole.Kind.RUNS, List.of())),
						new OpenProject.Used(path("lib/common.glsl"), false,
								new FileRole(FileRole.Kind.INCLUDE,
										List.of(path("main.frag")))),
						new OpenProject.Used(path("noise.png"), true,
								new FileRole(FileRole.Kind.TEXTURE,
										List.of(path("main.frag"))))),
				project.used());
		assertTrue(project.uses(path("lib/common.glsl")));
		assertFalse(project.uses(path("other.glsl")));
		assertFalse(project.uses(ProjectManifest.FILE));
	}

	// Each file of a shader of passes says what it is to it, as the
	// Running section shows it: Ink's three passes, its includes and
	// its texture.
	@Test
	public void knowsWhatEachFileIsToTheRunningShader() throws IOException {
		String lib = "float hash(vec2 p) { return p.x; }\n";
		write("lib/noise.glsl", lib);
		write("lib/color.glsl", "vec3 tint(float v) { return vec3(v); }\n");
		new File(root, "textures").mkdirs();
		Files.write(new File(root, "textures/noise.png").toPath(),
				Pictures.png(1));
		write("Ink/main.frag", "#pragma se input \"ink.frag\"\n" +
				"uniform sampler2D ink;\n" +
				"#pragma se input \"../textures/noise.png\"\n" +
				"uniform sampler2D paper;\n" + SHADER);
		write("Ink/ink.frag", "#pragma se target initial=\"seed.frag\"\n" +
				"#pragma se input \"ink.frag\" frame=previous\n" +
				"uniform sampler2D ink;\n" +
				"#include \"../lib/noise.glsl\"\n" +
				"#include \"../lib/color.glsl\"\n" + SHADER);
		write("Ink/seed.frag", "#include \"../lib/noise.glsl\"\n" + SHADER);
		OpenProject project = open();
		project.runThisFile(path("Ink/main.frag"));

		prepare(project);

		java.util.Map<ProjectPath, FileRole> roles =
				new java.util.LinkedHashMap<>();
		for (OpenProject.Used used : project.used()) {
			roles.put(used.path(), used.role());
		}
		assertEquals(new FileRole(FileRole.Kind.RUNS, List.of()),
				roles.get(path("Ink/main.frag")));
		assertEquals(FileRole.Kind.PASS, roles.get(path("Ink/ink.frag"))
				.kind());
		assertEquals(new FileRole(FileRole.Kind.FIRST_FRAME,
				List.of(path("Ink/ink.frag"))), roles.get(path("Ink/seed.frag")));
		assertEquals(new FileRole(FileRole.Kind.INCLUDE,
						List.of(path("Ink/ink.frag"), path("Ink/seed.frag"))),
				roles.get(path("lib/noise.glsl")));
		assertEquals(new FileRole(FileRole.Kind.INCLUDE,
				List.of(path("Ink/ink.frag"))), roles.get(path("lib/color.glsl")));
		assertEquals(new FileRole(FileRole.Kind.TEXTURE,
						List.of(path("Ink/main.frag"))),
				roles.get(path("textures/noise.png")));
	}

	// A report of the build of what ran before may come after what
	// runs changed: that build is not read by what runs now.
	@Test
	public void readsOnlyOfTheBuildOfWhatRunsNow() throws IOException {
		write("a.frag", "#include \"lib/common.glsl\"\n" + SHADER);
		write("b.frag", SHADER);
		write("lib/common.glsl", "// common\n");
		OpenProject project = open();
		project.runThisFile(path("a.frag"));
		prepare(project);
		assertTrue(project.reads(path("lib/common.glsl")));
		assertTrue(project.reads(path("a.frag")));

		project.runThisFile(path("b.frag"));

		assertTrue(project.uses(path("a.frag")));
		assertFalse(project.reads(path("a.frag")));
		assertFalse(project.reads(path("lib/common.glsl")));

		prepare(project);

		assertTrue(project.reads(path("b.frag")));
		assertFalse(project.reads(path("a.frag")));
	}

	@Test
	public void theEntryOnlyCheckedToExistIsNotUsed() throws IOException {
		write("a.glsl", SHADER);
		write("b.glsl", SHADER);
		write("shader-project.json", "{\"entry\": \"a.glsl\"}");
		OpenProject project = open();
		project.runThisFile(path("b.glsl"));
		settle();

		Preparation preparation = prepare(project);

		assertTrue(preparation.files().containsKey(path("a.glsl")));
		assertEquals(List.of(runs("b.glsl")),
				project.used());
		assertFalse(project.uses(path("a.glsl")));
	}

	@Test
	public void leavesOutFilesTheBuildCouldNotRead() throws IOException {
		write("main.frag", "#include \"missing.glsl\"\n" + SHADER);
		OpenProject project = open();

		prepare(project);

		assertEquals(List.of(runs("main.frag")),
				project.used());
	}

	@Test
	public void runsATextFileOfAnyNameWhoseOwnTextDefinesMain()
			throws IOException {
		write("main.frag", SHADER);
		write("tight.glsl", "void main(){}");
		write("lib/common.glsl", "float f() { return 1.0; }\n");
		write("declared.glsl", "void main();\n");
		write("disabled.glsl", "#if 0\nvoid main() {}\n#endif\n");
		write("commented.glsl", "// void main() {}\n");
		write("notes.txt", SHADER);
		write("other.wgsl", SHADER);
		OpenProject project = open();

		assertTrue(canRun(project, "main.frag"));
		assertTrue(canRun(project, "tight.glsl"));
		assertFalse(canRun(project, "lib/common.glsl"));
		assertFalse(canRun(project, "declared.glsl"));
		assertFalse(canRun(project, "disabled.glsl"));
		assertFalse(canRun(project, "commented.glsl"));
		// The name plays no part.
		assertTrue(canRun(project, "notes.txt"));
		// Unless it is another shading language's.
		assertFalse(canRun(project, "other.wgsl"));
		assertEquals(OpenProject.Runs.NO, project.runs(path("other.glsl")));
		edit(project, document(project, "lib/common.glsl"), "void main() {}");
		assertTrue(canRun(project, "lib/common.glsl"));
	}

	@Test
	public void findsWhetherALargeFileRunsOnTheWorkerOncePerText()
			throws IOException {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 500_000; ++i) {
			text.append("float x;\n");
		}
		write("big.glsl", text.append("void main() {}\n").toString());
		OpenProject project = open();
		Document big = document(project, "big.glsl");
		List<Runnable> posted = new ArrayList<>();
		project.setListener(posted::add, () -> {
		});

		assertEquals(OpenProject.Runs.UNKNOWN, project.runs(path("big.glsl")));
		assertEquals(OpenProject.Runs.UNKNOWN, project.runs(path("big.glsl")));
		assertEquals(1, worker.size());
		settle();
		assertEquals(1, drain(posted));
		assertEquals(OpenProject.Runs.YES, project.runs(path("big.glsl")));

		edit(project, big, "void f() {}");
		drain(posted);
		// The answer before stays until the new one is known and told.
		assertEquals(OpenProject.Runs.YES, project.runs(path("big.glsl")));
		assertEquals(1, worker.size());
		settle();
		assertEquals(1, drain(posted));
		assertEquals(OpenProject.Runs.NO, project.runs(path("big.glsl")));
	}

	// The bar of the editor: a key costs no work in proportion to the
	// text or the folder. The journal needs only its timers, a delay
	// after the last key and a limit after the first, which post one
	// write to the worker when they ring.
	@Test
	public void keysAreNotToldAndQueueNoWorkButTheJournalsTimers()
			throws IOException {
		OpenProject project = open();
		Document a = shown(project, "a.glsl", SHADER);
		ProjectPath path = path("a.glsl");
		List<Runnable> posted = new ArrayList<>();
		int[] heard = {0};
		// As the screen: what it is told lists the drawer, whose row of
		// the file asks whether it runs.
		project.setListener(posted::add, () -> {
			++heard[0];
			project.runs(path);
		});
		project.runs(path);
		// The file turns unsaved: that is told once.
		a.replace(0, 0, Text.of(" "));
		settle(posted);
		steps.later.run();
		settle(posted);
		heard[0] = 0;
		int queued = 0;

		for (int i = 0; i < 50; ++i) {
			a.replace(0, 0, Text.of("x"));
			do {
				queued += worker.size();
				settle();
			} while (drain(posted) > 0);
		}

		assertEquals(0, heard[0]);
		assertEquals(0, queued);
		assertEquals(2, steps.later.size());
		steps.later.run();
		assertEquals(1, worker.size());
		settle(posted);
		assertEquals(a.text().toString(), journaled("a.glsl"));
		// Asked, it answers what it knew and reads the text once; the
		// same answer is not told.
		assertEquals(OpenProject.Runs.YES, project.runs(path));
		assertEquals(1, worker.size());
		settle(posted);
		assertEquals(0, heard[0]);
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
			document(project, file);
		}
		edit(project, "lib/x.glsl", "mine");
		edit(project, "lib/deep/y.glsl", "mine");
		edit(project, "library.glsl", "mine");
		write("lib/x.glsl", "theirs");
		refresh(project);
		project.setDiagnostics(build(project), List.of(
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
		write("main.frag", SHADER);

		assertTrue(open().offers(path("main.frag")).write());
		assertEquals(ProjectFiles.Offers.NONE, open(new MemoryProjectFiles()
				.put("main.frag", SHADER)).offers(path("main.frag")));
	}

	@Test
	public void tellsTheListenerOnceThroughItsExecutor() throws IOException {
		write("a.glsl", "a");
		OpenProject project = open();
		document(project, "a.glsl");
		List<Runnable> posted = new ArrayList<>();
		int[] heard = {0};
		project.setListener(posted::add, () -> ++heard[0]);

		edit(project, "a.glsl", "a!");
		edit(project, "a.glsl", "a!!");
		OpenProject.Build build = build(project);
		project.setDiagnostics(build, List.of(ProjectDiagnostic.error("e")));

		assertEquals(0, heard[0]);
		assertEquals(1, posted.size());
		posted.remove(0).run();
		assertEquals(1, heard[0]);
		edit(project, "a.glsl", "a!!!");
		project.setDiagnostics(build, List.of());
		assertTrue(posted.isEmpty());
		assertEquals(1, project.errors());
	}

	@Test
	public void tellsTheListenerWhatTheScreensMark() throws IOException {
		write("main.frag", "#include \"lib.glsl\"\n" + SHADER);
		write("lib.glsl", "// lib\n");
		write("b.glsl", SHADER);
		OpenProject project = open();
		document(project, "lib.glsl");
		List<Runnable> posted = new ArrayList<>();
		project.setListener(posted::add, () -> {
		});

		edit(project, "lib.glsl", "// edited\n");
		assertEquals(1, drain(posted));
		save(project, true);
		assertEquals(1, drain(posted));
		OpenProject.Build build = build(project);
		assertEquals(1, drain(posted));
		project.setDiagnostics(build, List.of(ProjectDiagnostic.error("e")));
		assertEquals(1, drain(posted));
		project.runThisFile(path("b.glsl"));
		assertEquals(1, drain(posted));
		edit(project, "lib.glsl", "// mine\n");
		drain(posted);
		write("lib.glsl", "// theirs\n");
		refresh(project);
		assertEquals(Set.of(path("lib.glsl")), project.conflicts().keySet());
		assertEquals(1, drain(posted));
		refresh(project);
		assertEquals(0, drain(posted));

		project.setListener(null, null);
		project.resolve(path("lib.glsl"), OpenProject.Choice.KEEP,
				project.seen(path("lib.glsl")));
		assertEquals(0, drain(posted));
	}

	@Test
	public void knowsItsNameAndWhereItIs() {
		assertNull(open().name());
		new ProjectSessions(sessionsDir).remember(id, "aurora",
				"ShaderEditor / Shaders", 1);

		OpenProject project = open();

		assertEquals("aurora", project.name());
		assertEquals("ShaderEditor / Shaders", project.location());
	}

	@Test
	public void showsTheFileShownLastAfterReopening() throws IOException {
		write("lib/common.glsl", "// common\n");
		OpenProject project = open();
		assertNull(project.show(path("lib/common.glsl")));

		project.persist();
		settle();

		assertEquals(path("lib/common.glsl"), open().shown());
		assertEquals(path("lib/common.glsl"),
				new ProjectSessions(sessionsDir).shown(id));
	}

	@Test
	public void groupsDiagnosticsByFile() throws IOException {
		OpenProject project = open();
		ProjectDiagnostic inMain = new ProjectDiagnostic(Severity.WARNING,
				"unused", path("main.frag"), 3);
		ProjectDiagnostic inLib = new ProjectDiagnostic(Severity.ERROR,
				"syntax error", path("lib/common.glsl"), 7);
		ProjectDiagnostic nowhere = ProjectDiagnostic.error("driver crashed");

		project.setDiagnostics(build(project),
				List.of(inMain, inLib, nowhere));

		assertEquals(List.of(inLib), project.diagnostics(
				path("lib/common.glsl")));
		assertEquals(List.of(inMain), project.diagnostics(path("main.frag")));
		assertEquals(List.of(), project.diagnostics(path("other.glsl")));
		assertEquals(List.of(), project.diagnostics(null));
		assertEquals(List.of(inMain, inLib, nowhere), project.diagnostics());
		assertEquals(2, project.errors());
	}

	@Test
	public void opensOnlyTextOfAtMost16MiBInTheEditor() throws IOException {
		// A picture named like a shader, a shader named like a picture.
		Files.write(new File(root, "image.frag").toPath(), Pictures.png(1));
		write("shader.png", "void main() {}");
		Files.write(new File(root, "data.bin").toPath(), new byte[]{0, 1, 2});
		write("notes.txt", "notes");
		write("big.glsl", "// " + "x".repeat(2 * 1024 * 1024));
		byte[] large = java.util.Arrays.copyOf(Pictures.png(),
				(int) Document.MAX_BYTES + 1);
		Files.write(new File(root, "large").toPath(), large);
		OpenProject project = open();

		assertEquals("image.frag is a picture, not text",
				load(project, "image.frag"));
		assertEquals("void main() {}", text(project, "shader.png"));
		// Told from its first bytes, without reading it.
		assertEquals("large is a picture, not text",
				this.<String>await(done -> project.load(path("large"),
						Document.MAX_BYTES + 1, done)));
		assertNotNull(load(project, "data.bin"));
		assertNotNull(load(project, "missing.glsl"));
		assertEquals("notes", text(project, "notes.txt"));
		assertEquals("big.glsl is larger than 16 MiB",
				this.<String>await(done -> project.load(path("big.glsl"),
						Document.MAX_BYTES + 1, done)));
		assertNull(load(project, "big.glsl"));
	}

	@Test
	public void fileTheDeviceHasNoMemoryForIsRefusedWithoutACrash()
			throws IOException {
		write("a.glsl", "stored");
		write("big.glsl", "big");
		ProjectPath big = path("big.glsl");
		DirectoryProjectFiles files = new DirectoryProjectFiles(root);
		OpenProject project = open(new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				return read(path, Long.MAX_VALUE);
			}

			@Override
			public Read read(ProjectPath path, long limit) {
				if (big.equals(path)) {
					throw new OutOfMemoryError();
				}
				return files.read(path, limit);
			}
		});

		String problem = load(project, "big.glsl");

		assertEquals("big.glsl is too large for this device", problem);
		assertNull(project.document(big));
		assertEquals("stored", text(project, "a.glsl"));
	}

	@Test
	public void keepsTheProjectFilesWhenOpening() throws IOException {
		write("main.frag", SHADER);
		byte[] before = Files.readAllBytes(new File(root, "main.frag")
				.toPath());

		OpenProject project = open();
		document(project, "main.frag");
		project.persist();
		refresh(project);

		assertArrayEquals(before, Files.readAllBytes(
				new File(root, "main.frag").toPath()));
		assertEquals(Set.of("main.frag"), Set.of(root.list()));
	}

	private OpenProject editedElsewhere() throws IOException {
		write("a.glsl", "stored");
		OpenProject project = open();
		edit(project, document(project, "a.glsl"), "mine");
		write("a.glsl", "theirs");
		refresh(project);
		assertEquals(Set.of(path("a.glsl")), project.conflicts().keySet());
		assertEquals("mine", project.document(path("a.glsl")).text()
				.toString());
		return project;
	}

	private OpenProject open() {
		return open(new DirectoryProjectFiles(root));
	}

	private OpenProject open(ProjectFiles store) {
		ProjectSessions sessions = new ProjectSessions(sessionsDir);
		sessions.disk = disk;
		return OpenProject.open(id, store, null, null, sessions, worker,
				main, steps.timers, budget);
	}

	// The files of the journals, which a test makes fail.
	private final FaultyDisk disk = new FaultyDisk();

	private LiveProjects live() {
		return new LiveProjects(new ProjectSessions(sessionsDir), worker,
				main, steps.timers, budget);
	}

	// Return what the documents in memory besides the one shown take.
	private static long used(OpenProject project) {
		long used = 0;
		for (String name : List.of("0", "1", "2", "3", "4", "a", "b")) {
			ProjectPath path = path(name + ".glsl");
			Document document = project.document(path);
			if (document != null && !path.equals(project.shown())) {
				used += OpenProject.cost(document);
			}
		}
		return used;
	}

	private OpenProject open(LiveProjects live, LiveProjects.Opener opener) {
		OpenProject[] opened = {null};
		live.open(id, opener, project -> opened[0] = project);
		settle();
		assertNotNull(opened[0]);
		return opened[0];
	}

	private SafProjects.Opened folder(Locator locator) {
		return new SafProjects.Opened(new DirectoryProjectFiles(
				new File(locator.value())), null, null);
	}

	private void settle() {
		steps.settle();
	}

	@SuppressWarnings("unchecked")
	private <T> T await(Consumer<Callback<T>> call) {
		Object[] result = {NOTHING};
		call.accept(value -> result[0] = value);
		settle();
		assertNotSame(NOTHING, result[0]);
		return (T) result[0];
	}

	private String load(OpenProject project, String path) {
		return await(done -> project.load(path(path), ProjectFiles.UNKNOWN,
				done));
	}

	private Document document(OpenProject project, String path) {
		assertNull(load(project, path));
		return project.document(path(path));
	}

	private Document document(OpenProject project, String path, String text)
			throws IOException {
		write(path, text);
		return document(project, path);
	}

	private boolean canRun(OpenProject project, String path) {
		document(project, path);
		project.runs(path(path));
		settle();
		return project.runs(path(path)) == OpenProject.Runs.YES;
	}

	private String text(OpenProject project, String path) {
		return document(project, path).text().toString();
	}

	private static void edit(OpenProject project, String path, String text) {
		edit(project, project.document(path(path)), text);
	}

	private static void edit(OpenProject project, Document document,
			String text) {
		document.replace(0, document.text().length(), Text.of(text));
	}

	private Map<ProjectPath, Outcome> save(OpenProject project,
			boolean explicit) {
		return await(done -> project.save(explicit, done));
	}

	private void flush(OpenProject project) {
		boolean[] flushed = {false};
		project.flush(() -> flushed[0] = true);
		settle();
		assertTrue(flushed[0]);
	}

	// Load path and show it when it is there, as a screen does, so it
	// stays whatever the budget.
	private Document shown(OpenProject project, String path) {
		ProjectPath file = path(path);
		project.load(file, ProjectFiles.UNKNOWN, problem -> {
			assertNull(problem);
			project.show(file);
		});
		settle();
		return project.document(file);
	}

	private Document shown(OpenProject project, String path, String text)
			throws IOException {
		write(path, text);
		return shown(project, path);
	}

	private String reload(OpenProject project, ProjectPath path) {
		return await(done -> project.reload(path, done));
	}

	// Put a file where the folder of the histories would be, so that
	// none can be written.
	private void noHistories() throws IOException {
		File history = new File(new File(sessionsDir, id), "history");
		assertTrue(delete(history));
		Files.write(history.toPath(), new byte[]{1});
	}

	private static boolean delete(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				delete(child);
			}
		}
		return !file.exists() || file.delete();
	}

	// Return the files the journal keeps unsaved text of.
	private Set<ProjectPath> kept() {
		return new ProjectSessions(sessionsDir).unsaved(id);
	}

	// Flip a bit of the journal of a.glsl, at offset from its start, or
	// from its end when negative, and return its file.
	private File damage(int offset) throws IOException {
		File file = new ProjectSessions(sessionsDir).journal(id)
				.file(path("a.glsl"));
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[offset < 0 ? bytes.length + offset : offset] ^= 1;
		Files.write(file.toPath(), bytes);
		return file;
	}

	// Return the text the journal keeps for path, or null.
	private String journaled(String path) {
		Journal.Recovered recovered = new ProjectSessions(sessionsDir)
				.journal(id).read(path(path));
		return recovered != null ? recovered.text().toString() : null;
	}

	private Set<ProjectPath> refresh(OpenProject project) {
		return await(project::refresh);
	}

	private Preparation prepare(OpenProject project) {
		return build(project).preparation();
	}

	private OpenProject.Build build(OpenProject project) {
		return await(done -> project.prepare(BuiltinNames.ALL, done));
	}

	private static String built(Preparation preparation, String path) {
		return new String(preparation.files().get(path(path)).bytes(),
				StandardCharsets.UTF_8);
	}

	// The file that runs, as the last build used it.
	private static OpenProject.Used runs(String file) {
		return new OpenProject.Used(path(file), false,
				new FileRole(FileRole.Kind.RUNS, List.of()));
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

	// Run the worker, main and what the listener was told until all
	// are idle.
	private void settle(List<Runnable> posted) {
		do {
			settle();
		} while (drain(posted) > 0);
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
