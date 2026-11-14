package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.RenderPlan.Size;
import de.markusfisch.android.shadereditor.project.RenderPlan.Target;

public class RenderPlanTest {
	private static final ProjectPath MAIN = path("main.frag");
	private static final int MAX = 4096;

	@Test
	public void imageInputDefaultsToLinearWithoutMipmapsClamped() {
		assertEquals(new Sampling(Filter.LINEAR, Filter.LINEAR, Mipmap.NONE,
						Wrap.CLAMP, Wrap.CLAMP),
				sampling("tex", "a.png", null, null, null, null, null));
	}

	@Test
	public void passAndBackbufferInputsDefaultToNearestClamped() {
		Sampling nearest = new Sampling(Filter.NEAREST, Filter.NEAREST,
				Mipmap.NONE, Wrap.CLAMP, Wrap.CLAMP);
		assertEquals(nearest,
				sampling("state", "pass.glsl", null, null, null, null, null));
		assertEquals(nearest,
				sampling("backbuffer", null, Filter.NEAREST, null, null,
						null, null));
	}

	@Test
	public void cameraInputKeepsTheExistingDefault() {
		assertEquals(new Sampling(Filter.NEAREST, Filter.LINEAR, Mipmap.NONE,
						Wrap.CLAMP, Wrap.CLAMP),
				sampling("cameraBack", null, null, null, null, Wrap.CLAMP,
						Wrap.CLAMP));
	}

	@Test
	public void writtenOptionsReplaceOnlyTheirDefault() {
		assertEquals(new Sampling(Filter.NEAREST, Filter.LINEAR,
						Mipmap.LINEAR, Wrap.REPEAT, Wrap.MIRROR),
				sampling("tex", "a.png", Filter.NEAREST, null, Mipmap.LINEAR,
						Wrap.REPEAT, Wrap.MIRROR));
		assertEquals(new Sampling(Filter.NEAREST, Filter.LINEAR, Mipmap.NONE,
						Wrap.CLAMP, Wrap.REPEAT),
				sampling("state", "pass.glsl", null, Filter.LINEAR, null,
						null, Wrap.REPEAT));
	}

	@Test
	public void cameraInputRejectsWrapOtherThanClamp() {
		ProjectParseResult<Sampling> result = Sampling.of(input("cameraFront",
				null, Filter.LINEAR, Filter.LINEAR, null, Wrap.REPEAT,
				Wrap.CLAMP), false);
		assertFalse(result.isSuccess());
		assertEquals(MAIN, result.diagnostic().file());
		assertEquals(3, result.diagnostic().line());
	}

	@Test
	public void missingTargetIsFullScaleRgba8PresentedNearest() {
		Target target = Target.of(null, MAIN);
		assertEquals(new Target(1, 0, 0, Format.RGBA8, Filter.NEAREST, MAIN,
				0), target);
		assertFalse(target.isFixed());
	}

	@Test
	public void targetDirectiveKeepsWhatItWrote() {
		assertEquals(new Target(0.5, 0, 0, Format.RGBA8, Filter.LINEAR, MAIN,
						7),
				Target.of(new HostDirective.Target(MAIN, 7, 0.5, null, null,
						null, null, Filter.LINEAR), MAIN));
		Target fixed = Target.of(new HostDirective.Target(MAIN, 2, null, 64,
				32, Format.RGBA16F, null, null), MAIN);
		assertEquals(new Target(1, 64, 32, Format.RGBA16F, Filter.NEAREST,
				MAIN, 2), fixed);
		assertTrue(fixed.isFixed());
	}

	@Test
	public void projectSizeRoundsSurfaceTimesQuality() {
		assertEquals(new Size(540, 960), project(1080, 1920, 0.5f));
		assertEquals(new Size(501, 1), project(1001, 1, 0.5f));
		assertEquals(new Size(250, 125), project(1000, 500, 0.25f));
	}

	@Test
	public void tinyProjectSizeIsAtLeastOne() {
		assertEquals(new Size(1, 1), project(3, 1, 0.1f));
	}

	@Test
	public void rejectsInvalidSurfaceAndQuality() {
		assertFalse(Size.project(0, 10, 1, MAX).isSuccess());
		assertFalse(Size.project(10, -1, 1, MAX).isSuccess());
		assertFalse(Size.project(10, 10, 0, MAX).isSuccess());
		assertFalse(Size.project(10, 10, Float.NaN, MAX).isSuccess());
		assertFalse(Size.project(10, 10, Float.POSITIVE_INFINITY, MAX)
				.isSuccess());
	}

	@Test
	public void rejectsProjectSizeAboveTextureLimit() {
		assertFalse(Size.project(4097, 10, 1, MAX).isSuccess());
		assertEquals(new Size(4096, 5), project(8192, 10, 0.5f));
		assertFalse(Size.project(Integer.MAX_VALUE, 10, 4, MAX).isSuccess());
	}

	@Test
	public void scaledTargetFloorsWrittenDecimal() {
		Target half = scaled(0.5);
		assertEquals(new Size(250, 480), size(half, new Size(501, 961)));
		assertEquals(new Size(29, 29), size(scaled(0.29), new Size(100, 100)));
		assertEquals(new Size(100, 1), size(scaled(1), new Size(100, 1)));
	}

	@Test
	public void scaledTargetAppliesTheSixDigitDecimal() {
		assertEquals(new Size(29, 29), size(scaled(Math.nextDown(0.29)),
				new Size(100, 100)));
		assertEquals(new Size(999, 1), size(scaled(0.999999),
				new Size(1000, 1)));
	}

	@Test
	public void rejectsScaledTargetAboveTextureLimit() {
		ProjectParseResult<Size> result = Target.of(null, MAIN)
				.size(new Size(8192, 8192), MAX);
		assertFalse(result.isSuccess());
		assertEquals(MAIN, result.diagnostic().file());
		assertTrue(result.diagnostic().message().contains("8192x8192"));
	}

	@Test
	public void scaledTargetIsAtLeastOne() {
		assertEquals(new Size(1, 1), size(scaled(0.01), new Size(10, 99)));
	}

	@Test
	public void fixedTargetIgnoresQuality() {
		Target fixed = Target.of(new HostDirective.Target(MAIN, 1, null, 256,
				128, null, null, null), MAIN);
		assertEquals(new Size(256, 128), size(fixed, project(1080, 1920, 1)));
		assertEquals(new Size(256, 128),
				size(fixed, project(1080, 1920, 0.125f)));
	}

	@Test
	public void rejectsFixedTargetAboveTextureLimit() {
		Target big = Target.of(new HostDirective.Target(MAIN, 4, null, 8192,
				16, null, null, null), MAIN);
		ProjectParseResult<Size> result = big.size(new Size(100, 100), MAX);
		assertFalse(result.isSuccess());
		assertEquals(MAIN, result.diagnostic().file());
		assertEquals(4, result.diagnostic().line());
	}

	private static Sampling sampling(
			String uniform,
			String source,
			Filter min,
			Filter mag,
			Mipmap mipmap,
			Wrap wrapS,
			Wrap wrapT) {
		// The tests name pictures .png; a build reads the bytes.
		ProjectParseResult<Sampling> result = Sampling.of(input(uniform,
				source, min, mag, mipmap, wrapS, wrapT), source != null &&
				source.toString().endsWith(".png"));
		assertTrue(result.isSuccess());
		return result.value();
	}

	private static HostDirective.Input input(
			String uniform,
			String source,
			Filter min,
			Filter mag,
			Mipmap mipmap,
			Wrap wrapS,
			Wrap wrapT) {
		return new HostDirective.Input(MAIN, 3, uniform, "sampler2D",
				source != null ? path(source) : null, null, min, mag, mipmap,
				wrapS, wrapT);
	}

	private static Target scaled(double scale) {
		return Target.of(new HostDirective.Target(MAIN, 1, scale, null, null,
				null, null, null), MAIN);
	}

	private static Size project(int width, int height, float quality) {
		ProjectParseResult<Size> result = Size.project(width, height, quality,
				MAX);
		assertTrue(String.valueOf(result.diagnostic()), result.isSuccess());
		return result.value();
	}

	private static Size size(Target target, Size project) {
		ProjectParseResult<Size> result = target.size(project, MAX);
		assertTrue(result.isSuccess());
		return result.value();
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
