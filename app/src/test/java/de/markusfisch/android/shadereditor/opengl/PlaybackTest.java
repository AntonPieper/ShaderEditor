package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import de.markusfisch.android.shadereditor.opengl.Playback.Draw;
import de.markusfisch.android.shadereditor.opengl.Playback.Swap;

// Pause, Step and Restart: what a frame draws, the clock it draws at,
// and how a tuning session's builds and values meet a paused picture.
public class PlaybackTest {
	private static final float EPSILON = 1e-5f;
	private static final long MILLI = 1000000L;
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
	public void runningDrawsEveryFrame() {
		for (var swap : Swap.values()) {
			assertEquals(Draw.FRAME, Playback.decide(false, false, false,
					false, swap));
			assertEquals(Draw.FRAME, Playback.decide(false, true, true,
					true, swap));
		}
	}

	@Test
	public void pausedDrawsOnlyWhatIsAskedFor() {
		// Showing another pass, a thumbnail: presented only.
		assertEquals(Draw.PRESENT, decide(false, false, false, Swap.NONE));
		assertEquals(Draw.STEP, decide(true, false, false, Swap.NONE));
		// Restart or new targets: frame 0 is drawn.
		assertEquals(Draw.STEP, decide(false, false, true, Swap.NONE));
	}

	// The tuning session meets a paused picture.
	@Test
	public void pausedTuningRedrawsTheFrameDrawnLast() {
		// A value set: the same frame again.
		assertEquals(Draw.AGAIN, decide(false, true, false, Swap.NONE));
		// Its builds go on from the picture: the same frame again.
		assertEquals(Draw.AGAIN, decide(false, false, false,
				Swap.CONTINUED));
		assertEquals(Draw.AGAIN, decide(false, false, true, Swap.CONTINUED));
		// An ordinary edit starts again: its frame 0.
		assertEquals(Draw.STEP, decide(false, false, false, Swap.NEW));
		// A step asked for with a value set draws the next frame with it.
		assertEquals(Draw.STEP, decide(true, true, false, Swap.NONE));
	}

	@Test
	public void aPausedClockStandsStepsAndGoesOn() {
		var inputs = new EngineInputs();
		inputs.resize(8, 8);
		inputs.restart(1f, 0);
		inputs.capture(0, 3f);
		var last = inputs.capture(20 * MILLI, 3f);
		assertEquals(.02f, last.time, EPSILON);
		assertEquals(1, last.frame);
		float delta = last.timeDelta;

		inputs.setPaused(true, 25 * MILLI);
		var again = inputs.captureAgain(3f);
		assertEquals(.02f, again.time, EPSILON);
		assertEquals(1, again.frame);
		// Each step one frame's time and one frame on.
		var step = inputs.captureStep(3f);
		assertEquals(.02f + delta, step.time, EPSILON);
		assertEquals(2, step.frame);
		assertEquals(delta, step.timeDelta, EPSILON);
		step = inputs.captureStep(3f);
		assertEquals(.02f + 2 * delta, step.time, EPSILON);
		assertEquals(3, step.frame);

		// A minute later it goes on from there, one frame's time on.
		inputs.setPaused(false, 60_000 * MILLI);
		var on = inputs.capture(60_000 * MILLI, 3f);
		assertEquals(.02f + 3 * delta, on.time, EPSILON);
		assertEquals(4, on.frame);
		assertEquals(delta, on.timeDelta, EPSILON);
	}

	@Test
	public void aRestartWhilePausedStepsToFrameZero() {
		var inputs = new EngineInputs();
		inputs.resize(8, 8);
		inputs.restart(1f, 0);
		inputs.capture(0, 3f);
		inputs.capture(16 * MILLI, 3f);
		inputs.setPaused(true, 20 * MILLI);
		inputs.restart(1f, 30 * MILLI);
		var first = inputs.captureStep(3f);
		assertEquals(0f, first.time, EPSILON);
		assertEquals(0, first.frame);
		assertEquals(1, inputs.captureStep(3f).frame);
	}

	// Drawn again, a frame reads the same last frame as often as it is
	// drawn, and the next frame goes on from that last frame.
	@Test
	public void aFrameDrawnAgainKeepsTheHistory() {
		var engine = life();
		engine.draw(0);
		engine.draw(1);
		int drawn = last(engine, "life.frag");
		redraw(engine);
		// It reads the frame drawn last, as the next frame would.
		int previous = read(engine);
		assertEquals(drawn, previous);
		redraw(engine);
		assertEquals(previous, read(engine));
		// The redraw did not move it on: the next frame reads the same.
		engine.draw(2);
		assertEquals(previous, read(engine));
		engine.draw(3);
		assertNotEquals(previous, read(engine));
	}

	// Paused, what the frame drawn last drew is presented, drawing
	// nothing, of the output or of a pass shown.
	@Test
	public void presentingDrawsNoPass() {
		var engine = life();
		assertTrue(engine.runtime.needsFrame());
		engine.draw(0);
		assertFalse(engine.runtime.needsFrame());
		int life = last(engine, "life.frag");
		int main = last(engine, "main.frag");
		int draws = engine.device.draws.size();
		engine.runtime.present(engine.pipeline, 8, 4,
				engine.runtime.shown(TestProjects.path("life.frag")));
		assertEquals(draws + 1, engine.device.draws.size());
		assertEquals(life, presented(engine));
		engine.runtime.present(engine.pipeline, 8, 4,
				engine.runtime.shown(null));
		assertEquals(main, presented(engine));
		assertEquals(main, engine.pipeline.picture().getId());
	}

	// Restart draws the first frames again, without a build.
	@Test
	public void restartDrawsTheFirstFramesAgain() {
		var engine = life();
		engine.draw(0);
		engine.draw(1);
		assertEquals(1, engine.draws("seed.frag").size());
		engine.runtime.restart();
		assertTrue(engine.runtime.needsFrame());
		engine.draw(2);
		assertEquals(2, engine.draws("seed.frag").size());
	}

	@NonNull
	private static Draw decide(boolean step, boolean again,
			boolean needsFrame, @NonNull Swap swap) {
		return Playback.decide(true, step, again, needsFrame, swap);
	}

	private static void redraw(@NonNull FakeEngine engine) {
		engine.runtime.draw(engine.pipeline, engine.builtins(), engine.frame,
				engine.runtime.shown(null), false);
	}

	// The texture life.frag read as its last frame in its last draw.
	private static int read(@NonNull FakeEngine engine) {
		var draws = engine.draws("life.frag");
		var draw = draws.get(draws.size() - 1);
		return draw.units().get(engine.unit("life.frag", "life"));
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

	private static int last(@NonNull FakeEngine engine, @NonNull String file) {
		var draws = engine.draws(file);
		return draws.get(draws.size() - 1).target();
	}

	private static int presented(@NonNull FakeEngine engine) {
		var presented = engine.presented();
		var draw = presented.get(presented.size() - 1);
		return draw.units().get(engine.stretchUnit(draw));
	}
}
