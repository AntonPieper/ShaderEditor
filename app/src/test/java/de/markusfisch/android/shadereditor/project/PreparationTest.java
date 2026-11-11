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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.RenderPlan.Pass;
import de.markusfisch.android.shadereditor.project.RenderPlan.Program;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

public class PreparationTest {
	private static final String MAIN = "void main() {}";
	private static final Sampling NEAREST = new Sampling(Filter.NEAREST,
			Filter.NEAREST, Mipmap.NONE, Wrap.CLAMP, Wrap.CLAMP);

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void plansDiamondWithResolvedInputs() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#version 300 es",
						"#pragma se input \"pass/c.glsl\"",
						"uniform sampler2D c;",
						"#pragma se input \"pass/b.glsl\" filter=linear",
						"uniform sampler2D b;",
						"#pragma se input \"noise.png\" wrap=repeat",
						"uniform sampler2D noise;",
						"#pragma se target scale=0.5 present=linear",
						MAIN))
				.put("pass/b.glsl", lines(input("d.glsl", "d"), MAIN))
				.put("pass/c.glsl", lines(input("d.glsl", "d"), MAIN))
				.put("pass/d.glsl", MAIN)
				.put("noise.png", new byte[]{1, 2});
		Preparation preparation = prepare(files);
		assertTrue(preparation.diagnostics().isEmpty());
		RenderPlan plan = preparation.plan();
		assertEquals(List.of("pass/d.glsl", "pass/b.glsl", "pass/c.glsl",
				"main.glsl"), programFiles(plan));
		assertEquals(3, plan.output());
		assertTrue(plan.reset().isEmpty());

		Pass main = plan.passes().get(3);
		assertEquals(3, main.program());
		assertEquals(List.of(
						new RenderPlan.Input("c", "sampler2D", Source.pass(2),
								Frame.CURRENT, NEAREST, path("main.glsl"), 2),
						new RenderPlan.Input("b", "sampler2D", Source.pass(1),
								Frame.CURRENT, new Sampling(Filter.LINEAR,
								Filter.LINEAR, Mipmap.NONE, Wrap.CLAMP,
								Wrap.CLAMP), path("main.glsl"), 4),
						new RenderPlan.Input("noise", "sampler2D",
								Source.image(path("noise.png")), Frame.CURRENT,
								new Sampling(Filter.LINEAR, Filter.LINEAR,
										Mipmap.NONE, Wrap.REPEAT, Wrap.REPEAT),
								path("main.glsl"), 6)),
				main.inputs());
		assertEquals(new RenderPlan.Target(0.5, 0, 0, Format.RGBA8,
				Filter.LINEAR, path("main.glsl"), 8), main.target());
		assertEquals(Source.pass(0),
				plan.passes().get(1).inputs().get(0).source());
		assertEquals(Source.pass(0),
				plan.passes().get(2).inputs().get(0).source());
		assertEquals(new RenderPlan.Target(1, 0, 0, Format.RGBA8,
				Filter.NEAREST, path("pass/d.glsl"), 0),
				plan.passes().get(0).target());
		for (Pass pass : plan.passes()) {
			assertTrue(pass.perFrame());
			assertFalse(pass.history());
			assertNull(pass.initial());
		}

		Program program = plan.programs().get(3);
		assertEquals(1, program.versionLine());
		assertTrue(program.text().startsWith("#version 300 es\n\n" +
				"uniform sampler2D c;\n\n"));
		assertEquals(9, program.sourceMap().locate(9).line());
		assertEquals(List.of("shader-project.json", "main.glsl",
				"pass/c.glsl", "pass/d.glsl", "pass/b.glsl", "noise.png"),
				dependencyPaths(preparation));
		assertNull(preparation.dependencies().get(0).revision());
		assertEquals(6, files.reads);
	}

	@Test
	public void plansHistoryAndPreviousReads() {
		RenderPlan plan = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#pragma se input \"state.glsl\" frame=previous",
						"uniform sampler2D state;",
						MAIN))
				.put("state.glsl", lines(
						"#pragma se input mag=linear",
						"uniform sampler2D backbuffer;",
						MAIN))).plan();
		assertNotNull(plan);
		assertEquals(List.of("main.glsl", "state.glsl"), programFiles(plan));
		RenderPlan.Input state = plan.passes().get(0).inputs().get(0);
		assertEquals(Source.pass(1), state.source());
		assertEquals(Frame.PREVIOUS, state.frame());
		RenderPlan.Input backbuffer = plan.passes().get(1).inputs().get(0);
		assertEquals(Source.pass(1), backbuffer.source());
		assertEquals(Frame.PREVIOUS, backbuffer.frame());
		assertEquals(new Sampling(Filter.NEAREST, Filter.LINEAR, Mipmap.NONE,
				Wrap.CLAMP, Wrap.CLAMP), backbuffer.sampling());
		assertFalse(plan.passes().get(0).history());
		assertTrue(plan.passes().get(1).history());
	}

	@Test
	public void plansCameraAsEngineSampler() {
		RenderPlan plan = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#pragma se input filter=linear",
						"uniform samplerExternalOES cameraBack;",
						MAIN))).plan();
		assertNotNull(plan);
		RenderPlan.Input camera = plan.passes().get(0).inputs().get(0);
		assertEquals(Source.ENGINE, camera.source());
		assertEquals(new Sampling(Filter.LINEAR, Filter.LINEAR, Mipmap.NONE,
				Wrap.CLAMP, Wrap.CLAMP), camera.sampling());
	}

	@Test
	public void cameraWrapIsAnErrorWithoutPlan() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#pragma se input wrap=repeat",
						"uniform samplerExternalOES cameraFront;",
						MAIN)));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR main.glsl:1 Camera images can only be " +
				"read with wrap=clamp, remove wrap="), messages(preparation));
	}

	@Test
	public void plansInitialSourcesInResetOrder() {
		RenderPlan plan = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#pragma se target initial=\"i.glsl\"",
						"uniform sampler2D backbuffer;",
						"#pragma se input \"p.glsl\"",
						"uniform sampler2D p;",
						MAIN))
				.put("i.glsl", lines(
						"#pragma se input \"p.glsl\"",
						"uniform sampler2D p;",
						MAIN))
				.put("p.glsl", lines(
						"#pragma se target initial=\"seed.png\"",
						"uniform sampler2D backbuffer;",
						MAIN))
				.put("seed.png", new byte[]{1})).plan();
		assertNotNull(plan);
		assertEquals(List.of("p.glsl", "i.glsl", "main.glsl"),
				programFiles(plan));
		assertEquals(List.of(0, 2), plan.reset());
		assertEquals(Source.image(path("seed.png")),
				plan.passes().get(0).initial());
		assertEquals(Source.pass(1), plan.passes().get(2).initial());
		assertFalse(plan.passes().get(1).perFrame());
		assertTrue(plan.passes().get(0).perFrame());
	}

	@Test
	public void readsValidManifestAsDependency() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", MAIN)
				.put("shader-project.json", "{\"projectVersion\": 1}"));
		assertNotNull(preparation.plan());
		assertTrue(preparation.diagnostics().isEmpty());
		assertEquals(List.of(
						new SourceAssembly.Dependency(ProjectManifest.FILE, "r1"),
						new SourceAssembly.Dependency(path("main.glsl"), "r0")),
				preparation.dependencies());
	}

	@Test
	public void continuedMacroLineIncludesNothing() {
		String main = lines("#define A \\", "#include \"lib.glsl\"", MAIN);
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", main)
				.put("lib.glsl", "float f;\n"));
		assertTrue(preparation.diagnostics().isEmpty());
		assertEquals(List.of("shader-project.json", "main.glsl"),
				dependencyPaths(preparation));
		assertEquals(main, preparation.plan().programs().get(0).text());
	}

	@Test
	public void continuedLineCommentHidesHostDirective() {
		String main = lines("// c \\", "#pragma se input \"a.png\"",
				"uniform sampler2D a;", MAIN);
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", main)
				.put("a.png", new byte[]{1}));
		assertTrue(preparation.diagnostics().isEmpty());
		assertTrue(preparation.plan().passes().get(0).inputs().isEmpty());
		assertEquals(main, preparation.plan().programs().get(0).text());
	}

	@Test
	public void quoteOnContinuedDirectiveLineOpensNoComment() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines("#define S \\", "\"a/*b\"",
						"#pragma se input \"a.png\"", "uniform sampler2D a;",
						MAIN))
				.put("a.png", new byte[]{1}));
		assertTrue(preparation.diagnostics().isEmpty());
		assertEquals(List.of(new RenderPlan.Input("a", "sampler2D",
						Source.image(path("a.png")), Frame.CURRENT,
						new Sampling(Filter.LINEAR, Filter.LINEAR, Mipmap.NONE,
								Wrap.CLAMP, Wrap.CLAMP), path("main.glsl"), 3)),
				preparation.plan().passes().get(0).inputs());
	}

	@Test
	public void invalidManifestIsAnErrorButDependenciesAreComplete() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#pragma se input \"a.png\"",
						"uniform sampler2D a;",
						MAIN))
				.put("a.png", new byte[]{1})
				.put("shader-project.json",
						"{\n\"projectVersion\": 2\n}"));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR shader-project.json:2 Unsupported " +
				"projectVersion 2, this app reads version 1"),
				messages(preparation));
		assertEquals(List.of("shader-project.json", "main.glsl", "a.png"),
				dependencyPaths(preparation));
	}

	@Test
	public void missingManifestEntryIsAnError() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", MAIN)
				.put("shader-project.json", "{\"entry\": \"missing.glsl\"}"));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR shader-project.json:0 Entry " +
				"missing.glsl in shader-project.json does not exist"),
				messages(preparation));
		assertEquals(List.of("shader-project.json", "main.glsl",
				"missing.glsl"), dependencyPaths(preparation));
		assertNotNull(prepare(new MemoryProjectFiles()
				.put("main.glsl", MAIN)
				.put("other.glsl", MAIN)
				.put("shader-project.json", "{\"entry\": \"other.glsl\"}"))
				.plan());
	}

	@Test
	public void versionControlFoldersAreNotDependencies() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", lines("#include \"sub/../.git/config\"",
						MAIN));
		Preparation preparation = prepare(files);
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR main.glsl:1 Path is inside a " +
				"version-control folder: sub/../.git/config"),
				messages(preparation));
		assertEquals(List.of(ProjectManifest.FILE, path("main.glsl")),
				files.readPaths);
	}

	@Test
	public void invalidDirectivesKeepTheirPathsAsDependencies() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#pragma se input \"a.png\" filter=garbage",
						"uniform sampler2D a;",
						"#pragma se input \"b.png\" wrap=clamp wrap=clamp",
						"uniform sampler2D b;",
						"#pragma se input \"c.glsl\"",
						"float notADeclaration;",
						"#pragma se target bogus=1 initial=\"seed.png\"",
						MAIN))
				.put("a.png", new byte[]{1})
				.put("b.png", new byte[]{1})
				.put("c.glsl", lines("#include \"lib.glsl\"", MAIN))
				.put("lib.glsl", "#pragma se input \"d.png\"\n")
				.put("d.png", new byte[]{1})
				.put("seed.png", new byte[]{1}));
		assertNull(preparation.plan());
		assertEquals(5, preparation.diagnostics().size());
		assertEquals(List.of("shader-project.json", "main.glsl", "a.png",
				"b.png", "c.glsl", "lib.glsl", "d.png", "seed.png"),
				dependencyPaths(preparation));
	}

	@Test
	public void manifestThatIsNotTextIsAnError() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", MAIN)
				.put("shader-project.json", new byte[]{(byte) 0xc3}));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR shader-project.json:0 " +
				"shader-project.json is not UTF-8 text"), messages(preparation));
	}

	@Test
	public void manifestBelowTheRootIsAnOrdinaryFile() {
		Preparation preparation = Preparation.prepare(new MemoryProjectFiles()
						.put("sub/main.glsl", MAIN)
						.put("sub/shader-project.json", "nonsense"),
				path("sub/main.glsl"), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNotNull(preparation.plan());
		assertEquals(List.of("shader-project.json", "sub/main.glsl"),
				dependencyPaths(preparation));
	}

	@Test
	public void cycleKeepsEveryDependency() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#pragma se input \"a.glsl\"",
						"uniform sampler2D a;",
						MAIN))
				.put("a.glsl", lines(
						"#include \"lib.glsl\"",
						"#pragma se input \"main.glsl\"",
						"uniform sampler2D m;",
						"#pragma se input \"a.png\"",
						"uniform sampler2D img;",
						MAIN))
				.put("lib.glsl", "float f;\n")
				.put("a.png", new byte[]{1}));
		assertNull(preparation.plan());
		assertEquals(1, preparation.diagnostics().size());
		assertEquals(List.of("shader-project.json", "main.glsl", "a.glsl",
				"lib.glsl", "a.png"), dependencyPaths(preparation));
	}

	@Test
	public void sharedIncludeIsReadOnce() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#include \"lib.glsl\"",
						"#pragma se input \"a.glsl\"",
						"uniform sampler2D a;",
						MAIN))
				.put("a.glsl", lines("#include \"lib.glsl\"", MAIN))
				.put("lib.glsl", "float f;\n");
		Preparation preparation = prepare(files);
		assertNotNull(preparation.plan());
		assertEquals(4, files.reads);
		assertEquals(4, preparation.dependencies().size());
	}

	@Test
	public void entryMustBeAShader() {
		Preparation preparation = Preparation.prepare(new MemoryProjectFiles()
						.put("a.png", new byte[]{1}),
				path("a.png"), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR a.png:0 The running shader must be a " +
				".glsl file: a.png"), messages(preparation));
		assertEquals(List.of("shader-project.json"),
				dependencyPaths(preparation));
	}

	@Test
	public void blockCommentEndingOnConsumedLineIsAnError() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"/* hello",
						"*/ #pragma se input \"a.png\"",
						"uniform sampler2D tex;",
						MAIN))
				.put("a.png", new byte[]{1}));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR main.glsl:2 Block comments are not " +
				"allowed on a line with a host directive, use a trailing " +
				"// comment"), messages(preparation));
	}

	@Test
	public void failedReadIsAnErrorWithItsReason() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#include \"lib.glsl\"",
						"#pragma se input \"pass.glsl\"",
						"uniform sampler2D pass;",
						"#pragma se input \"noise.png\"",
						"uniform sampler2D noise;",
						MAIN))
				.put("lib.glsl", "float x;\n")
				.put("pass.glsl", MAIN)
				.put("noise.png", new byte[]{1});
		ProjectFiles failing = path -> path.equals(ProjectManifest.FILE) ||
				path.getFileName().equals("main.glsl")
				? files.read(path)
				: new ProjectFiles.Failed("permission lost");
		Preparation preparation = Preparation.prepare(failing,
				path("main.glsl"), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNull(preparation.plan());
		assertEquals(List.of(
				"ERROR main.glsl:1 Could not read lib.glsl: permission lost",
				"ERROR main.glsl:2 Could not read pass.glsl: permission lost",
				"ERROR main.glsl:4 Could not read noise.png: permission lost"),
				messages(preparation));
		assertEquals(List.of("shader-project.json", "main.glsl", "lib.glsl",
				"pass.glsl", "noise.png"), dependencyPaths(preparation));
		assertEquals(List.of("ERROR shader-project.json:0 Could not read " +
						"shader-project.json: permission lost",
				"ERROR main.glsl:0 Could not read main.glsl: permission lost"),
				messages(Preparation.prepare(path -> new ProjectFiles.Failed(
						"permission lost"), path("main.glsl"),
						ProjectLimits.DEFAULT, BuiltinNames.ALL)));
	}

	@Test
	public void keepsCopiesOfTheBytesItRead() {
		byte[] image = {1, 2, 3};
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", lines(input("a.png", "a"), MAIN))
				.put("a.png", image);
		Preparation preparation = prepare(files);
		assertNotNull(preparation.plan());
		image[0] = 9;
		assertEquals(List.of(path("main.glsl"), path("a.png")),
				new ArrayList<>(preparation.files().keySet()));
		ProjectFiles.Content kept = preparation.files().get(path("a.png"));
		assertArrayEquals(new byte[]{1, 2, 3}, kept.bytes());
		assertEquals("r1", kept.revision());
		assertEquals(1, files.readPaths.stream()
				.filter(path("a.png")::equals).count());
	}

	@Test
	public void rejectsMoreFilesThanTheLimit() {
		Preparation preparation = Preparation.prepare(new MemoryProjectFiles()
						.put("main.glsl", lines(input("a.glsl", "a"), MAIN))
						.put("a.glsl", lines(input("b.glsl", "b"), MAIN))
						.put("b.glsl", MAIN),
				path("main.glsl"), new ProjectLimits(16, 1000, 100, 3, 16, 100),
				BuiltinNames.ALL);
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR a.glsl:1 Could not read b.glsl: the " +
				"build reads more than the limit of 3 files"),
				messages(preparation));
	}

	@Test
	public void fileLimitIsCheckedBeforeReading() {
		StringBuilder main = new StringBuilder();
		for (int i = 0; i < 6; ++i) {
			main.append(input("a" + i + ".png", "a" + i)).append('\n');
		}
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", main + MAIN);
		for (int i = 0; i < 6; ++i) {
			files.put("a" + i + ".png", new byte[]{1});
		}
		Preparation preparation = Preparation.prepare(files,
				path("main.glsl"), new ProjectLimits(16, 10000, 100, 2, 16,
						100), BuiltinNames.ALL);
		assertNull(preparation.plan());
		assertEquals(List.of(ProjectManifest.FILE, path("main.glsl")),
				files.readPaths);
		assertEquals(6, preparation.diagnostics().size());
		assertEquals("ERROR main.glsl:1 Could not read a0.png: the build " +
				"reads more than the limit of 2 files",
				messages(preparation).get(0));
	}

	@Test
	public void zeroLimitsAreErrorsNotCrashes() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", lines("#include \"lib.glsl\"",
						input("a.png", "a"), MAIN))
				.put("lib.glsl", "float x;\n")
				.put("a.png", new byte[]{1});
		List<ProjectLimits> zeros = List.of(
				new ProjectLimits(0, 1000, 100, 64, 16, 100),
				new ProjectLimits(16, 0, 100, 64, 16, 100),
				new ProjectLimits(16, 1000, 0, 64, 16, 100),
				new ProjectLimits(16, 1000, 100, 0, 16, 100),
				new ProjectLimits(16, 1000, 100, 64, 0, 100));
		for (ProjectLimits limits : zeros) {
			Preparation preparation = Preparation.prepare(files,
					path("main.glsl"), limits, BuiltinNames.ALL);
			assertNull(limits.toString(), preparation.plan());
			assertTrue(messages(preparation).toString(),
					messages(preparation).get(0).contains("limit of 0"));
		}
		assertNotNull(Preparation.prepare(files, path("main.glsl"),
				new ProjectLimits(16, 1000, 100, 64, 16, 0),
				BuiltinNames.ALL).plan());
	}

	@Test
	public void plansTheSameFromADirectory() throws IOException {
		Map<String, byte[]> project = new LinkedHashMap<>();
		project.put("shader-project.json", bytes(
				"{\"projectVersion\": 1, \"entry\": \"main.glsl\"}"));
		project.put("main.glsl", bytes(lines(
				"#include \"lib/common.glsl\"",
				"#pragma se input \"passes/blur.glsl\" filter=linear",
				"uniform sampler2D blur;",
				"#pragma se input \"textures/caf\u00e9.png\" wrap=repeat",
				"uniform sampler2D noise;",
				MAIN)));
		project.put("lib/common.glsl", bytes("float shared;\n"));
		project.put("passes/blur.glsl", bytes(lines(
				"#include \"../lib/common.glsl\"",
				input("../textures/caf\u00e9.png", "noise"),
				MAIN)));
		project.put("textures/caf\u00e9.png", new byte[]{(byte) 0x89, 'P',
				'N', 'G', 0, 1, 2});
		project.put("unrelated.txt", bytes("not read"));
		MemoryProjectFiles memory = new MemoryProjectFiles();
		for (Map.Entry<String, byte[]> file : project.entrySet()) {
			memory.put(file.getKey(), file.getValue());
			// Stored decomposed, as archives made on macOS do.
			File disk = new File(temp.getRoot(), file.getKey()
					.replace("\u00e9", "e\u0301"));
			File parent = disk.getParentFile();
			assertTrue(parent.isDirectory() || parent.mkdirs());
			Files.write(disk.toPath(), file.getValue());
		}

		Preparation expected = prepare(memory);
		Preparation actual = prepare(
				new DirectoryProjectFiles(temp.getRoot()));
		assertTrue(messages(expected).toString(),
				expected.diagnostics().isEmpty());
		assertNotNull(expected.plan());
		assertEquals(messages(expected), messages(actual));
		assertEquals(expected.plan(), actual.plan());
		assertEquals(dependencyPaths(expected), dependencyPaths(actual));
		assertEquals(List.of("shader-project.json", "main.glsl",
				"lib/common.glsl", "passes/blur.glsl",
				"textures/caf\u00e9.png"), dependencyPaths(actual));
		assertEquals(expected.files().keySet(), actual.files().keySet());
		for (ProjectPath path : expected.files().keySet()) {
			assertArrayEquals(expected.files().get(path).bytes(),
					actual.files().get(path).bytes());
		}
	}

	private static Preparation prepare(ProjectFiles files) {
		return Preparation.prepare(files, path("main.glsl"),
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String input(String source, String name) {
		return "#pragma se input \"" + source + "\"\nuniform sampler2D " +
				name + ";";
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}

	private static List<String> programFiles(RenderPlan plan) {
		List<String> files = new ArrayList<>();
		for (Pass pass : plan.passes()) {
			files.add(plan.programs().get(pass.program()).file().toString());
		}
		return files;
	}

	private static List<String> dependencyPaths(Preparation preparation) {
		List<String> paths = new ArrayList<>();
		for (SourceAssembly.Dependency dependency :
				preparation.dependencies()) {
			paths.add(dependency.path().toString());
		}
		return paths;
	}

	private static List<String> messages(Preparation preparation) {
		List<String> messages = new ArrayList<>();
		for (ProjectDiagnostic d : preparation.diagnostics()) {
			messages.add(d.severity() + " " + d.file() + ":" + d.line() +
					" " + d.message());
		}
		return messages;
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
