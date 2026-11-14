package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.RenderPlan;

public class PlanSupportTest {
	private static final TargetFormats ALL = TargetFormats.of(3,
			"GL_EXT_color_buffer_float");
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
		assertTrue(PlanSupport.refusals(plan, ALL).isEmpty());
	}

	@Test
	public void acceptsInputsFromOtherPassesAndTheirPreviousFrames() {
		assertTrue(refusals(ALL, plan("main.frag", lines(
						"#pragma se input \"b.frag\"",
						"uniform sampler2D b;",
						"#pragma se input \"c.frag\" frame=previous",
						"uniform sampler2D c;",
						MAIN),
				"b.frag", MAIN,
				"c.frag", lines(
						"#pragma se input \"b.frag\" frame=previous",
						"uniform sampler2D b;",
						MAIN))).isEmpty());
	}

	@Test
	public void acceptsReadingItsOwnPreviousFrame() {
		assertTrue(refusals(ALL, plan("main.frag", lines(
				"#pragma se input filter=linear",
				"uniform sampler2D backbuffer;",
				"#pragma se input \"main.frag\" frame=previous",
				"uniform sampler2D self;",
				MAIN))).isEmpty());
	}

	@Test
	public void acceptsTargetsOfAnySizeAndPresent() {
		assertTrue(refusals(ALL, plan("main.frag", lines(
				"#pragma se target scale=0.5 present=linear",
				MAIN))).isEmpty());
		assertTrue(refusals(ALL, plan("main.frag", lines(
				MAIN,
				"#pragma se target size=64x64"))).isEmpty());
	}

	@Test
	public void refusesRgba16fWhereTheDeviceCannotDrawIntoIt() {
		var plan = plan("main.frag", lines(
				"#pragma se target scale=0.5 format=rgba16f",
				MAIN));
		assertTrue(refusals(ALL, plan).isEmpty());
		assertEquals(List.of(error(TargetFormats.of(3, "")
						.refusal(Format.RGBA16F), 1)),
				refusals(TargetFormats.of(3, ""), plan));
	}

	// Two passes in rgba16f read linearly: by an input, by present and by
	// initial.
	@Test
	public void refusesLinearReadsOfRgba16fWhereTheDeviceCannotFilterIt() {
		var plan = plan("main.frag", lines(
						"#pragma se input \"a.frag\" filter=nearest",
						"uniform sampler2D crisp;",
						"#pragma se input \"a.frag\" mag=linear",
						"uniform sampler2D smooth;",
						"#pragma se input \"main.frag\" frame=previous",
						"uniform sampler2D self;",
						"#pragma se target format=rgba16f present=linear " +
								"initial=\"a.frag\"",
						MAIN),
				"a.frag", lines(
						"#pragma se target format=rgba16f",
						MAIN));
		var unfiltered = TargetFormats.of(2, "GL_OES_texture_half_float " +
				"GL_EXT_color_buffer_half_float");
		var a = "a.frag, a target in format=rgba16f,";
		var linear = ", which needs OES_texture_half_float_linear; this " +
				"device does not have it, ";
		assertEquals(List.of(
						error("smooth reads " + a + " with a linear filter" +
								linear + "read it with filter=nearest", 3),
						error("initial stretches " + a + " linearly" + linear +
								"start from a pass in format=rgba8", 7),
						error("present=linear scales main.frag, a target in " +
								"format=rgba16f," + linear + "use " +
								"present=nearest", 7)),
				refusals(unfiltered, plan));
		assertTrue(refusals(TargetFormats.of(2, "GL_OES_texture_half_float " +
				"GL_EXT_color_buffer_half_float " +
				"GL_OES_texture_half_float_linear"), plan).isEmpty());
		// Only what it cannot draw into, not that it cannot filter it.
		assertEquals(2, refusals(TargetFormats.of(2, ""), plan).size());
	}

	@Test
	public void acceptsAnInitialImage() {
		assertTrue(refusals(ALL, plan("main.frag", lines(
						"#pragma se target initial=\"seed.png\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.png", PNG)).isEmpty());
	}

	@Test
	public void acceptsAnInitialShaderThatReadsAnImage() {
		assertTrue(refusals(ALL, plan("main.frag", lines(
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
		assertTrue(refusals(ALL, plan("main.frag", lines(
						"#pragma se target initial=\"a.glsl\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"a.glsl", MAIN)).isEmpty());
	}

	@Test
	public void acceptsInitialContentOfAnyPass() {
		assertTrue(refusals(ALL, plan("main.frag", lines(
						"#pragma se input \"state.frag\" frame=previous",
						"uniform sampler2D state;",
						MAIN),
				"state.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.frag", lines(
						"#pragma se input \"b.frag\"",
						"uniform sampler2D b;",
						"#pragma se target initial=\"seed.png\"",
						MAIN),
				"b.frag", MAIN,
				"seed.png", PNG)).isEmpty());
	}

	@Test
	public void acceptsCubemapImages() {
		var plan = plan("main.frag", lines(
						MAIN,
						"#pragma se input \"sky.png\" mipmap=linear",
						"uniform samplerCube sky;"),
				"sky.png", PNG);
		assertTrue(PlanSupport.refusals(plan, ALL).isEmpty());
	}

	private static List<ProjectDiagnostic> refusals(TargetFormats formats,
			RenderPlan plan) {
		return PlanSupport.refusals(plan, formats);
	}

	private static ProjectDiagnostic error(String message, int line) {
		return new ProjectDiagnostic(Severity.ERROR, message,
				path("main.frag"), line);
	}
}
