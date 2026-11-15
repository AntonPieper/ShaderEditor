package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import androidx.annotation.NonNull;

import org.junit.Test;

// Fit maps one range for red, green and blue to 0..1, on request, for
// the pass on the screen and its small picture; never a thumbnail.
public class FitTest {
	private static final String MAIN = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif",
			"void main(void) {",
			"	gl_FragColor = vec4(1.0);",
			"}");

	@Test
	public void lowIsBlackAndHighIsWhite() {
		var fit = new Fit(path("a.frag"), 1.8f, 14.2f);
		assertEquals(0f, shown(fit, 1.8f), 1e-6f);
		assertEquals(1f, shown(fit, 14.2f), 1e-6f);
		assertEquals(.5f, shown(fit, 8f), 1e-6f);
	}

	// A range across 0 is even around it: 0 is grey.
	@Test
	public void aRangeAcrossZeroPutsZeroAtGrey() {
		var even = new Fit(path("a.frag"), -.6f, .6f);
		assertEquals(.5f, shown(even, 0), 1e-6f);
		assertEquals(0f, shown(even, -.6f), 1e-6f);
		assertEquals(1f, shown(even, .6f), 1e-6f);
		var uneven = new Fit(path("a.frag"), -.2f, 1f);
		assertEquals(.5f, shown(uneven, 0), 1e-6f);
		assertEquals(.4f, shown(uneven, -.2f), 1e-6f);
		assertEquals(1f, shown(uneven, 1f), 1e-6f);
	}

	@Test
	public void aRangeMustHaveAWidth() {
		assertThrows(IllegalArgumentException.class,
				() -> new Fit(path("a.frag"), 1, 1));
		assertThrows(IllegalArgumentException.class,
				() -> new Fit(path("a.frag"), 0, Float.POSITIVE_INFINITY));
	}

	// Fitted, the screen is drawn by the fit's own program with its
	// mapping; without, by the surface program as always.
	@Test
	public void theScreenIsMappedOnlyWhileFitted() {
		var engine = life();
		engine.draw(0, "life.frag");
		int plain = last(engine).program();
		assertNull(last(engine).uniforms().get("scale"));
		var fit = new Fit(path("life.frag"), -1, 3);
		engine.pipeline.setFit(fit);
		engine.draw(1, "life.frag");
		var fitted = last(engine);
		assertNotEquals(plain, fitted.program());
		assertEquals(fit.scale(), fitted.uniforms().get("scale")[0], 0);
		assertEquals(fit.offset(), fitted.uniforms().get("offset")[0], 0);
		engine.pipeline.setFit(null);
		engine.draw(2, "life.frag");
		assertEquals(plain, last(engine).program());
	}

	// Only the fitted pass's picture is mapped.
	@Test
	public void onlyItsPictureIsMapped() {
		var engine = life();
		engine.draw(0);
		var fit = new Fit(path("life.frag"), 0, 10);
		int before = engine.device.draws.size();
		var inspector = new PassInspector(engine.device);
		engine.runtime.lastFrames(inspector.sources());
		inspector.pictures(fit);
		var draws = engine.device.draws.subList(before,
				engine.device.draws.size());
		// The three pictures, and the fitted pass once more as its target
		// holds it, for its facts.
		assertEquals(4, draws.size());
		assertEquals(0f, draws.get(0).uniforms().get("scale")[0], 0);
		assertEquals(fit.scale(), draws.get(1).uniforms().get("scale")[0], 0);
		assertEquals(0f, draws.get(2).uniforms().get("scale")[0], 0);
		assertEquals(0f, draws.get(3).uniforms().get("scale")[0], 0);
		assertEquals(draws.get(1).units(), draws.get(3).units());
	}

	private static float shown(@NonNull Fit fit, float value) {
		return Math.max(0, Math.min(1, value * fit.scale() + fit.offset()));
	}

	@NonNull
	private static FakeGlDevice.Draw last(@NonNull FakeEngine engine) {
		var presented = engine.presented();
		return presented.get(presented.size() - 1);
	}

	@NonNull
	private static FakeEngine life() {
		var engine = new FakeEngine(prepare(
				"main.frag", lines(
						"#pragma se input \"life.frag\"",
						"uniform sampler2D life;",
						MAIN),
				"life.frag", lines(
						"#pragma se target scale=0.5 initial=\"seed.frag\"",
						"#pragma se input \"life.frag\" frame=previous",
						"uniform sampler2D life;",
						MAIN),
				"seed.frag", MAIN), 3);
		engine.resize(8, 4);
		return engine;
	}
}
