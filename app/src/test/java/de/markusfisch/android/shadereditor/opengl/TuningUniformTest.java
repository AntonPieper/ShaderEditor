package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.SourceLocation;

// A tuned number is a uniform the host sets: declared where the text
// uses it, set without a build, and its errors still on the user's lines.
public class TuningUniformTest {
	private static final String PRECISION = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif");
	private static final List<String> DECLARATION = List.of(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"uniform highp float se_tune0;",
			"#else",
			"uniform mediump float se_tune0;",
			"#endif");

	@Test
	public void theDeclarationFollowsTheGeneratedLines() {
		var source = source(lines(PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(se_tune0);",
				"}"), 2);
		var lines = List.of(source.fragment().split("\n"));
		assertEquals("#define SHADER_EDITOR 1", lines.get(0));
		assertEquals(DECLARATION, lines.subList(1, 6));
		assertEquals(PRECISION.trim(), String.join("\n", lines.subList(6, 11)));
		// Generated lines map to no line of the user's; the use, line 8
		// of main.frag, is line 14 of what compiles.
		for (int line = 1; line <= 6; ++line) {
			assertSame(SourceLocation.GENERATED,
					source.sourceMap().locate(line));
		}
		var use = source.sourceMap().locate(14);
		assertEquals(path("main.frag"), use.file());
		assertEquals(8, use.line());
		assertTrue(lines.get(13).contains("se_tune0"));
	}

	@Test
	public void inGlslEs300ItFollowsTheVersion() {
		var source = source(lines("#version 300 es", PRECISION,
				"out vec4 color;",
				"void main(void) {",
				"	color = vec4(se_tune0);",
				"}"), 3);
		var lines = List.of(source.fragment().split("\n"));
		assertEquals("#version 300 es", lines.get(0));
		assertEquals("#define SHADER_EDITOR 1", lines.get(1));
		assertEquals(DECLARATION, lines.subList(2, 7));
		var use = source.sourceMap().locate(lines.size() - 1);
		assertEquals(10, use.line());
	}

	// No declaration may precede an #extension (Mesa: "#extension
	// directive is not allowed in the middle of a shader").
	@Test
	public void theDeclarationFollowsTheLastExtension() {
		var lines = List.of(source(lines(
				"#extension GL_OES_standard_derivatives : enable",
				PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(se_tune0 * dFdx(1.0));",
				"}"), 2).fragment().split("\n"));
		assertEquals("#extension GL_OES_standard_derivatives : enable",
				lines.get(1));
		assertEquals(DECLARATION, lines.subList(2, 7));
	}

	// Inside a conditional it would be left out with the #extension.
	@Test
	public void aConditionalExtensionIsFollowedAfterItsEndif() {
		var lines = List.of(source(lines(
				"#ifdef GL_OES_standard_derivatives",
				"#extension GL_OES_standard_derivatives : enable",
				"#endif",
				PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(se_tune0);",
				"}"), 2).fragment().split("\n"));
		assertEquals("#endif", lines.get(3));
		assertEquals(DECLARATION, lines.subList(4, 9));
	}

	// A program that does not use it, or names it in a comment only,
	// declares nothing.
	@Test
	public void onlyAProgramThatUsesItDeclaresIt() {
		String text = lines(PRECISION,
				"// se_tune0 is not used here",
				"void main(void) {",
				"	gl_FragColor = vec4(1.0);",
				"}");
		var source = source(text, 2);
		assertFalse(source.fragment().contains("uniform highp float"));
		assertEquals(path("main.frag"),
				source.sourceMap().locate(2).file());
	}

	@Test
	public void itStartsAtTheNumbersValueAndChangesWithoutABuild() {
		var tuning = tuning(Tuning.Type.FLOAT, 0.3);
		var engine = new FakeEngine(new FakeGlDevice(), prepare("main.frag",
				lines(PRECISION, "uniform vec2 resolution;",
						"void main(void) {",
						"	gl_FragColor = vec4(se_tune0);",
						"}")),
				TargetFormats.of(2, ""), List.of(tuning));
		assertTrue(engine.diagnostics.toString(), engine.runtime != null);
		engine.resize(4, 4);
		engine.draw(0);
		assertEquals(0.3f, value(engine, "main.frag"), 0f);
		int programs = engine.device.programs.size();
		tuning.set(0.75);
		engine.runtime.applyTuning();
		engine.draw(1);
		assertEquals(0.75f, value(engine, "main.frag"), 0f);
		// Nothing was compiled for it.
		assertEquals(programs, engine.device.programs.size());
		// What the host sets is not a uniform "nothing provides".
		for (var diagnostic : engine.diagnostics) {
			assertFalse(diagnostic.toString(),
					diagnostic.message().contains("se_tune0"));
		}
	}

	// A pass file or an include: each program that uses it gets it.
	@Test
	public void onlyThePassesThatUseItAreSet() {
		var tuning = tuning(Tuning.Type.FLOAT, 2.0);
		var engine = new FakeEngine(new FakeGlDevice(), prepare(
						"main.frag", lines(PRECISION,
								"#pragma se input \"blur.frag\"",
								"uniform sampler2D blur;",
								"uniform vec2 resolution;",
								"void main(void) {",
								"	gl_FragColor = texture2D(blur, vec2(0.0));",
								"}"),
						"blur.frag", lines(PRECISION,
								"void main(void) {",
								"	gl_FragColor = vec4(se_tune0);",
								"}")),
				TargetFormats.of(2, ""), List.of(tuning));
		engine.resize(4, 4);
		engine.draw(0);
		assertEquals(2f, value(engine, "blur.frag"), 0f);
		assertNull(last(engine, "main.frag").uniforms().get("se_tune0"));
	}

	@Test
	public void integersAreSetAsIntegers() {
		var tuning = tuning(Tuning.Type.UINT, 7);
		var engine = new FakeEngine(new FakeGlDevice(), prepare("main.frag",
				lines("#version 300 es", PRECISION, "out vec4 color;",
						"void main(void) {",
						"	color = vec4(float(se_tune0));",
						"}")),
				TargetFormats.of(3, ""), List.of(tuning));
		engine.resize(4, 4);
		engine.draw(0);
		assertEquals(7f, value(engine, "main.frag"), 0f);
		assertEquals(List.of(
						"#ifdef GL_FRAGMENT_PRECISION_HIGH",
						"uniform highp uint se_tune0;",
						"#else",
						"uniform mediump uint se_tune0;",
						"#endif"),
				tuning.declaration());
	}

	// F2: an int is written with the language's 32-bit wrap, as the
	// literal's bit pattern is, never clamped.
	@Test
	public void anIntOutsideItsRangeWraps() {
		var tuning = tuning(Tuning.Type.INT, -1);
		var engine = new FakeEngine(new FakeGlDevice(), prepare("main.frag",
				lines("#version 300 es", PRECISION, "out vec4 color;",
						"void main(void) {",
						"	color = vec4(float(se_tune0));",
						"}")),
				TargetFormats.of(3, ""), List.of(tuning));
		engine.resize(4, 4);
		engine.draw(0);
		assertEquals(-1f, value(engine, "main.frag"), 0f);
		tuning.set(0xffffffffL);
		engine.runtime.applyTuning();
		engine.draw(1);
		assertEquals(-1f, value(engine, "main.frag"), 0f);
	}

	// F5: never inside a block comment an #extension line opens, nor
	// on a line a backslash continues into.
	@Test
	public void theDeclarationFollowsACommentOpenedOnAnExtension() {
		for (String[] start : new String[][]{
				{"#extension GL_OES_standard_derivatives : enable /*",
						"still a comment */"},
				{"#extension GL_OES_standard_derivatives : enable // a\\",
						"comment continued"}}) {
			var lines = List.of(source(lines(start[0], start[1], PRECISION,
					"void main(void) {",
					"	gl_FragColor = vec4(se_tune0);",
					"}"), 2).fragment().split("\n"));
			assertEquals(start[1], lines.get(2));
			assertEquals(DECLARATION, lines.subList(3, 8));
		}
	}

	@NonNull
	private PlanSource source(@NonNull String text, int glesVersion) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var source = PlanSource.of(plan("main.frag", text).programs().get(0),
				glesVersion, List.of(tuning(Tuning.Type.FLOAT, 0.0)), errors);
		assertEquals(List.of(), errors);
		return source;
	}

	// Several numbers of one build, each a uniform of its own: each
	// program declares the ones its text uses, and each is set without a
	// build.
	@Test
	public void severalUniformsInOneBuild() {
		var a = new Tuning("se_tune0", Tuning.Type.FLOAT, 0.25);
		var b = new Tuning("se_tune1", Tuning.Type.FLOAT, 0.5);
		var c = new Tuning("se_tune2", Tuning.Type.INT, 3);
		var engine = new FakeEngine(new FakeGlDevice(), prepare(
						"main.frag", lines(PRECISION,
								"#pragma se input \"blur.frag\"",
								"uniform sampler2D blur;",
								"uniform vec2 resolution;",
								"void main(void) {",
								"	gl_FragColor = texture2D(blur, vec2(0.0)) *",
								"			vec4(se_tune0, se_tune1, 0.0, 1.0);",
								"}"),
						"blur.frag", lines(PRECISION,
								"void main(void) {",
								"	gl_FragColor = vec4(se_tune1 * " +
										"float(se_tune2));",
								"}")),
				TargetFormats.of(2, ""), List.of(a, b, c));
		assertTrue(engine.diagnostics.toString(), engine.runtime != null);
		for (var diagnostic : engine.diagnostics) {
			assertFalse(diagnostic.toString(),
					diagnostic.message().contains("se_tune"));
		}
		engine.resize(4, 4);
		engine.draw(0);
		assertEquals(0.25f, value(engine, "main.frag", "se_tune0"), 0f);
		assertEquals(0.5f, value(engine, "main.frag", "se_tune1"), 0f);
		assertNull(last(engine, "main.frag").uniforms().get("se_tune2"));
		assertEquals(0.5f, value(engine, "blur.frag", "se_tune1"), 0f);
		assertEquals(3f, value(engine, "blur.frag", "se_tune2"), 0f);
		assertNull(last(engine, "blur.frag").uniforms().get("se_tune0"));
		int programs = engine.device.programs.size();
		a.set(0.75);
		c.set(-4);
		engine.runtime.applyTuning();
		engine.draw(1);
		assertEquals(0.75f, value(engine, "main.frag", "se_tune0"), 0f);
		assertEquals(0.5f, value(engine, "main.frag", "se_tune1"), 0f);
		assertEquals(-4f, value(engine, "blur.frag", "se_tune2"), 0f);
		assertEquals(programs, engine.device.programs.size());
	}

	// The build owns which uniforms it has: a caller that reuses its list
	// changes nothing; a value set on a uniform still shows.
	@Test
	public void aBuildOwnsItsUniforms() {
		var tuning = tuning(Tuning.Type.FLOAT, 0.25);
		var list = new ArrayList<Tuning>();
		list.add(tuning);
		var engine = new FakeEngine(new FakeGlDevice(), prepare("main.frag",
				lines(PRECISION, "void main(){gl_FragColor=vec4(se_tune0);}")),
				TargetFormats.of(2, ""), list);
		engine.resize(4, 4);
		engine.draw(0);
		list.clear();
		tuning.set(0.75);
		engine.runtime.applyTuning();
		engine.draw(1);
		assertEquals(0.75f, value(engine, "main.frag"), 0f);
	}

	// Declared once each, together, after the last #extension; one the
	// program does not use is left out.
	@Test
	public void eachUsedUniformIsDeclaredOnce() {
		var program = plan("main.frag", lines(
				"#extension GL_OES_standard_derivatives : enable",
				PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(se_tune1, se_tune0, se_tune1, 1.0);",
				"}")).programs().get(0);
		var errors = new ArrayList<ProjectDiagnostic>();
		var source = PlanSource.of(program, 2, List.of(
				new Tuning("se_tune0", Tuning.Type.FLOAT, 0),
				new Tuning("se_tune1", Tuning.Type.FLOAT, 0),
				new Tuning("se_tune2", Tuning.Type.FLOAT, 0)), errors);
		assertEquals(List.of(), errors);
		var lines = List.of(source.fragment().split("\n"));
		assertEquals("#extension GL_OES_standard_derivatives : enable",
				lines.get(1));
		var both = new ArrayList<>(DECLARATION);
		for (String line : DECLARATION) {
			both.add(line.replace("se_tune0", "se_tune1"));
		}
		assertEquals(both, lines.subList(2, 12));
		assertFalse(source.fragment().contains("se_tune2"));
		// The user's line 9 (PRECISION ends in an empty line).
		var use = source.sourceMap().locate(lines.size() - 1);
		assertTrue(lines.get(lines.size() - 2).contains("se_tune1, se_tune0"));
		assertEquals(9, use.line());
	}

	@NonNull
	private static Tuning tuning(@NonNull Tuning.Type type, double value) {
		return new Tuning("se_tune0", type, value);
	}

	private static float value(@NonNull FakeEngine engine,
			@NonNull String file) {
		return value(engine, file, "se_tune0");
	}

	private static float value(@NonNull FakeEngine engine,
			@NonNull String file, @NonNull String name) {
		return last(engine, file).uniforms().get(name)[0];
	}

	@NonNull
	private static FakeGlDevice.Draw last(@NonNull FakeEngine engine,
			@NonNull String file) {
		var draws = engine.draws(file);
		return draws.get(draws.size() - 1);
	}
}
