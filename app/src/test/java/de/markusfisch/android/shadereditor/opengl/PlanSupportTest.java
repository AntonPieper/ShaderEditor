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
		var plan = plan("main.frag", lines(
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
		var plan = plan("main.frag", lines(
						"#pragma se input \"b.glsl\"",
						"uniform sampler2D b;",
						"#pragma se input \"main.frag\" frame=previous",
						"uniform sampler2D self;",
						MAIN),
				"b.glsl", MAIN);
		assertEquals(List.of(
				error("Reading the output of b.glsl needs a newer version " +
						"of ShaderEditor", 1)), PlanSupport.refusals(plan));
	}

	@Test
	public void acceptsReadingItsOwnPreviousFrame() {
		assertTrue(PlanSupport.refusals(plan("main.frag", lines(
				"#pragma se input filter=linear",
				"uniform sampler2D backbuffer;",
				"#pragma se input \"main.frag\" frame=previous",
				"uniform sampler2D self;",
				MAIN))).isEmpty());
	}

	@Test
	public void acceptsTargetsOfAnySizeAndPresent() {
		assertTrue(PlanSupport.refusals(plan("main.frag", lines(
				"#pragma se target scale=0.5 present=linear",
				MAIN))).isEmpty());
		assertTrue(PlanSupport.refusals(plan("main.frag", lines(
				MAIN,
				"#pragma se target size=64x64"))).isEmpty());
	}

	@Test
	public void refusesFormatsOtherThanRgba8() {
		assertEquals(List.of(error("se target format needs a newer " +
						"version of ShaderEditor", 1)),
				PlanSupport.refusals(plan("main.frag", lines(
						"#pragma se target scale=0.5 format=rgba16f",
						MAIN))));
	}

	@Test
	public void acceptsAnInitialImage() {
		assertTrue(PlanSupport.refusals(plan("main.frag", lines(
						"#pragma se target initial=\"seed.png\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.png", PNG)).isEmpty());
	}

	@Test
	public void acceptsAnInitialShaderThatReadsAnImage() {
		assertTrue(PlanSupport.refusals(plan("main.frag", lines(
						"#pragma se target initial=\"seed.glsl\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.glsl", lines(
						"#pragma se input \"a.png\"",
						"uniform sampler2D a;",
						"#pragma se target scale=0.5",
						MAIN),
				"a.png", PNG)).isEmpty());
		// Ordered by path, the initial shader may come first.
		assertTrue(PlanSupport.refusals(plan("main.frag", lines(
						"#pragma se target initial=\"a.glsl\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"a.glsl", MAIN)).isEmpty());
	}

	@Test
	public void refusesWhatAnInitialShaderCannotHaveYet() {
		assertEquals(List.of(new ProjectDiagnostic(Severity.ERROR,
						"se target initial needs a newer version of " +
								"ShaderEditor", path("seed.glsl"), 1)),
				PlanSupport.refusals(plan("main.frag", lines(
								"#pragma se target initial=\"seed.glsl\"",
								"uniform sampler2D backbuffer;",
								MAIN),
						"seed.glsl", lines(
								"#pragma se target initial=\"seed.png\"",
								"uniform sampler2D backbuffer;",
								MAIN),
						"seed.png", PNG)));
		assertEquals(List.of(new ProjectDiagnostic(Severity.ERROR,
						"Reading the output of b.glsl needs a newer version " +
								"of ShaderEditor", path("seed.glsl"), 1)),
				PlanSupport.refusals(plan("main.frag", lines(
								"#pragma se target initial=\"seed.glsl\"",
								"uniform sampler2D backbuffer;",
								MAIN),
						"seed.glsl", lines(
								"#pragma se input \"b.glsl\"",
								"uniform sampler2D b;",
								MAIN),
						"b.glsl", MAIN)));
	}

	@Test
	public void acceptsCubemapImages() {
		var plan = plan("main.frag", lines(
						MAIN,
						"#pragma se input \"sky.png\" mipmap=linear",
						"uniform samplerCube sky;"),
				"sky.png", PNG);
		assertTrue(PlanSupport.refusals(plan).isEmpty());
	}

	@Test
	public void refusesMoreThanOnePass() {
		var plan = plan("main.frag", MAIN);
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
				path("main.frag"), line);
	}
}
