package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
				path("sub/main.glsl"), ProjectLimits.DEFAULT);
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
				path("a.png"), ProjectLimits.DEFAULT);
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR a.png:0 The running shader must be a " +
				".glsl file: a.png"), messages(preparation));
		assertEquals(List.of("shader-project.json"),
				dependencyPaths(preparation));
	}

	@Test
	public void rejectsMoreFilesThanTheLimit() {
		Preparation preparation = Preparation.prepare(new MemoryProjectFiles()
						.put("main.glsl", lines(input("a.glsl", "a"), MAIN))
						.put("a.glsl", lines(input("b.glsl", "b"), MAIN))
						.put("b.glsl", MAIN),
				path("main.glsl"), new ProjectLimits(16, 1000, 3, 16));
		assertNull(preparation.plan());
		assertEquals(List.of("ERROR null:0 Build reads more than the limit " +
				"of 3 files"), messages(preparation));
	}

	private static Preparation prepare(MemoryProjectFiles files) {
		return Preparation.prepare(files, path("main.glsl"),
				ProjectLimits.DEFAULT);
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
