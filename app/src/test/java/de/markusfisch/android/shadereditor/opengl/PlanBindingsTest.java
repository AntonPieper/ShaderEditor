package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.RenderPlan.Size;

public class PlanBindingsTest {
	private static final String MAIN = "void main() {}";
	private static final String PNG = "\u0089PNG";
	private static final int MAX_SIZE = 4096;

	@Test
	public void bindsEachImageInputToTheSamplerItWasDeclaredFor() {
		var bindings = bindings(lines(
						"#pragma se input \"a.png\" wrap=repeat",
						"uniform sampler2D zebra;",
						"#pragma se input \"b.png\"",
						"uniform sampler2D apple;",
						MAIN),
				"zebra", "apple");
		assertEquals(2, bindings.images.size());
		var zebra = bindings.images.get(0);
		assertEquals("zebra", zebra.sampler().name());
		assertEquals(1, zebra.sampler().unit());
		assertEquals(path("a.png"), zebra.image());
		assertEquals(Wrap.REPEAT, zebra.sampling().wrapS());
		var apple = bindings.images.get(1);
		assertEquals("apple", apple.sampler().name());
		assertEquals(0, apple.sampler().unit());
		assertEquals(Sampling.IMAGE, apple.sampling());
		assertTrue(bindings.warnings.isEmpty());
	}

	@Test
	public void warnsAboutADeclaredInputTheShaderDoesNotUse() {
		var bindings = bindings(lines(
						"#pragma se input \"a.png\"",
						"uniform sampler2D used;",
						"#pragma se input \"b.png\"",
						"uniform sampler2D unused;",
						MAIN),
				"used");
		assertEquals(List.of(path("a.png")),
				new ArrayList<>(bindings.imagePaths()));
		assertEquals(List.of(new ProjectDiagnostic(Severity.WARNING,
						"unused has an input but the shader does not use it, " +
								"so nothing is bound to it", path("main.glsl"), 3)),
				bindings.warnings);
	}

	@Test
	public void readsOneImageOnceForTwoInputs() {
		var bindings = bindings(lines(
						"#pragma se input \"a.png\" filter=nearest",
						"uniform sampler2D crisp;",
						"#pragma se input \"a.png\" mipmap=linear",
						"uniform sampler2D smooth;",
						MAIN),
				"crisp", "smooth");
		assertEquals(2, bindings.images.size());
		assertEquals(Set.of(path("a.png")), bindings.imagePaths());
		assertEquals(Filter.NEAREST, bindings.images.get(0).sampling().mag());
		assertEquals(Mipmap.LINEAR, bindings.images.get(1).sampling().mipmap());
	}

	@Test
	public void leavesEngineSamplersNextToImageInputsToTheEngine() {
		var plan = plan("main.glsl", lines(
						"#pragma se input \"a.png\"",
						"uniform sampler2D a;",
						"#pragma se input filter=linear",
						"uniform samplerExternalOES cameraFront;",
						"uniform sampler2D backbuffer;",
						"uniform sampler2D nothingProvides;",
						MAIN),
				"a.png", PNG);
		var camera = sampler("cameraFront", GLES11Ext.GL_SAMPLER_EXTERNAL_OES);
		var program = new GlProgram(1, List.of(
				sampler("a", GLES20.GL_SAMPLER_2D),
				camera,
				sampler("backbuffer", GLES20.GL_SAMPLER_2D),
				sampler("nothingProvides", GLES20.GL_SAMPLER_2D)));
		var bindings = PlanBindings.of(plan.passes().get(0),
				program.getSamplers());
		assertEquals(1, bindings.images.size());
		assertEquals("a", bindings.images.get(0).sampler().name());
		assertEquals("cameraFront", bindings.camera.name());
		assertEquals(2, bindings.camera.unit());
		assertEquals(new Sampling(Filter.LINEAR, Filter.LINEAR, Mipmap.NONE,
				Wrap.CLAMP, Wrap.CLAMP), bindings.cameraSampling);
		assertTrue(bindings.warnings.isEmpty());
	}

	@Test
	public void anUnannotatedBackbufferReadsThePreviousFrame() {
		var plan = plan("main.glsl", lines(
				"uniform sampler2D backbuffer;",
				MAIN));
		var bindings = PlanBindings.of(plan.passes().get(0),
				List.of(sampler("backbuffer", GLES20.GL_SAMPLER_2D).withUnit(2)));
		assertEquals(1, bindings.history.size());
		assertEquals(2, bindings.history.get(0).sampler().unit());
		assertSame(Sampling.PASS, bindings.history.get(0).sampling());
		assertTrue(bindings.images.isEmpty());
	}

	@Test
	public void aBackbufferOfAnotherTypeOrOptimizedAwayReadsNothing() {
		var plan = plan("main.glsl", lines(
				"uniform samplerCube backbuffer;",
				MAIN));
		assertTrue(PlanBindings.of(plan.passes().get(0), List.of(
				sampler("backbuffer", GLES20.GL_SAMPLER_CUBE).withUnit(0)))
				.history.isEmpty());
		assertTrue(PlanBindings.of(plan.passes().get(0), List.of())
				.history.isEmpty());
	}

	@Test
	public void anUnannotatedCameraKeepsItsDefaultSampling() {
		var bindings = PlanBindings.of(plan("main.glsl", MAIN).passes().get(0),
				List.of(sampler("cameraBack", GLES11Ext.GL_SAMPLER_EXTERNAL_OES)
						.withUnit(0)));
		assertEquals("cameraBack", bindings.camera.name());
		assertSame(Sampling.CAMERA, bindings.cameraSampling);
		var plain = PlanBindings.of(plan("main.glsl", MAIN).passes().get(0),
				List.of(sampler("cameraBack", GLES20.GL_SAMPLER_2D).withUnit(0)));
		assertNull(plain.camera);
	}

	@Test
	public void generatesMipmapsWhenOneInputOfTheImageAsks() {
		var bindings = bindings(lines(
						"#pragma se input \"a.png\"",
						"uniform sampler2D a1;",
						"#pragma se input \"a.png\" mipmap=nearest",
						"uniform sampler2D a2;",
						"#pragma se input \"b.png\" filter=nearest",
						"uniform sampler2D b;",
						MAIN),
				"a1", "a2", "b");
		assertTrue(bindings.needsMipmaps(texture("a.png", false)));
		assertFalse(bindings.needsMipmaps(texture("b.png", false)));
	}

	@Test
	public void gles3SamplesOneImageWithDifferentSettings() {
		var bindings = twoSettingsOfOneImage();
		assertTrue(bindings.check(Map.of(path("a.png"), new Size(300, 200)),
				3, MAX_SIZE, MAX_SIZE).isEmpty());
	}

	@Test
	public void gles2CannotSampleOneImageWithDifferentSettings() {
		var bindings = twoSettingsOfOneImage();
		assertEquals(List.of(error("crisp (main.glsl:1) and smooth read a.png " +
						"with different settings, which needs OpenGL ES 3.0; " +
						"this device only has OpenGL ES 2.0", 3)),
				bindings.check(Map.of(path("a.png"), new Size(256, 128)), 2,
						MAX_SIZE, MAX_SIZE));
	}

	@Test
	public void gles2ReadsOneImageWithTheSameSettingsTwice() {
		var bindings = bindings(lines(
						"#pragma se input \"a.png\" wrap=repeat",
						"uniform sampler2D a1;",
						"#pragma se input \"a.png\" wrap=repeat",
						"uniform sampler2D a2;",
						MAIN),
				"a1", "a2");
		assertTrue(bindings.check(Map.of(path("a.png"), new Size(64, 64)), 2,
				MAX_SIZE, MAX_SIZE).isEmpty());
	}

	@Test
	public void gles2ReadsNonPowerOfTwoImagesOnlyClampedWithoutMipmaps() {
		var bindings = bindings(lines(
						"#pragma se input \"a.png\" wrap=clamp,mirror",
						"uniform sampler2D mirrored;",
						"#pragma se input \"b.png\" mipmap=linear",
						"uniform sampler2D mipmapped;",
						"#pragma se input \"c.png\"",
						"uniform sampler2D clamped;",
						"#pragma se input \"d.png\" wrap=repeat mipmap=nearest",
						"uniform sampler2D square;",
						MAIN),
				"mirrored", "mipmapped", "clamped", "square");
		var sizes = Map.of(
				path("a.png"), new Size(64, 48),
				path("b.png"), new Size(100, 128),
				path("c.png"), new Size(3, 5),
				path("d.png"), new Size(128, 32));
		assertEquals(List.of(
				error("a.png is 64x48, not a power of two, so OpenGL ES 2.0 " +
						"reads it only with wrap=clamp and mipmap=none, and " +
						"this device has no OpenGL ES 3.0; resize the image or " +
						"change the settings of mirrored", 1),
				error("b.png is 100x128, not a power of two, so OpenGL ES 2.0 " +
						"reads it only with wrap=clamp and mipmap=none, and " +
						"this device has no OpenGL ES 3.0; resize the image or " +
						"change the settings of mipmapped", 3)),
				bindings.check(sizes, 2, MAX_SIZE, MAX_SIZE));
		assertTrue(bindings.check(sizes, 3, MAX_SIZE, MAX_SIZE).isEmpty());
	}

	@Test
	public void namesTheLimitOfAnImageTooLargeForTheDevice() {
		var bindings = bindings(lines(
						"#pragma se input \"big.png\"",
						"uniform sampler2D big;",
						"#pragma se input \"big.png\" wrap=repeat",
						"uniform sampler2D again;",
						MAIN),
				"big", "again");
		var expected = List.of(error("big.png is 5000x100, larger than " +
				"4096, the largest texture this device supports", 1));
		var sizes = Map.of(path("big.png"), new Size(5000, 100));
		assertEquals(expected, bindings.check(sizes, 3, MAX_SIZE, MAX_SIZE));
		assertEquals(expected, bindings.check(sizes, 2, MAX_SIZE, MAX_SIZE));
		assertTrue(bindings.check(Map.of(path("big.png"), new Size(4096, 4096)),
				3, MAX_SIZE, MAX_SIZE).isEmpty());
	}

	@Test
	public void locatesAProblemOfAnImageAtItsFirstInput() {
		var bindings = bindings(lines(
						MAIN,
						"#pragma se input \"a.png\"",
						"uniform sampler2D a;"),
				"a");
		assertEquals(error("Cannot decode a.png", 2),
				bindings.locate(path("a.png"), "Cannot decode a.png"));
	}

	@Test
	public void readsASamplerCubeImageAsACubemap() {
		var bindings = cubeBindings(lines(
				"#pragma se input \"sky.png\" mipmap=nearest",
				"uniform samplerCube sky;",
				"#pragma se input \"sky.png\"",
				"uniform sampler2D flat;",
				MAIN), "sky", "flat");
		assertEquals(texture("sky.png", true),
				bindings.images.get(0).texture());
		assertEquals(texture("sky.png", false),
				bindings.images.get(1).texture());
		assertEquals(Set.of(path("sky.png")), bindings.imagePaths());
		assertTrue(bindings.needsMipmaps(texture("sky.png", true)));
		assertFalse(bindings.needsMipmaps(texture("sky.png", false)));
	}

	@Test
	public void refusesACubemapThatIsNotAStripOfSixSquares() {
		var bindings = cubeBindings(lines(
				"#pragma se input \"sky.png\"",
				"uniform samplerCube sky;",
				MAIN), "sky");
		var expected = List.of(error("sky.png is 600x200, but a samplerCube " +
				"reads a strip of six square faces, six times as wide as " +
				"high", 1));
		assertEquals(expected, bindings.check(
				Map.of(path("sky.png"), new Size(600, 200)), 3, MAX_SIZE,
				MAX_SIZE));
		assertEquals(expected, bindings.check(
				Map.of(path("sky.png"), new Size(600, 200)), 2, MAX_SIZE,
				MAX_SIZE));
		assertTrue(bindings.check(Map.of(path("sky.png"), new Size(1200, 200)),
				3, MAX_SIZE, MAX_SIZE).isEmpty());
	}

	@Test
	public void measuresACubemapByItsFaces() {
		var bindings = cubeBindings(lines(
				"#pragma se input \"sky.png\" mipmap=linear",
				"uniform samplerCube sky;",
				MAIN), "sky");
		// The strip is wider than a 2D texture may be; its faces are not.
		assertTrue(bindings.check(Map.of(path("sky.png"), new Size(6144, 1024)),
				3, 4096, 2048).isEmpty());
		assertTrue(bindings.check(Map.of(path("sky.png"), new Size(768, 128)),
				2, MAX_SIZE, MAX_SIZE).isEmpty());
		assertEquals(List.of(error("sky.png has faces of 1024x1024, larger " +
						"than 512, the largest cubemap face this device " +
						"supports", 1)),
				bindings.check(Map.of(path("sky.png"), new Size(6144, 1024)),
						3, 4096, 512));
		assertEquals(List.of(error("sky.png has faces of 100x100, not a " +
						"power of two, so OpenGL ES 2.0 reads it only with " +
						"wrap=clamp and mipmap=none, and this device has no " +
						"OpenGL ES 3.0; resize the image or change the " +
						"settings of sky", 1)),
				bindings.check(Map.of(path("sky.png"), new Size(600, 100)),
						2, MAX_SIZE, MAX_SIZE));
	}

	@Test
	public void gles2ReadsAStripAsCubemapAndAsImageWithDifferentSettings() {
		var bindings = cubeBindings(lines(
				"#pragma se input \"sky.png\"",
				"uniform samplerCube sky;",
				"#pragma se input \"sky.png\" filter=nearest",
				"uniform sampler2D flat;",
				MAIN), "sky", "flat");
		assertTrue(bindings.check(Map.of(path("sky.png"), new Size(1536, 256)),
				2, MAX_SIZE, MAX_SIZE).isEmpty());
	}

	private static PlanBindings cubeBindings(String main, String... active) {
		RenderPlan plan = plan("main.glsl", main, "sky.png", PNG);
		var uniforms = new ArrayList<GlUniform>();
		for (int i = 0; i < active.length; ++i) {
			uniforms.add(new GlUniform(active[i], "sky".equals(active[i])
					? GLES20.GL_SAMPLER_CUBE
					: GLES20.GL_SAMPLER_2D, 1, i));
		}
		return PlanBindings.of(plan.passes().get(0),
				new GlProgram(1, uniforms).getSamplers());
	}

	private static PlanBindings.Texture texture(String image, boolean cubemap) {
		return new PlanBindings.Texture(path(image), cubemap);
	}

	private static PlanBindings twoSettingsOfOneImage() {
		return bindings(lines(
						"#pragma se input \"a.png\" filter=nearest",
						"uniform sampler2D crisp;",
						"#pragma se input \"a.png\"",
						"uniform sampler2D smooth;",
						MAIN),
				"crisp", "smooth");
	}

	// The main.glsl text with the images it names, and the sampler2D
	// uniforms the program keeps active.
	private static PlanBindings bindings(String main, String... active) {
		var files = new ArrayList<String>(List.of("main.glsl", main));
		for (String name : List.of("a.png", "b.png", "c.png", "d.png",
				"big.png")) {
			files.add(name);
			files.add(PNG);
		}
		RenderPlan plan = plan(files.toArray(new String[0]));
		var uniforms = new ArrayList<GlUniform>();
		for (int i = 0; i < active.length; ++i) {
			uniforms.add(new GlUniform(active[i], GLES20.GL_SAMPLER_2D, 1, i));
		}
		return PlanBindings.of(plan.passes().get(0),
				new GlProgram(1, uniforms).getSamplers());
	}

	private static GlUniform sampler(String name, int type) {
		return new GlUniform(name, type, 1, 0);
	}

	private static ProjectDiagnostic error(String message, int line) {
		return new ProjectDiagnostic(Severity.ERROR, message,
				path("main.glsl"), line);
	}
}
