package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.RenderPlan;

public class PlanSourceTest {
	@Test
	public void mapsADriverErrorInANestedIncludeToItsFileAndLine() {
		var source = source(3, plan(
				"main.frag", lines(
						"#version 300 es",
						"precision mediump float;",
						"#include \"lib/common.glsl\"",
						"out vec4 color;",
						"void main() { color = vec4(f()); }"),
				"lib/common.glsl", lines(
						"#include \"inner.glsl\"",
						"float f() { return g(); }"),
				"lib/inner.glsl", lines(
						"float g() {",
						"\treturn oops;",
						"}")));
		var mapping = source.sourceMap();

		var error = DriverLog.parse("ERROR: 0:" +
				lineOf(source.fragment(), "\treturn oops;") +
				": 'oops' : undeclared identifier", mapping).get(0);
		assertEquals(path("lib/inner.glsl"), error.file());
		assertEquals(2, error.line());
		assertEquals("'oops' : undeclared identifier", error.message());

		var main = DriverLog.parse("ERROR: 0:" +
				lineOf(source.fragment(), "out vec4 color;") + ": x",
				mapping).get(0);
		assertEquals(path("main.frag"), main.file());
		assertEquals(4, main.line());

		String generated = "ERROR: 0:" + lineOf(source.fragment(),
				"#define SHADER_EDITOR 1") + ": redefinition";
		var unmapped = DriverLog.parse(generated, mapping).get(0);
		assertNull(unmapped.file());
		assertEquals(generated, unmapped.message());
	}

	@Test
	public void generatesItsLinesAfterTheVersion() {
		var source = source(3, plan("main.frag", lines(
				"// header",
				"#version 300 es",
				"uniform samplerExternalOES cameraBack;",
				"out vec4 color;",
				"void main() {}")));
		assertEquals(lines(
				"// header",
				"#version 300 es",
				"#define SHADER_EDITOR 1",
				"#extension GL_OES_EGL_image_external_essl3 : require",
				"uniform samplerExternalOES cameraBack;",
				"out vec4 color;",
				"void main() {}"), source.fragment());
		assertEquals("#version 300 es\n" + FullScreenQuad.VERTEX_SHADER_3,
				source.vertex());
	}

	@Test
	public void generatesItsLinesAtTheTopWithoutAVersion() {
		var source = source(2, plan("main.frag", lines(
				"uniform samplerExternalOES cameraFront;",
				"void main() {}")));
		assertEquals(lines(
				"#define SHADER_EDITOR 1",
				"#extension GL_OES_EGL_image_external : require",
				"uniform samplerExternalOES cameraFront;",
				"void main() {}"), source.fragment());
		assertEquals(FullScreenQuad.VERTEX_SHADER, source.vertex());
		var first = source.sourceMap().locate(3);
		assertEquals(path("main.frag"), first.file());
		assertEquals(1, first.line());
		assertTrue(source.sourceMap().locate(1).isGenerated());
	}

	@Test
	public void refusesAVersionTheContextCannotCompile() {
		var errors = new ArrayList<ProjectDiagnostic>();
		var plan = plan("main.frag", lines(
				"",
				"#version 310 es",
				"void main() {}"));
		assertNull(PlanSource.of(plan.programs().get(0), 2, errors));
		assertEquals(List.of(new ProjectDiagnostic(Severity.ERROR,
				"#version 310 es needs OpenGL ES 3.0, but this device only " +
						"has OpenGL ES 2.0", path("main.frag"), 2)), errors);
		assertNotNull(PlanSource.of(plan.programs().get(0), 3, errors));
	}

	@Test
	public void readsTheFTimePeriodOfTheAssembledSource() {
		assertEquals(5f, source(2, plan(
				"main.frag", lines("#include \"period.glsl\"", "void main() {}"),
				"period.glsl", "#define FTIME_PERIOD 5.\n")).fTimeMax(), 0f);
		assertEquals(3f, source(2, plan("main.frag",
				"void main() {}\n")).fTimeMax(), 0f);
	}

	@Test
	public void fTimePeriodsKeepTheirValue() {
		assertEquals(1.5f, fTimeMax("1.5"), 0f);
		assertEquals(0.25f, fTimeMax(".25"), 0f);
		assertEquals(12f, fTimeMax("12"), 0f);
	}

	// Each would make ftime NaN.
	@Test
	public void zeroOrOverflowingFTimePeriodsUseTheDefault() {
		for (String period : new String[]{"0", "0.0", "9".repeat(100),
				"0." + "0".repeat(100) + "1"}) {
			assertEquals(period, 3f, fTimeMax(period), 0f);
		}
	}

	@Test
	public void malformedFTimePeriodIsAnErrorAtItsLine() {
		var errors = new ArrayList<ProjectDiagnostic>();
		var plan = plan(
				"main.frag", lines(
						"precision mediump float;",
						"#include \"period.glsl\"",
						"void main() {}"),
				"period.glsl", lines(
						"// the period",
						"#define FTIME_PERIOD 1..2"));
		assertNull(PlanSource.of(plan.programs().get(plan.output()), 2, errors));
		assertEquals(List.of(new ProjectDiagnostic(Severity.ERROR,
				"FTIME_PERIOD 1..2 is not a number", path("period.glsl"), 2)),
				errors);
	}

	@Test
	public void blanksNonAsciiCharactersOfCommentsButKeepsTheLines() {
		var source = source(2, plan("main.frag", lines(
				"// \u00e9t\u00e9",
				"void main() {}")));
		assertEquals(lines("#define SHADER_EDITOR 1", "//  t ", "void main() {}"),
				source.fragment());
	}

	// A shader written by hand with more than ASCII compiles: every
	// such character is a space for the compiler, on its own line.
	@Test
	public void blanksNonAsciiCharactersOfCodeToo() {
		var source = source(2, plan("main.frag", lines(
				"// Gr\u00fc\u00dfe \u4e16\u754c",
				"void main() {\u00a0",
				"\tgl_FragColor = vec4(1.0); // \ud83d\ude00",
				"}")));
		assertEquals(lines("#define SHADER_EDITOR 1", "// Gr  e   ",
				"void main() { ", "\tgl_FragColor = vec4(1.0); //  ",
				"}"), source.fragment());
		assertEquals(4, source.sourceMap().locate(5).line());
	}

	// The comment names a camera, but the shader reads none.
	@Test
	public void commentsRequireNoExtension() {
		var source = source(2, plan("main.frag", lines(
				"// would read a samplerExternalOES here",
				"/* uniform samplerExternalOES cameraBack; */",
				"void main() {}")));
		assertFalse(source.fragment(), source.fragment().contains("#extension"));
	}

	@Test
	public void versionInACommentIsNoVersion() {
		var errors = new ArrayList<ProjectDiagnostic>();
		var plan = plan("main.frag", lines(
				"/*",
				"#version 300 es",
				"*/",
				"void main() {}"));
		var source = PlanSource.of(plan.programs().get(0), 2, errors);
		assertEquals(List.of(), errors);
		assertNotNull(source);
		assertEquals(FullScreenQuad.VERTEX_SHADER, source.vertex());
	}

	private static PlanSource source(int glesVersion, RenderPlan plan) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var source = PlanSource.of(plan.programs().get(plan.output()),
				glesVersion, errors);
		assertTrue(errors.toString(), errors.isEmpty());
		return source;
	}

	private static int lineOf(String text, String line) {
		return List.of(text.split("\n")).indexOf(line) + 1;
	}

	private static float fTimeMax(String period) {
		return source(2, plan("main.frag", lines(
				"#define FTIME_PERIOD " + period,
				"void main() {}"))).fTimeMax();
	}
}
