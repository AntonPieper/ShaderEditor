package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.shadertoy.Fixtures;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyProject;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader;

/**
 * What the import from ShaderToy makes is a project the engine's own
 * preparation takes, as it takes the bundled examples.
 */
public class ShaderToyImportTest {
	private static final ProjectPath MAIN = ProjectPath.parse(
			ShaderToyProject.ENTRY).value();

	@Test
	public void everyFixturePreparesWithoutADiagnostic() {
		for (String name : new String[]{
				"rings.json", "trail.json", "ripples.json", "media.json"}) {
			ShaderToyProject.Converted converted =
					ShaderToyProject.ofJson(Fixtures.text(name));
			assertNull(name, converted.refusal());
			Preparation preparation = prepare(converted);
			assertEquals(name, List.of(), preparation.diagnostics());
			assertNotNull(name, preparation.plan());
		}
	}

	// A buffer that reads itself is the shape of the bundled Ink: a
	// pass with history that starts from another pass, read by the
	// running shader in the same frame.
	@Test
	public void feedbackHasTheShapeOfTheInkExample() {
		RenderPlan ink = Preparation.prepare(
				new DirectoryProjectFiles(ExamplesTest.folder()),
				ProjectPath.parse("Ink/main.frag").value(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL).plan();
		RenderPlan trail = prepare(ShaderToyProject.ofJson(
				Fixtures.text("trail.json"))).plan();
		assertEquals(shape(ink), shape(trail));
		assertEquals(List.of(
				"bufferA.frag with history from empty.frag reads " +
						"[bufferA.frag PREVIOUS]",
				"empty.frag drawn at the start",
				"main.frag reads [bufferA.frag CURRENT]"),
				describe(trail));
		RenderPlan.Pass buffer = trail.passes().get(0);
		assertEquals(Format.RGBA16F, buffer.target().format());
		assertEquals(Filter.LINEAR,
				buffer.inputs().get(0).sampling().min());
	}

	// A reads itself and B, which is drawn after it: both of the frame
	// before. B reads A of this frame and itself of the frame before.
	// Image reads A twice, once with nearest and once with linear; that
	// one device cannot do it is the engine's to say when it builds.
	@Test
	public void twoBuffersThatReadEachOther() {
		RenderPlan plan = prepare(ShaderToyProject.ofJson(
				Fixtures.text("ripples.json"))).plan();
		assertEquals(List.of(
				"bufferA.frag with history from empty.frag reads " +
						"[bufferA.frag PREVIOUS, bufferB.frag PREVIOUS]",
				"bufferB.frag with history from empty.frag reads " +
						"[bufferA.frag CURRENT, bufferB.frag PREVIOUS]",
				"empty.frag drawn at the start",
				"main.frag reads [bufferA.frag CURRENT, " +
						"bufferA.frag CURRENT]"),
				describe(plan));
		List<RenderPlan.Input> inputs = plan.passes().get(3).inputs();
		assertEquals(Filter.NEAREST, inputs.get(0).sampling().min());
		assertEquals(Filter.LINEAR, inputs.get(1).sampling().min());
	}

	// What is not carried over reads black: no input stands for it, so
	// the project builds, and the engine warns of it once it has linked.
	// A buffer whose frame before nobody reads has no start: Buffer A.
	@Test
	public void whatIsMissingIsNoInput() {
		RenderPlan plan = prepare(ShaderToyProject.ofJson(
				Fixtures.text("media.json"))).plan();
		assertEquals(List.of(
				"bufferA.frag reads [bufferB.frag PREVIOUS]",
				"bufferB.frag with history from empty.frag reads " +
						"[bufferC.frag PREVIOUS]",
				"bufferC.frag with history from empty.frag reads []",
				"empty.frag drawn at the start",
				"main.frag reads [bufferA.frag CURRENT]"),
				describe(plan));
	}

	// Tabs given as code come without what their channels read: they
	// build and read black, until a prepared line is switched on.
	@Test
	public void tabsWithoutChannelsBuild() {
		Map<String, String> tabs = new LinkedHashMap<>();
		tabs.put("Image", "void mainImage(out vec4 c, in vec2 p) {\n" +
				"\tc = texture(iChannel0, p / iResolution.xy);\n}\n");
		tabs.put("Buffer A", "void mainImage(out vec4 c, in vec2 p) {\n" +
				"\tc = texture(iChannel0, p / iResolution.xy) + 0.01;\n}\n");
		ShaderToyProject.Converted converted = ShaderToyProject.of(
				ShaderToyShader.ofTabs("Tabs", tabs));
		Preparation preparation = prepare(converted);
		assertEquals(List.of(), preparation.diagnostics());
		assertEquals(List.of("main.frag reads []"),
				describe(preparation.plan()));
		// As the comment says: without the "//".
		Map<String, String> files = new LinkedHashMap<>(converted.files());
		files.replaceAll((path, text) -> text.replace(
				"// #pragma se input \"bufferA.frag\"",
				"#pragma se input \"bufferA.frag\""));
		preparation = prepare(files);
		assertEquals(List.of(), preparation.diagnostics());
		assertEquals(List.of(
				"bufferA.frag with history reads [bufferA.frag PREVIOUS]",
				"main.frag reads [bufferA.frag CURRENT]"),
				describe(preparation.plan()));
	}

	// A shader that calls something of its own "time" still compiles
	// against the app's uniform of that name: seen by the name being
	// moved out of the way in every pass that needs it.
	@Test
	public void aNameOfItsOwnThatIsAnAppUniform() {
		Map<String, String> tabs = new LinkedHashMap<>();
		tabs.put("Common", "float time;\n");
		tabs.put("Image", "void mainImage(out vec4 c, in vec2 p) {\n" +
				"\ttime = 0.5 * iTime;\n\tc = vec4(fract(time));\n}\n");
		ShaderToyProject.Converted converted = ShaderToyProject.of(
				ShaderToyShader.ofTabs(null, tabs));
		String main = converted.files().get("main.frag");
		assertTrue(main, main.indexOf("uniform float time;") <
				main.indexOf("#define time shaderToyOwn_time\n"));
		assertTrue(main, main.indexOf("#define time shaderToyOwn_time\n") <
				main.indexOf("#include \"common.glsl\""));
		assertEquals(List.of(), prepare(converted).diagnostics());
	}

	private static Preparation prepare(ShaderToyProject.Converted converted) {
		return prepare(converted.files());
	}

	private static Preparation prepare(Map<String, String> texts) {
		MemoryProjectFiles files = new MemoryProjectFiles();
		for (Map.Entry<String, String> file : texts.entrySet()) {
			files.put(file.getKey(), file.getValue());
		}
		return Preparation.prepare(files, MAIN, ProjectLimits.DEFAULT,
				BuiltinNames.ALL);
	}

	// Each pass in the order it is drawn, with what it reads.
	private static List<String> describe(RenderPlan plan) {
		List<String> passes = new ArrayList<>();
		for (RenderPlan.Pass pass : plan.passes()) {
			StringBuilder sb = new StringBuilder(file(plan, pass));
			if (!pass.perFrame()) {
				passes.add(sb.append(" drawn at the start").toString());
				continue;
			}
			if (pass.history()) {
				sb.append(" with history");
			}
			if (pass.initial() != null) {
				sb.append(" from ").append(file(plan,
						plan.passes().get(pass.initial().pass())));
			}
			List<String> inputs = new ArrayList<>();
			for (RenderPlan.Input input : pass.inputs()) {
				inputs.add(file(plan, plan.passes().get(
						input.source().pass())) + " " + input.frame());
			}
			passes.add(sb.append(" reads ").append(inputs).toString());
		}
		return passes;
	}

	// The same without the names of files and the order they give:
	// how many passes, which have history and a start, and which frame
	// each reads of itself and of others.
	private static List<String> shape(RenderPlan plan) {
		List<String> shape = new ArrayList<>();
		for (int i = 0; i < plan.passes().size(); ++i) {
			RenderPlan.Pass pass = plan.passes().get(i);
			List<String> inputs = new ArrayList<>();
			for (RenderPlan.Input input : pass.inputs()) {
				if (input.source().kind() == RenderPlan.Source.Kind.PASS) {
					inputs.add((input.source().pass() == i
							? "itself "
							: "another ") + input.frame());
				}
			}
			shape.add((pass.perFrame() ? "drawn " : "start ") +
					(pass.history() ? "history " : "") +
					(pass.initial() != null ? "started " : "") + inputs);
		}
		Collections.sort(shape);
		return shape;
	}

	private static String file(RenderPlan plan, RenderPlan.Pass pass) {
		return plan.programs().get(pass.program()).file().toString();
	}
}
