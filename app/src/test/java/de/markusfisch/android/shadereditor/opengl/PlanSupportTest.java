package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.RenderPlan;

public class PlanSupportTest {
	private static final String MAIN = "void main() {}";
	private static final String PNG = "\u0089PNG";

	@Test
	public void acceptsOnePassWithImagesEngineSamplersAndDefaultTarget() {
		var plan = plan("main.glsl", lines(
						"#pragma se input \"a.png\" wrap=repeat mipmap=linear",
						"uniform sampler2D a;",
						"#pragma se input \"a.png\" filter=nearest",
						"uniform sampler2D b;",
						"#pragma se input filter=linear",
						"uniform samplerExternalOES cameraBack;",
						"uniform sampler2D backbuffer;",
						"#pragma se target scale=1 format=rgba8 present=nearest",
						MAIN),
				"a.png", PNG);
		assertTrue(PlanSupport.refusals(plan).isEmpty());
	}

	@Test
	public void refusesInputsFromOtherPasses() {
		var plan = plan("main.glsl", lines(
						"#pragma se input \"b.glsl\"",
						"uniform sampler2D b;",
						"#pragma se input \"main.glsl\" frame=previous",
						"uniform sampler2D self;",
						MAIN),
				"b.glsl", MAIN);
		assertEquals(List.of(
				error("Reading the output of b.glsl needs a newer version " +
						"of ShaderEditor", 1)), PlanSupport.refusals(plan));
	}

	@Test
	public void acceptsReadingItsOwnPreviousFrame() {
		assertTrue(PlanSupport.refusals(plan("main.glsl", lines(
				"#pragma se input filter=linear",
				"uniform sampler2D backbuffer;",
				"#pragma se input \"main.glsl\" frame=previous",
				"uniform sampler2D self;",
				MAIN))).isEmpty());
	}

	@Test
	public void refusesTargetsOtherThanTheDefault() {
		assertEquals(List.of(error("se target scale, format needs a newer " +
						"version of ShaderEditor", 1)),
				PlanSupport.refusals(plan("main.glsl", lines(
						"#pragma se target scale=0.5 format=rgba16f",
						MAIN))));
		assertEquals(List.of(error("se target size, present needs a newer " +
						"version of ShaderEditor", 2)),
				PlanSupport.refusals(plan("main.glsl", lines(
						MAIN,
						"#pragma se target size=64x64 present=linear"))));
	}

	@Test
	public void refusesInitial() {
		var plan = plan("main.glsl", lines(
						"#pragma se target initial=\"seed.png\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.png", PNG);
		assertEquals(List.of(error("se target initial needs a newer " +
				"version of ShaderEditor", 1)), PlanSupport.refusals(plan));
	}

	@Test
	public void acceptsCubemapImages() {
		var plan = plan("main.glsl", lines(
						MAIN,
						"#pragma se input \"sky.png\" mipmap=linear",
						"uniform samplerCube sky;"),
				"sky.png", PNG);
		assertTrue(PlanSupport.refusals(plan).isEmpty());
	}

	@Test
	public void refusesMoreThanOnePass() {
		var plan = plan("main.glsl", MAIN);
		var pass = plan.passes().get(0);
		var twoPasses = new RenderPlan(plan.programs(), List.of(pass, pass),
				1, List.of());
		assertEquals(List.of(new ProjectDiagnostic(Severity.ERROR,
						"Projects with more than one pass need a newer " +
								"version of ShaderEditor", null, 0)),
				PlanSupport.refusals(twoPasses));
	}

	private static ProjectDiagnostic error(String message, int line) {
		return new ProjectDiagnostic(Severity.ERROR, message,
				path("main.glsl"), line);
	}
}
