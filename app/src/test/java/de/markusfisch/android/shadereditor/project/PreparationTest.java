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
import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;
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
				.put("main.frag", lines(
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
				.put("noise.png", Pictures.png(1, 2));
		Preparation preparation = prepare(files);
		assertTrue(preparation.diagnostics().isEmpty());
		RenderPlan plan = preparation.plan();
		assertEquals(List.of("pass/d.glsl", "pass/b.glsl", "pass/c.glsl",
				"main.frag"), programFiles(plan));
		assertEquals(3, plan.output());
		assertTrue(plan.reset().isEmpty());

		Pass main = plan.passes().get(3);
		assertEquals(3, main.program());
		assertEquals(List.of(
						new RenderPlan.Input("c", TextureKind.FLAT, Source.pass(2),
								Frame.CURRENT, NEAREST, path("main.frag"), 2),
						new RenderPlan.Input("b", TextureKind.FLAT, Source.pass(1),
								Frame.CURRENT, new Sampling(Filter.LINEAR,
								Filter.LINEAR, Mipmap.NONE, Wrap.CLAMP,
								Wrap.CLAMP), path("main.frag"), 4),
						new RenderPlan.Input("noise", TextureKind.FLAT,
								Source.image(path("noise.png")), Frame.CURRENT,
								new Sampling(Filter.LINEAR, Filter.LINEAR,
										Mipmap.NONE, Wrap.REPEAT, Wrap.REPEAT),
								path("main.frag"), 6)),
				main.inputs());
		assertEquals(new RenderPlan.Target(0.5, 0, 0, Format.RGBA8,
				Filter.LINEAR, path("main.frag"), 8), main.target());
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
		assertEquals(1, program.head());
		assertTrue(program.text().startsWith("#version 300 es\n\n" +
				"uniform sampler2D c;\n\n"));
		assertEquals(9, program.sourceMap().locate(9).line());
		assertEquals(List.of("shader-project.json", "main.frag",
				"pass/c.glsl", "pass/d.glsl", "pass/b.glsl", "noise.png"),
				dependencyPaths(preparation));
		assertNull(preparation.dependencies().get(0).revision());
		assertEquals(6, files.reads);
	}

	@Test
	public void plansHistoryAndPreviousReads() {
		RenderPlan plan = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se input \"state.glsl\" frame=previous",
						"uniform sampler2D state;",
						MAIN))
				.put("state.glsl", lines(
						"#pragma se input mag=linear",
						"uniform sampler2D backbuffer;",
						MAIN))).plan();
		assertNotNull(plan);
		assertEquals(List.of("main.frag", "state.glsl"), programFiles(plan));
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
				.put("main.frag", lines(
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
				.put("main.frag", lines(
						"#pragma se input wrap=repeat",
						"uniform samplerExternalOES cameraFront;",
						MAIN)));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR main.frag:1 Camera images can only be " +
				"read with wrap=clamp, remove wrap="), messages(preparation));
	}

	@Test
	public void plansInitialSourcesInResetOrder() {
		RenderPlan plan = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
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
				.put("seed.png", Pictures.png(1))).plan();
		assertNotNull(plan);
		assertEquals(List.of("p.glsl", "i.glsl", "main.frag"),
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
				.put("main.frag", MAIN)
				.put("shader-project.json", "{\"projectVersion\": 1}"));
		assertNotNull(preparation.plan());
		assertTrue(preparation.diagnostics().isEmpty());
		assertEquals(List.of(
						new SourceAssembly.Dependency(ProjectManifest.FILE, "r1"),
						new SourceAssembly.Dependency(path("main.frag"), "r0")),
				preparation.dependencies());
	}

	@Test
	public void continuedMacroLineIncludesNothing() {
		String main = lines("#define A \\", "#include \"lib.glsl\"", MAIN);
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", main)
				.put("lib.glsl", "float f;\n"));
		assertTrue(preparation.diagnostics().isEmpty());
		assertEquals(List.of("shader-project.json", "main.frag"),
				dependencyPaths(preparation));
		assertEquals(main, preparation.plan().programs().get(0).text());
	}

	@Test
	public void continuedLineCommentHidesHostDirective() {
		String main = lines("// c \\", "#pragma se input \"a.png\"",
				"uniform sampler2D a;", MAIN);
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", main)
				.put("a.png", Pictures.png(1)));
		assertTrue(preparation.diagnostics().isEmpty());
		assertTrue(preparation.plan().passes().get(0).inputs().isEmpty());
		assertEquals(main, preparation.plan().programs().get(0).text());
	}

	@Test
	public void planSaysHowATextureIsReadNotHowItIsDeclared() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se input \"sky.png\"", "uniform samplerCube sky;",
						"#pragma se input filter=linear",
						"uniform samplerExternalOES cameraBack;", MAIN))
				.put("sky.png", Pictures.png(1)));
		assertTrue(preparation.diagnostics().isEmpty());
		List<RenderPlan.Input> inputs =
				preparation.plan().passes().get(0).inputs();
		assertEquals(TextureKind.CUBE, inputs.get(0).texture());
		assertEquals(TextureKind.EXTERNAL, inputs.get(1).texture());
	}

	@Test
	public void quoteOnContinuedDirectiveLineOpensNoComment() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines("#define S \\", "\"a/*b\"",
						"#pragma se input \"a.png\"", "uniform sampler2D a;",
						MAIN))
				.put("a.png", Pictures.png(1)));
		assertTrue(preparation.diagnostics().isEmpty());
		assertEquals(List.of(new RenderPlan.Input("a", TextureKind.FLAT,
						Source.image(path("a.png")), Frame.CURRENT,
						new Sampling(Filter.LINEAR, Filter.LINEAR, Mipmap.NONE,
								Wrap.CLAMP, Wrap.CLAMP), path("main.frag"), 3)),
				preparation.plan().passes().get(0).inputs());
	}

	@Test
	public void invalidManifestIsAnErrorButDependenciesAreComplete() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se input \"a.png\"",
						"uniform sampler2D a;",
						MAIN))
				.put("a.png", Pictures.png(1))
				.put("shader-project.json",
						"{\n\"projectVersion\": 2\n}"));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR shader-project.json:2 Unsupported " +
				"projectVersion 2, this app reads version 1"),
				messages(preparation));
		assertEquals(List.of("shader-project.json", "main.frag", "a.png"),
				dependencyPaths(preparation));
	}

	@Test
	public void missingManifestEntryIsAnError() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", MAIN)
				.put("shader-project.json", "{\"entry\": \"missing.glsl\"}"));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR shader-project.json:0 Entry " +
				"missing.glsl in shader-project.json does not exist"),
				messages(preparation));
		assertEquals(List.of("shader-project.json", "main.frag",
				"missing.glsl"), dependencyPaths(preparation));
		assertNotNull(prepare(new MemoryProjectFiles()
				.put("main.frag", MAIN)
				.put("other.glsl", MAIN)
				.put("shader-project.json", "{\"entry\": \"other.glsl\"}"))
				.plan());
	}

	@Test
	public void versionControlFoldersAreNotDependencies() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines("#include \"sub/../.git/config\"",
						MAIN));
		Preparation preparation = prepare(files);
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR main.frag:1 Path is inside a " +
				"version-control folder: sub/../.git/config"),
				messages(preparation));
		assertEquals(List.of(ProjectManifest.FILE, path("main.frag")),
				files.readPaths);
	}

	@Test
	public void invalidDirectivesKeepTheirPathsAsDependencies() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se input \"a.png\" filter=garbage",
						"uniform sampler2D a;",
						"#pragma se input \"b.png\" wrap=clamp wrap=clamp",
						"uniform sampler2D b;",
						"#pragma se input \"c.glsl\"",
						"float notADeclaration;",
						"#pragma se target bogus=1 initial=\"seed.png\"",
						MAIN))
				.put("a.png", Pictures.png(1))
				.put("b.png", Pictures.png(1))
				.put("c.glsl", lines("#include \"lib.glsl\"", MAIN))
				.put("lib.glsl", "#pragma se input \"d.png\"\n")
				.put("d.png", Pictures.png(1))
				.put("seed.png", Pictures.png(1)));
		assertNull(preparation.plan());
		assertEquals(5, preparation.diagnostics().size());
		assertEquals(List.of("shader-project.json", "main.frag", "a.png",
				"b.png", "c.glsl", "lib.glsl", "d.png", "seed.png"),
				dependencyPaths(preparation));
	}

	@Test
	public void manifestThatIsNotTextIsAnError() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", MAIN)
				.put("shader-project.json", new byte[]{(byte) 0xc3}));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR shader-project.json:0 " +
				"shader-project.json is not UTF-8 text"), messages(preparation));
	}

	@Test
	public void manifestBelowTheRootIsAnOrdinaryFile() {
		Preparation preparation = Preparation.prepare(new MemoryProjectFiles()
						.put("sub/main.frag", MAIN)
						.put("sub/shader-project.json", "nonsense"),
				path("sub/main.frag"), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNotNull(preparation.plan());
		assertEquals(List.of("shader-project.json", "sub/main.frag"),
				dependencyPaths(preparation));
	}

	@Test
	public void cycleKeepsEveryDependency() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se input \"a.glsl\"",
						"uniform sampler2D a;",
						MAIN))
				.put("a.glsl", lines(
						"#include \"lib.glsl\"",
						"#pragma se input \"main.frag\"",
						"uniform sampler2D m;",
						"#pragma se input \"a.png\"",
						"uniform sampler2D img;",
						MAIN))
				.put("lib.glsl", "float f;\n")
				.put("a.png", Pictures.png(1)));
		assertNull(preparation.plan());
		assertEquals(1, preparation.diagnostics().size());
		assertEquals(List.of("shader-project.json", "main.frag", "a.glsl",
				"lib.glsl", "a.png"), dependencyPaths(preparation));
	}

	@Test
	public void sharedIncludeIsReadOnce() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(
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
	public void aFileNamedAsAnotherLanguageIsNotRunAsGlsl() {
		String refused = " is named as a shader in another language " +
				"than GLSL, which ShaderEditor does not run; rename it if " +
				"it is GLSL";
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.wgsl", MAIN + "\n")
				.put("reads.frag", lines("#pragma se input \"fx/blur.wgsl\"",
						"uniform sampler2D blur;", MAIN))
				.put("fx/blur.wgsl", MAIN + "\n")
				.put("includes.frag", lines("#include \"lib.agsl\"", MAIN))
				.put("lib.agsl", "float one() { return 1.0; }\n")
				.put("picture.frag", lines("#pragma se input \"noise.wgsl\"",
						"uniform sampler2D noise;", MAIN))
				.put("noise.wgsl", Pictures.png(1));

		assertEquals(List.of("ERROR main.wgsl:0" + refused),
				messages(prepare(files, "main.wgsl")));
		assertEquals(List.of("ERROR reads.frag:1 fx/blur.wgsl" + refused),
				messages(prepare(files, "reads.frag")));
		Preparation including = prepare(files, "includes.frag");
		assertEquals(List.of("ERROR includes.frag:1 lib.agsl" + refused),
				messages(including));
		// Still what the shader is made of, for sharing.
		assertEquals(2, including.uses().size());
		assertEquals("lib.agsl",
				including.uses().get(1).path().toString());
		// Kind comes first: a picture is a texture whatever its name.
		assertNotNull(prepare(files, "picture.frag").plan());
	}

	@Test
	public void entryMustBeTextWithAMainWhateverItIsCalled() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("shader.frag", Pictures.png(1))
				.put("data.frag", new byte[]{(byte) 0xff, (byte) 0xfe})
				.put("lib.frag", "float one() { return 1.0; }\n")
				.put("picture.png", MAIN + "\n")
				.put("notes", MAIN + "\n");

		assertEquals(List.of("ERROR shader.frag:0 is a picture, not " +
				"a shader"), messages(prepare(files,
				"shader.frag")));
		assertEquals(List.of("ERROR data.frag:0 is not text"),
				messages(prepare(files, "data.frag")));
		assertEquals(List.of("ERROR lib.frag:0 defines no main, so it " +
				"cannot run"), messages(prepare(files, "lib.frag")));
		// The name says nothing: these two run.
		assertNotNull(prepare(files, "picture.png").plan());
		assertNotNull(prepare(files, "notes").plan());
		assertEquals(List.of("shader-project.json", "notes"),
				dependencyPaths(prepare(files, "notes")));
	}

	@Test
	public void whatAnInputNamesIsToldFromItsBytes() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						input("texture.frag", "a"),
						input("pass.png", "b"),
						"#pragma se target initial=\"seed\"",
						MAIN))
				// A picture named like a shader, a shader named like a
				// picture, and a picture without an ending.
				.put("texture.frag", Pictures.png(1))
				.put("pass.png", MAIN + "\n")
				.put("seed", Pictures.jpeg()));

		RenderPlan plan = preparation.plan();
		assertNotNull(messages(preparation).toString(), plan);
		assertEquals(List.of("pass.png", "main.frag"), programFiles(plan));
		Pass main = plan.passes().get(1);
		assertEquals(Source.image(path("texture.frag")),
				main.inputs().get(0).source());
		assertEquals(Sampling.IMAGE, main.inputs().get(0).sampling());
		assertEquals(Source.pass(0), main.inputs().get(1).source());
		assertEquals(NEAREST, main.inputs().get(1).sampling());
		assertEquals(Source.image(path("seed")), main.initial());
		assertEquals(List.of("shader-project.json", "main.frag",
				"texture.frag", "pass.png", "seed"),
				dependencyPaths(preparation));
	}

	@Test
	public void anInputThatIsNeitherPictureNorTextIsAnErrorOnItsLine() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(input("data.bin", "a"), MAIN))
				.put("data.bin", new byte[]{(byte) 0xff, (byte) 0xfe, 0}));

		assertNull(preparation.plan());
		assertEquals(List.of("ERROR main.frag:1 data.bin is neither a " +
				"picture (PNG, JPEG) nor text"), messages(preparation));
	}

	@Test
	public void anInputNamingTextWithoutMainIsAnErrorThatNamesTheFile() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(input("lib/noise.glsl", "a"), MAIN))
				.put("lib/noise.glsl", "float noise(vec2 p) { return 0.0; }\n");

		assertEquals(List.of("ERROR main.frag:1 lib/noise.glsl defines no " +
				"main, so it cannot be an input; include it instead"),
				messages(prepare(files)));

		// Text that mentions main is the compiler's to judge.
		files.put("lib/noise.glsl", "#define ENTRY main\nvoid ENTRY() {}\n");
		assertNotNull(prepare(files).plan());
	}

	@Test
	public void optionsAreCheckedAgainstWhatTheFileIs() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se input \"picture.frag\" frame=previous",
						"uniform sampler2D a;",
						"#pragma se input \"pass.png\" mipmap=linear",
						"uniform sampler2D b;",
						"#pragma se input \"pass.png\"",
						"uniform samplerCube c;",
						MAIN))
				.put("picture.frag", Pictures.png())
				.put("pass.png", MAIN + "\n"));

		assertEquals(List.of(
				"ERROR main.frag:1 frame= applies only to a pass, " +
						"picture.frag is a picture; remove it",
				"ERROR main.frag:3 mipmap= applies only to a picture, " +
						"pass.png is a pass and has no mipmaps",
				"ERROR main.frag:5 A pass is read as uniform sampler2D c, " +
						"not samplerCube"), messages(preparation));
	}

	@Test
	public void blockCommentEndingOnConsumedLineIsAnError() {
		Preparation preparation = prepare(new MemoryProjectFiles()
				.put("main.frag", lines(
						"/* hello",
						"*/ #pragma se input \"a.png\"",
						"uniform sampler2D tex;",
						MAIN))
				.put("a.png", Pictures.png(1)));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR main.frag:2 Block comments are not " +
				"allowed on a line with a host directive, use a trailing " +
				"// comment"), messages(preparation));
	}

	@Test
	public void failedReadIsAnErrorWithItsReason() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(
						"#include \"lib.glsl\"",
						"#pragma se input \"pass.glsl\"",
						"uniform sampler2D pass;",
						"#pragma se input \"noise.png\"",
						"uniform sampler2D noise;",
						MAIN))
				.put("lib.glsl", "float x;\n")
				.put("pass.glsl", MAIN)
				.put("noise.png", Pictures.png(1));
		ProjectFiles failing = path -> path.equals(ProjectManifest.FILE) ||
				path.getFileName().equals("main.frag")
				? files.read(path)
				: new ProjectFiles.Failed("permission lost");
		Preparation preparation = Preparation.prepare(failing,
				path("main.frag"), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNull(preparation.plan());
		assertEquals(List.of(
				"ERROR main.frag:1 Could not read lib.glsl: permission lost",
				"ERROR main.frag:2 Could not read pass.glsl: permission lost",
				"ERROR main.frag:4 Could not read noise.png: permission lost"),
				messages(preparation));
		assertEquals(List.of("shader-project.json", "main.frag", "lib.glsl",
				"pass.glsl", "noise.png"), dependencyPaths(preparation));
		assertEquals(List.of("ERROR shader-project.json:0 Could not read " +
						"shader-project.json: permission lost",
				"ERROR main.frag:0 Could not read main.frag: permission lost"),
				messages(Preparation.prepare(path -> new ProjectFiles.Failed(
						"permission lost"), path("main.frag"),
						ProjectLimits.DEFAULT, BuiltinNames.ALL)));
	}

	@Test
	public void keepsCopiesOfTheBytesItRead() {
		byte[] image = Pictures.png(1, 2, 3);
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(input("a.png", "a"), MAIN))
				.put("a.png", image);
		Preparation preparation = prepare(files);
		assertNotNull(preparation.plan());
		image[0] = 9;
		assertEquals(List.of(path("main.frag"), path("a.png")),
				new ArrayList<>(preparation.files().keySet()));
		ProjectFiles.Content kept = preparation.files().get(path("a.png"));
		assertArrayEquals(Pictures.png(1, 2, 3), kept.bytes());
		assertEquals("r1", kept.revision());
		assertEquals(1, files.readPaths.stream()
				.filter(path("a.png")::equals).count());
	}

	@Test
	public void rejectsMoreFilesThanTheLimit() {
		Preparation preparation = Preparation.prepare(new MemoryProjectFiles()
						.put("main.frag", lines(input("a.glsl", "a"), MAIN))
						.put("a.glsl", lines(input("b.glsl", "b"), MAIN))
						.put("b.glsl", MAIN),
				path("main.frag"), new ProjectLimits(16, 1000, 100, 3, 16, 100),
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
				.put("main.frag", main + MAIN);
		for (int i = 0; i < 6; ++i) {
			files.put("a" + i + ".png", Pictures.png(1));
		}
		Preparation preparation = Preparation.prepare(files,
				path("main.frag"), new ProjectLimits(16, 10000, 100, 2, 16,
						100), BuiltinNames.ALL);
		assertNull(preparation.plan());
		assertEquals(List.of(ProjectManifest.FILE, path("main.frag")),
				files.readPaths);
		assertEquals(6, preparation.diagnostics().size());
		assertEquals("ERROR main.frag:1 Could not read a0.png: the build " +
				"reads more than the limit of 2 files",
				messages(preparation).get(0));
	}

	@Test
	public void zeroLimitsAreErrorsNotCrashes() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines("#include \"lib.glsl\"",
						input("a.png", "a"), MAIN))
				.put("lib.glsl", "float x;\n")
				.put("a.png", Pictures.png(1));
		List<ProjectLimits> zeros = List.of(
				new ProjectLimits(0, 1000, 100, 64, 16, 100),
				new ProjectLimits(16, 0, 100, 64, 16, 100),
				new ProjectLimits(16, 1000, 0, 64, 16, 100),
				new ProjectLimits(16, 1000, 100, 0, 16, 100),
				new ProjectLimits(16, 1000, 100, 64, 0, 100));
		for (ProjectLimits limits : zeros) {
			Preparation preparation = Preparation.prepare(files,
					path("main.frag"), limits, BuiltinNames.ALL);
			assertNull(limits.toString(), preparation.plan());
			assertTrue(messages(preparation).toString(),
					messages(preparation).get(0).contains("limit of 0"));
		}
		assertNotNull(Preparation.prepare(files, path("main.frag"),
				new ProjectLimits(16, 1000, 100, 64, 16, 0),
				BuiltinNames.ALL).plan());
	}

	@Test
	public void plansTheSameFromADirectory() throws IOException {
		Map<String, byte[]> project = new LinkedHashMap<>();
		project.put("shader-project.json", bytes(
				"{\"projectVersion\": 1, \"entry\": \"main.frag\"}"));
		project.put("main.frag", bytes(lines(
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
		project.put("textures/caf\u00e9.png", Pictures.png(0, 1, 2));
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
		assertEquals(List.of("shader-project.json", "main.frag",
				"lib/common.glsl", "passes/blur.glsl",
				"textures/caf\u00e9.png"), dependencyPaths(actual));
		assertEquals(expected.files().keySet(), actual.files().keySet());
		for (ProjectPath path : expected.files().keySet()) {
			assertArrayEquals(expected.files().get(path).bytes(),
					actual.files().get(path).bytes());
		}
	}

	private static Preparation prepare(ProjectFiles files, String entry) {
		return Preparation.prepare(files, path(entry),
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
	}

	private static Preparation prepare(ProjectFiles files) {
		return Preparation.prepare(files, path("main.frag"),
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
