package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static org.junit.Assert.assertEquals;
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
				"main.glsl", lines(
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
		var mapping = ShaderLineMapping.withSourceMap(source.sourceMap());

		var error = DriverLog.parse("ERROR: 0:" +
				lineOf(source.fragment(), "\treturn oops;") +
				": 'oops' : undeclared identifier", mapping).get(0);
		assertEquals(path("lib/inner.glsl"), error.file());
		assertEquals(2, error.line());
		assertEquals("'oops' : undeclared identifier", error.message());

		var main = DriverLog.parse("ERROR: 0:" +
				lineOf(source.fragment(), "out vec4 color;") + ": x",
				mapping).get(0);
		assertEquals(path("main.glsl"), main.file());
		assertEquals(4, main.line());

		String generated = "ERROR: 0:" + lineOf(source.fragment(),
				"#define SHADER_EDITOR 1") + ": redefinition";
		var unmapped = DriverLog.parse(generated, mapping).get(0);
		assertNull(unmapped.file());
		assertEquals(generated, unmapped.message());
	}

	@Test
	public void generatesItsLinesAfterTheVersion() {
		var source = source(3, plan("main.glsl", lines(
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
		var source = source(2, plan("main.glsl", lines(
				"uniform samplerExternalOES cameraFront;",
				"void main() {}")));
		assertEquals(lines(
				"#define SHADER_EDITOR 1",
				"#extension GL_OES_EGL_image_external : require",
				"uniform samplerExternalOES cameraFront;",
				"void main() {}"), source.fragment());
		assertEquals(FullScreenQuad.VERTEX_SHADER, source.vertex());
		var first = source.sourceMap().locate(3);
		assertEquals(path("main.glsl"), first.file());
		assertEquals(1, first.line());
		assertTrue(source.sourceMap().locate(1).isGenerated());
	}

	@Test
	public void refusesAVersionTheContextCannotCompile() {
		var errors = new ArrayList<ProjectDiagnostic>();
		var plan = plan("main.glsl", lines(
				"",
				"#version 310 es",
				"void main() {}"));
		assertNull(PlanSource.of(plan.programs().get(0), 2, errors));
		assertEquals(List.of(new ProjectDiagnostic(Severity.ERROR,
				"#version 310 es needs OpenGL ES 3.0, but this device only " +
						"has OpenGL ES 2.0", path("main.glsl"), 2)), errors);
		assertNotNull(PlanSource.of(plan.programs().get(0), 3, errors));
	}

	@Test
	public void readsTheFTimePeriodOfTheAssembledSource() {
		assertEquals(5f, source(2, plan(
				"main.glsl", lines("#include \"period.glsl\"", "void main() {}"),
				"period.glsl", "#define FTIME_PERIOD 5.\n")).fTimeMax(), 0f);
		assertEquals(3f, source(2, plan("main.glsl", lines(
				"#define FTIME_PERIOD 1..2",
				"void main() {}"))).fTimeMax(), 0f);
		assertEquals(3f, source(2, plan("main.glsl", lines(
				"#define FTIME_PERIOD 0",
				"void main() {}"))).fTimeMax(), 0f);
	}

	@Test
	public void removesNonAsciiCharactersButKeepsTheLines() {
		var source = source(2, plan("main.glsl", lines(
				"// été",
				"void main() {}")));
		assertEquals(lines("#define SHADER_EDITOR 1", "// t", "void main() {}"),
				source.fragment());
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
}
