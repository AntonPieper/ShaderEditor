package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import de.markusfisch.android.shadereditor.opengl.Playback.Draw;
import de.markusfisch.android.shadereditor.opengl.Playback.Swap;

// The table in Playback, row by row: what a frame draws, the clock it
// draws at, and the histories it reads, also as a tuning session meets
// a paused picture.
public class PlaybackTest {
	private static final float EPSILON = 1e-5f;
	private static final long MILLI = 1000000L;
	private static final float FIRST = EngineInputs.FIRST_TIME_DELTA;
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
	public void playingDrawsEveryFrame() {
		for (var swap : Swap.values()) {
			assertEquals(Draw.FRAME, Playback.decide(false, true, true, true,
					true, swap));
			assertEquals(Draw.FRAME, Playback.decide(false, false, false,
					false, false, swap));
		}
	}

	// A higher row wins.
	@Test
	public void pausedTheRowsInOrder() {
		// Restart, a new build or new targets: frame 0, whatever else
		// was asked, a tuned value and a build that goes on included.
		assertEquals(Draw.FIRST, paused(true, true, true, true, Swap.CONTINUED));
		assertEquals(Draw.FIRST, paused(false, false, false, false, Swap.NEW));
		assertEquals(Draw.STEP, paused(true, true, false, true, Swap.CONTINUED));
		// A tuned value, a build that goes on, one that drew nothing.
		assertEquals(Draw.AGAIN, paused(false, true, false, false, Swap.NONE));
		assertEquals(Draw.AGAIN, paused(false, false, false, false,
				Swap.CONTINUED));
		assertEquals(Draw.AGAIN, paused(false, false, false, true, Swap.NONE));
		// A pass shown, Fit, a thumbnail.
		assertEquals(Draw.PRESENT, paused(false, false, false, false,
				Swap.NONE));
	}

	// The rows' clocks: STEP is T + D, frame F + 1; AGAIN is T, F; Play
	// goes on one frame's time after.
	@Test
	public void aPausedClockStandsStepsAndGoesOn() {
		var inputs = running();
		var last = inputs.capture(20 * MILLI, 3f);
		assertEquals(.02f, last.time, EPSILON);
		assertEquals(1, last.frame);
		float delta = last.timeDelta;
		float rate = last.frameRate;
		float runtime = last.runtime;

		inputs.setPaused(true, 25 * MILLI);
		var again = inputs.capture(Draw.AGAIN, 30 * MILLI, 3f);
		assertEquals(.02f, again.time, EPSILON);
		assertEquals(1, again.frame);
		assertEquals(delta, again.timeDelta, EPSILON);
		assertEquals(rate, again.frameRate, EPSILON);
		assertEquals(runtime, again.runtime, EPSILON);
		var step = inputs.capture(Draw.STEP, 40 * MILLI, 3f);
		assertEquals(.02f + delta, step.time, EPSILON);
		assertEquals(2, step.frame);
		assertEquals(delta, step.timeDelta, EPSILON);
		assertEquals(rate, step.frameRate, EPSILON);
		assertEquals(runtime + delta, step.runtime, EPSILON);
		step = inputs.capture(Draw.STEP, 50 * MILLI, 3f);
		assertEquals(.02f + 2 * delta, step.time, EPSILON);
		assertEquals(3, step.frame);

		// A minute later it goes on from there, one frame's time on.
		inputs.setPaused(false, 60_000 * MILLI);
		var on = inputs.capture(60_000 * MILLI, 3f);
		assertEquals(.02f + 3 * delta, on.time, EPSILON);
		assertEquals(4, on.frame);
		assertEquals(delta, on.timeDelta, EPSILON);
	}

	// F7: Restart while paused is frame 0 at time 0, and the next Step
	// is frame 1, also with a tuned value asked for meanwhile.
	@Test
	public void aRestartWhilePausedIsFrameZeroThenOne() {
		var inputs = running();
		inputs.capture(16 * MILLI, 3f);
		inputs.setPaused(true, 20 * MILLI);
		inputs.restart(1f, 30 * MILLI);
		assertTrue(inputs.restarted());
		var draw = Playback.decide(true, false, true, inputs.restarted(),
				false, Swap.CONTINUED);
		assertEquals(Draw.FIRST, draw);
		var first = inputs.capture(draw, 40 * MILLI, 3f);
		assertEquals(0f, first.time, EPSILON);
		assertEquals(0, first.frame);
		assertEquals(FIRST, first.timeDelta, EPSILON);
		assertFalse(inputs.restarted());
		// The frame the sheet shows while paused follows the table.
		assertEquals(0, inputs.lastFrame());
		var next = inputs.capture(Draw.STEP, 50 * MILLI, 3f);
		assertEquals(FIRST, next.time, EPSILON);
		assertEquals(1, next.frame);
		assertEquals(1, inputs.lastFrame());
		inputs.capture(Draw.AGAIN, 60 * MILLI, 3f);
		assertEquals(1, inputs.lastFrame());
	}

	// F8: the rate is counted from the first frame after Play, not over
	// the time the picture stood after a restart.
	@Test
	public void theRateAfterPlayDoesNotCountTheStoppedTime() {
		var inputs = running();
		for (int i = 1; i <= 40; ++i) {
			inputs.capture(i * 16 * MILLI, 3f);
		}
		inputs.setPaused(true, 700 * MILLI);
		inputs.restart(1f, 800 * MILLI);
		inputs.capture(Draw.FIRST, 810 * MILLI, 3f);
		inputs.setPaused(false, 30_000 * MILLI);
		// Each frame after Play says a rate near the one it has, the
		// first one too: never one frame over the 29 s it stood.
		for (int i = 0; i <= 40; ++i) {
			float rate = inputs.capture(30_000 * MILLI + i * 16 * MILLI, 3f)
					.frameRate;
			assertTrue(i + ": " + rate, rate > 50 && rate < 70);
		}
	}

	// F9: a new shader set while paused starts at its own runtime, not at
	// the one before it.
	@Test
	public void aNewShaderWhilePausedHasItsOwnRuntime() {
		var inputs = running();
		var owner = new Object();
		inputs.showRuntime(owner, 0);
		for (int i = 1; i <= 60; ++i) {
			inputs.capture(i * 16 * MILLI, 3f);
		}
		assertTrue(inputs.capture(61 * 16 * MILLI, 3f).runtime > .5f);
		inputs.setPaused(true, 1000 * MILLI);
		var next = new Object();
		inputs.showRuntime(next, 2 * 1000 * MILLI);
		inputs.restart(1f, 1100 * MILLI);
		var first = inputs.capture(Draw.FIRST, 1200 * MILLI, 3f);
		assertEquals(2f, first.runtime, EPSILON);
		var step = inputs.capture(Draw.STEP, 1300 * MILLI, 3f);
		assertEquals(2f + FIRST, step.runtime, EPSILON);
	}

	// F6: drawn again, frame F reads the histories frame F read, those
	// from before it, and replaces itself; the next Step reads it.
	@Test
	public void aFrameDrawnAgainReadsTheHistoryBeforeIt() {
		var engine = life();
		engine.draw(0);
		engine.draw(1);
		int target = last(engine, "life.frag");
		int read = read(engine);
		redraw(engine);
		assertEquals(target, last(engine, "life.frag"));
		assertEquals(read, read(engine));
		redraw(engine);
		assertEquals(target, last(engine, "life.frag"));
		assertEquals(read, read(engine));
		// The next frame goes on from the frame drawn again.
		engine.draw(2);
		assertEquals(target, read(engine));
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
	private static Draw paused(boolean step, boolean again,
			boolean restarted, boolean needsFrame, @NonNull Swap swap) {
		return Playback.decide(true, step, again, restarted, needsFrame,
				swap);
	}

	// A clock that drew frame 0 at time 0.
	@NonNull
	private static EngineInputs running() {
		var inputs = new EngineInputs();
		inputs.resize(8, 8);
		inputs.restart(1f, 0);
		inputs.capture(0, 3f);
		return inputs;
	}

	private static void redraw(@NonNull FakeEngine engine) {
		engine.runtime.draw(engine.pipeline, engine.builtins(), engine.frame,
				engine.runtime.shown(null), true);
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
