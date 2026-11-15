package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective;

// Facts of a pass, never verdicts: whether it draws and what the build
// says of it, its size and format, one value everywhere, the range of a
// float pass and NaN or infinity in it, and how long it stayed the same.
public class PassFactsTest {
	private static final String MAIN = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif",
			"void main(void) {",
			"	gl_FragColor = vec4(1.0);",
			"}");

	// A black pass is said to be black, as a mask of 0 and 1 is said
	// to be neither.
	@Test
	public void oneValueEverywhereIsSaidWithTheValue() {
		int black = 0xff000000;
		assertArrayEquals(new float[]{0, 0, 0, 1},
				PassInspector.same(new int[]{black, black, black}), 0);
		assertNull(PassInspector.same(new int[]{black, 0xffffffff}));
		assertArrayEquals(new float[]{1, 128 / 255f, 0, 0},
				PassInspector.same(new int[]{0x00ff8000}), 1e-6f);
	}

	@Test
	public void aFloatPassHasItsRangePerChannel() {
		var facts = floatFacts(
				1.8f, -0.6f, 0f, 1f,
				14.2f, 0.6f, 0f, 1f);
		assertArrayEquals(new float[]{1.8f, -0.6f, 0, 1}, facts.low(), 0);
		assertArrayEquals(new float[]{14.2f, 0.6f, 0, 1}, facts.high(), 0);
		assertFalse(facts.nonFinite());
		assertNull(facts.same());
		assertEquals(PassInspector.FLOAT_SAMPLE, facts.sample());
	}

	// NaN and infinity are said, and left out of the range.
	@Test
	public void nanAndInfinityAreSaid() {
		var facts = floatFacts(
				Float.NaN, 2f, 0f, 1f,
				0.5f, Float.POSITIVE_INFINITY, 0f, 1f);
		assertTrue(facts.nonFinite());
		assertArrayEquals(new float[]{.5f, 2, 0, 1}, facts.low(), 0);
		assertArrayEquals(new float[]{.5f, 2, 0, 1}, facts.high(), 0);
		var everywhere = floatFacts(3f, 3f, 3f, 1f, 3f, 3f, 3f, 1f);
		assertArrayEquals(new float[]{3, 3, 3, 1}, everywhere.same(), 0);
	}

	// One fact a row; a pass that does not change says for how long.
	@Test
	public void everyRowHasItsFacts() {
		var engine = life(HostDirective.Format.RGBA8, 3);
		var inspector = new PassInspector(engine.device);
		engine.draw(0);
		var facts = facts(engine, inspector, 0);
		assertEquals(3, facts.size());
		var life = facts.get(1);
		assertEquals(path("life.frag"), life.file());
		assertTrue(life.drawn());
		assertEquals(4, life.width());
		assertEquals(2, life.height());
		assertEquals(HostDirective.Format.RGBA8, life.format());
		assertEquals(PassInspector.PICTURE, life.sample());
		assertNull(life.low());
		assertEquals(0, facts.get(2).unchanged());
		// main.frag draws into one texture, which the fake reads the same
		// each time: unchanged for a second. life.frag swaps: changed.
		engine.draw(1);
		facts = facts(engine, inspector, 1_000_000_000L);
		assertEquals(1_000_000_000L, facts.get(2).unchanged());
		assertEquals(0, facts.get(1).unchanged());
	}

	// A float pass is sampled in its own values in OpenGL ES 3, from its
	// 8-bit picture in OpenGL ES 2.
	@Test
	public void aFloatPassIsSampledInEs3Only() {
		var engine = life(HostDirective.Format.RGBA16F, 3);
		engine.draw(0);
		var life = facts(engine, new PassInspector(engine.device), 0).get(1);
		assertEquals(HostDirective.Format.RGBA16F, life.format());
		assertEquals(PassInspector.FLOAT_SAMPLE, life.sample());
		var es2 = new PassInspector(engine.device).facts(
				engine.runtime.running(), engine.runtime.lastFrames(),
				new PassInspector(engine.device).pictures(
						engine.runtime.lastFrames()), 0, false).get(1);
		assertEquals(PassInspector.PICTURE, es2.sample());
		assertNull(es2.low());
	}

	@NonNull
	private static PassFacts floatFacts(float... values) {
		var bits = new int[values.length];
		for (int i = 0; i < values.length; ++i) {
			bits[i] = Float.floatToRawIntBits(values[i]);
		}
		var row = new RunningPasses.Row(path("a.frag"),
				RunningPasses.Kind.PASS, false, true, List.of(), List.of(),
				null, List.of());
		return PassInspector.floatFacts(row, new PassInspector.Source(
				path("a.frag"), new GlTexture2D(1), 8, 8,
				HostDirective.Format.RGBA16F), bits, 0);
	}

	@NonNull
	private static List<PassFacts> facts(@NonNull FakeEngine engine,
			@NonNull PassInspector inspector, long now) {
		var sources = engine.runtime.lastFrames();
		var facts = inspector.facts(engine.runtime.running(), sources,
				inspector.pictures(sources), now, true);
		assertNotNull(facts);
		return facts;
	}

	@NonNull
	private static FakeEngine life(@NonNull HostDirective.Format format,
			int glesVersion) {
		var engine = new FakeEngine(prepare(
				"main.frag", lines(
						"#pragma se input \"life.frag\"",
						"uniform sampler2D life;",
						MAIN),
				"life.frag", lines(
						"#pragma se target scale=0.5 initial=\"seed.frag\" " +
								"format=" + format.name().toLowerCase(
										java.util.Locale.ROOT),
						"#pragma se input \"life.frag\" frame=previous",
						"uniform sampler2D life;",
						MAIN),
				"seed.frag", MAIN), glesVersion);
		engine.resize(8, 4);
		return engine;
	}
}
