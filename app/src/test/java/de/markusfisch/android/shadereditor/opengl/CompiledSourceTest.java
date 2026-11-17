package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.BuiltinNames;
import de.markusfisch.android.shadereditor.project.DirectoryProjectFiles;
import de.markusfisch.android.shadereditor.project.ExamplesTest;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceText;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter;
import de.markusfisch.android.shadereditor.project.shadertoy.Fixtures;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyProject;
import de.markusfisch.android.shadereditor.syntax.BundledShaders;

/**
 * The text the engine gives the driver is made by CompiledSource since
 * that was moved out of PlanSource. It has to be, character for
 * character, the text PlanSource made before: here that making is kept
 * as it was written then, and compared for every program of the
 * bundled examples, of the ShaderToy fixtures and of the bundled
 * shaders of the database as they are converted, and for texts that
 * take each of its steps.
 */
public class CompiledSourceTest {
	private static final Pattern VERSION = Pattern.compile("\\d{1,9}");
	private static final Pattern EXTERNAL_SAMPLER = Pattern.compile(
			"\\bsamplerExternalOES\\b");

	@Test
	public void theTextIsWhatPlanSourceMadeBefore() throws IOException {
		int programs = 0;
		DirectoryProjectFiles examples = new DirectoryProjectFiles(
				ExamplesTest.folder());
		for (ProjectPath shader : ExamplesTest.shaders()) {
			programs += same(shader.toString(), Preparation.prepare(examples,
					shader, ProjectLimits.DEFAULT, BuiltinNames.ALL).plan());
		}
		assertTrue("examples: " + programs, programs > 20);
		int before = programs;
		for (String fixture : new String[]{"media.json", "pace.json",
				"rings.json", "ripples.json", "trail.json"}) {
			programs += same(fixture, plan(ShaderToyProject.ofJson(
					Fixtures.text(fixture)).files()));
		}
		assertTrue("ShaderToy: " + (programs - before),
				programs - before >= 5);
		before = programs;
		for (Map.Entry<String, String> shader :
				BundledShaders.read().entrySet()) {
			if (shader.getKey().startsWith("examples/")) {
				continue;
			}
			LegacyShaderConverter.Result converted =
					LegacyShaderConverter.convert(shader.getValue(),
							name -> null, Set.of());
			List<String> pathsAndTexts = new ArrayList<>();
			for (Map.Entry<ProjectPath, String> text :
					converted.files().entrySet()) {
				pathsAndTexts.add(text.getKey().toString());
				pathsAndTexts.add(text.getValue());
			}
			programs += same(shader.getKey(), TestProjects.prepare(
					pathsAndTexts.toArray(new String[0])).plan());
		}
		assertTrue("database: " + (programs - before),
				programs - before >= 20);
		// Each step: a version, the extension for a camera, characters
		// that are no ASCII, in code, in a comment and behind a
		// backslash that ends a line of code or a directive, and a
		// character of two units.
		String[] texts = {
				"void main() {}\n",
				"#version 300 es\nprecision highp float;\nout vec4 c;\n" +
						"void main() { c = vec4(1.0); }\n",
				"#extension GL_OES_EGL_image_external : require\n" +
						"uniform samplerExternalOES cameraBack;\n" +
						"void main() {}\n",
				"#version 300 es\nuniform samplerExternalOES cameraBack;\n" +
						"out vec4 c;\nvoid main() {}\n",
				"// é 😀\nfloat x = 1.0; /* 中 */\n" +
						"void main() {}\n",
				"#define A 1.0 \\é\n\t+ 2.0\nfloat x = 1.0 + \\ é\n" +
						"\t2.0; // a \\é\nvoid main() {}\n",
				"float 😀x;\nvoid main() {}\n",
				"\tfloat\tx;\r\nvoid main() {}\r\n",
		};
		for (String text : texts) {
			programs += same(text, TestProjects.prepare("main.frag", text)
					.plan());
		}
	}

	private static RenderPlan plan(Map<String, String> files) {
		List<String> pathsAndTexts = new ArrayList<>();
		for (Map.Entry<String, String> text : files.entrySet()) {
			pathsAndTexts.add(text.getKey());
			pathsAndTexts.add(text.getValue());
		}
		return TestProjects.prepare(pathsAndTexts.toArray(new String[0]))
				.plan();
	}

	// Compares every program of plan; returns how many it has.
	private static int same(String about, RenderPlan plan) {
		assertNotNull(about, plan);
		for (RenderPlan.Program program : plan.programs()) {
			List<ProjectDiagnostic> errors = new ArrayList<>();
			PlanSource source = PlanSource.of(program, 3, errors);
			assertNotNull(about + " " + errors, source);
			List<String> generated = new ArrayList<>();
			assertEquals(about + " " + program.file(), before(program,
					generated), source.fragment());
			assertEquals(about + " " + program.file(), program.sourceMap()
					.insertGenerated(program.head(), generated.size()),
					source.sourceMap());
		}
		return plan.programs().size();
	}

	// PlanSource.of as it made the text before, without a tuning.
	private static String before(RenderPlan.Program program,
			List<String> generated) {
		PreprocessorScan scan = PreprocessorScan.scan(program.lines(), null);
		int version = 100;
		for (PreprocessorScan.Directive directive : scan.directives()) {
			if (directive.kind() == PreprocessorScan.Kind.VERSION) {
				Matcher matcher = VERSION.matcher(directive.argument());
				version = matcher.lookingAt()
						? Integer.parseInt(matcher.group())
						: 100;
				break;
			}
		}
		generated.add("#define SHADER_EDITOR 1");
		if (EXTERNAL_SAMPLER.matcher(String.join("\n", scan.code())).find()) {
			generated.add(version >= 300
					? "#extension GL_OES_EGL_image_external_essl3 : require"
					: "#extension GL_OES_EGL_image_external : require");
		}
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < program.lines().size(); ++i) {
			String line = program.lines().get(i);
			int tail = SourceText.afterContinuation(line,
					scan.code().get(i));
			lines.add(tail < 0 ? line : line.substring(0, tail));
		}
		lines.addAll(program.head(), generated);
		return (String.join("\n", lines) + "\n").replaceAll(
				"[^\\x0A\\x09\\x20-\\x7E]", " ");
	}
}
