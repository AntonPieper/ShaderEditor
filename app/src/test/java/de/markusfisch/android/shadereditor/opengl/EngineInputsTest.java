package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class EngineInputsTest {
	private static final float EPSILON = 1e-6f;
	private static final long SECOND = 1000000000L;
	private static final long MILLI = 1000000L;

	@Test
	public void renderSizeIsSurfaceSizeTimesQuality() {
		assertEquals(540, EngineInputs.renderSize(1080, .5f));
		assertEquals(1080, EngineInputs.renderSize(1080, 1f));
		assertEquals(17, EngineInputs.renderSize(33, .5f));
		assertEquals(135, EngineInputs.renderSize(1080, .125f));
	}

	// At least one pixel per axis, however small the surface.
	@Test
	public void aTinySurfaceRendersAtLeastOnePixel() {
		assertEquals(1, EngineInputs.renderSize(1, .25f));
		assertEquals(1, EngineInputs.renderSize(3, .125f));
		var inputs = new EngineInputs();
		inputs.resize(1, 100);
		inputs.restart(.25f, 0L);
		assertEquals(1, inputs.getRenderWidth());
		assertEquals(25, inputs.getRenderHeight());
	}

	@Test
	public void resizeReportsOnlyChangedRenderSizes() {
		var inputs = new EngineInputs();
		inputs.restart(.5f, 0L);
		assertTrue(inputs.resize(1080, 1920));
		assertEquals(540, inputs.getRenderWidth());
		assertEquals(960, inputs.getRenderHeight());
		assertFalse(inputs.resize(1080, 1920));
		inputs.restart(1f, 0L);
		assertEquals(1080, inputs.getRenderWidth());
		assertFalse(inputs.resize(1080, 1920));

		var in = inputs.capture(0L, 3f);
		assertEquals(1080, in.surfaceWidth);
		assertEquals(1920, in.surfaceHeight);
		assertEquals(1080f, in.width, 0f);
		assertEquals(1920f, in.height, 0f);
	}

	// The clock restarts with the histories, which a resize keeps when
	// no target changes its size.
	@Test
	public void clockRestartsWhenToldNotOnResizeAndCountsFrames() {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		inputs.restart(1000000000L);
		var in = inputs.capture(1500000000L, 3f);
		assertEquals(.5f, in.time, EPSILON);
		assertEquals(0, in.frame);
		assertEquals(3f, in.fTimeMax, 0f);
		assertEquals(1, inputs.capture(1600000000L, 3f).frame);
		assertEquals(.1f, in.runtime, EPSILON);

		assertFalse(inputs.resize(100, 100));
		in = inputs.capture(1700000000L, 3f);
		assertEquals(2, in.frame);
		assertEquals(.7f, in.time, EPSILON);

		inputs.restart(2000000000L);
		in = inputs.capture(2000000000L, 3f);
		assertEquals(0, in.frame);
		assertEquals(0f, in.time, 0f);
		assertEquals(.2f, in.runtime, EPSILON);
	}

	// A drawing, B set: A keeps its clock while B decodes or when B
	// fails, and B's restored runtime starts when B is shown.
	@Test
	public void runtimeBelongsToTheShaderShown() {
		var inputs = new EngineInputs();
		var a = new Object();
		var b = new Object();
		assertEquals(-1L, inputs.getRuntimeNanos(a));
		inputs.showRuntime(a, 0L);
		inputs.capture(1000000000L, 3f);
		inputs.capture(3000000000L, 3f);
		assertEquals(2000000000L, inputs.getRuntimeNanos(a));
		assertEquals(-1L, inputs.getRuntimeNanos(b));

		inputs.capture(4000000000L, 3f);
		inputs.showRuntime(a, 0L);
		inputs.capture(5000000000L, 3f);
		assertEquals(4000000000L, inputs.getRuntimeNanos(a));

		inputs.showRuntime(b, 7000000000L);
		assertEquals(-1L, inputs.getRuntimeNanos(a));
		inputs.capture(6000000000L, 3f);
		assertEquals(7000000000L, inputs.getRuntimeNanos(b));
		assertEquals(7.5f, inputs.capture(6500000000L, 3f).runtime, EPSILON);
	}

	// A touch stays where it was on the surface when the quality or the
	// size changes without another touch event, and so does a pass's
	// pixel coordinate of it: (50, 25) on a 100x100 surface is
	// (128, 192) in a 256x256 pass at any quality.
	@Test
	public void aTouchKeepsItsPlaceWhenQualityOrSizeChange() {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		inputs.restart(1f, 0L);
		var before = inputs.capture(0L, 3f);
		assertEquals(0f, before.touchY, 0f);
		assertEquals(0f, before.touchStartY, 0f);
		assertEquals(0f, before.mouseY, 0f);
		inputs.post(touch(MotionEvent.ACTION_DOWN, 50f, 25f, 5f));
		var pass = new FrameInputs();
		pass.scale(inputs.capture(0L, 3f), 256, 256);
		assertEquals(128f, pass.touchX, 1e-4f);
		assertEquals(192f, pass.touchY, 1e-4f);
		inputs.restart(.5f, 0L);
		var half = inputs.capture(0L, 3f);
		assertEquals(25f, half.touchX, 1e-4f);
		assertEquals(37.5f, half.touchY, 1e-4f);
		assertEquals(.5f, half.mouseX, 1e-4f);
		pass.scale(half, 256, 256);
		assertEquals(128f, pass.touchX, 1e-4f);
		assertEquals(192f, pass.touchY, 1e-4f);
		assertEquals(128f, pass.touchStartX, 1e-4f);
		assertEquals(192f, pass.touchStartY, 1e-4f);
		assertEquals(128f, pass.pointers[0], 1e-4f);
		assertEquals(192f, pass.pointers[1], 1e-4f);
		inputs.restart(1f, 0L);
		inputs.resize(100, 200);
		var taller = inputs.capture(0L, 3f);
		assertEquals(175f, taller.touchY, 1e-4f);
		assertEquals(175f, taller.touchStartY, 1e-4f);
		assertEquals(175f, taller.pointers[1], 1e-4f);
	}

	@Test
	public void touchIsScaledToTheRenderTarget() {
		var inputs = new EngineInputs();
		inputs.resize(200, 400);
		inputs.restart(.5f, 0L);

		inputs.post(touch(MotionEvent.ACTION_DOWN, 20f, 40f, 5f));
		var in = inputs.capture(0L, 3f);
		assertEquals(10f, in.touchX, 0f);
		assertEquals(180f, in.touchY, 0f);
		assertEquals(10f, in.touchStartX, 0f);
		assertEquals(180f, in.touchStartY, 0f);
		assertEquals(.1f, in.mouseX, EPSILON);
		assertEquals(.9f, in.mouseY, EPSILON);
		assertEquals(1, in.pointerCount);
		assertArrayEquals(new float[]{10f, 180f, 5f}, slice(in), 0f);

		inputs.post(touch(MotionEvent.ACTION_MOVE,
				60f, 80f, 6f,
				100f, 120f, 7f));
		in = inputs.capture(0L, 3f);
		assertEquals(30f, in.touchX, 0f);
		assertEquals(10f, in.touchStartX, 0f);
		assertEquals(2, in.pointerCount);
		assertArrayEquals(new float[]{30f, 160f, 6f, 50f, 140f, 7f}, slice(in), 0f);

		inputs.post(touch(MotionEvent.ACTION_UP, 80f, 80f, 6f));
		in = inputs.capture(0L, 3f);
		assertEquals(0, in.pointerCount);
		assertEquals(40f, in.touchX, 0f);
		assertEquals(160f, in.touchY, 0f);
	}

	@Test
	public void inputPostedFromAnotherThreadAppearsInTheNextFrame() throws Exception {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		var poster = new Thread(() -> {
			inputs.post(touch(MotionEvent.ACTION_DOWN, 1f, 2f, 3f, 4f, 5f, 6f));
			inputs.postOffset(.25f, .5f);
		});
		poster.start();
		poster.join();

		var in = inputs.capture(0L, 3f);
		assertEquals(2, in.pointerCount);
		assertArrayEquals(new float[]{1f, 98f, 3f, 4f, 95f, 6f}, slice(in), 0f);
		assertEquals(.25f, in.offsetX, 0f);
		assertEquals(.5f, in.offsetY, 0f);
	}

	@Test
	public void twoPostsBetweenFramesLeaveTheLast() {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		inputs.postOffset(.1f, .2f);
		inputs.postOffset(.3f, .4f);
		inputs.post(touch(MotionEvent.ACTION_DOWN, 1f, 1f, 1f, 2f, 2f, 2f));
		inputs.post(touch(MotionEvent.ACTION_MOVE, 5f, 5f, 5f));

		var in = inputs.capture(0L, 3f);
		assertEquals(.3f, in.offsetX, 0f);
		assertEquals(.4f, in.offsetY, 0f);
		assertEquals(1, in.pointerCount);
		assertArrayEquals(new float[]{5f, 95f, 5f}, slice(in), 0f);
		assertEquals(1f, in.touchStartX, 0f);
	}

	@Test
	public void pointerCountNeverDisagreesWithThePointers() throws Exception {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		var touches = new EngineInputs.Touch[FrameInputs.MAX_POINTERS];
		for (int count = 1; count <= touches.length; ++count) {
			float[] pointers = new float[count * 3];
			for (int i = 0; i < pointers.length; i += 3) {
				pointers[i] = count;
				pointers[i + 1] = 100 - count;
				pointers[i + 2] = count;
			}
			touches[count - 1] = new EngineInputs.Touch(
					MotionEvent.ACTION_MOVE, pointers, count);
		}
		var done = new AtomicBoolean();
		var poster = new Thread(() -> {
			for (int i = 0; !done.get(); ++i) {
				inputs.post(touches[i % touches.length]);
			}
		});
		poster.start();
		try {
			for (int frame = 0; frame < 100000; ++frame) {
				var in = inputs.capture(frame, 3f);
				for (int i = 0; i < in.pointerCount * 3; ++i) {
					assertEquals(in.pointerCount, in.pointers[i], 0f);
				}
			}
		} finally {
			done.set(true);
			poster.join();
		}
	}

	// A pointer posted while the quality changes is scaled with the old
	// quality and size or with the new ones, never with one of each.
	@Test
	public void qualityAndRenderSizeChangeTogether() throws Exception {
		var inputs = new EngineInputs();
		inputs.resize(800, 800);
		inputs.post(touch(MotionEvent.ACTION_DOWN, 100f, 100f, 1f));
		var done = new AtomicBoolean();
		var poster = new Thread(() -> {
			while (!done.get()) {
				inputs.post(touch(MotionEvent.ACTION_MOVE, 100f, 100f, 1f));
			}
		});
		poster.start();
		try {
			for (int frame = 0; frame < 200000; ++frame) {
				inputs.restart(frame % 2 == 0 ? .5f : 1f, frame);
				float touchY = inputs.capture(frame, 3f).touchY;
				assertTrue("touchY " + touchY, touchY == 700f || touchY == 350f);
			}
		} finally {
			done.set(true);
			poster.join();
		}
	}

	// A touch that began is told to one frame, the first after it,
	// also when it ended before that frame.
	@Test
	public void oneFrameIsToldThatATouchBegan() {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		inputs.restart(1f, 0L);
		assertFalse(inputs.capture(0L, 3f).touchBegan);
		inputs.post(touch(MotionEvent.ACTION_DOWN, 10f, 20f, 1f));
		inputs.post(touch(MotionEvent.ACTION_MOVE, 30f, 40f, 1f));
		var in = inputs.capture(1L, 3f);
		assertTrue(in.touchBegan);
		assertEquals(1, in.pointerCount);
		var pass = new FrameInputs();
		pass.scale(in, 50, 50);
		assertTrue(pass.touchBegan);
		in = inputs.capture(2L, 3f);
		assertFalse(in.touchBegan);
		assertEquals(1, in.pointerCount);
		inputs.post(touch(MotionEvent.ACTION_UP, 30f, 40f, 1f));
		assertFalse(inputs.capture(3L, 3f).touchBegan);
		// Down and up between two frames.
		inputs.post(touch(MotionEvent.ACTION_DOWN, 50f, 60f, 1f));
		inputs.post(touch(MotionEvent.ACTION_UP, 50f, 60f, 1f));
		in = inputs.capture(4L, 3f);
		assertTrue(in.touchBegan);
		assertEquals(0, in.pointerCount);
		assertEquals(50f, in.touchStartX, 0f);
		assertFalse(inputs.capture(5L, 3f).touchBegan);
	}

	// What the frame before took: the time between two frames, 1/60 s
	// where there is no frame before, and never a long step.
	@Test
	public void timeDeltaIsTheTimeSinceTheFrameBefore() {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		inputs.restart(5 * SECOND);
		// The first frame, however late it comes.
		assertEquals(1f / 60f, inputs.capture(8 * SECOND, 3f).timeDelta,
				EPSILON);
		assertEquals(.02f, inputs.capture(8 * SECOND + 20 * MILLI, 3f)
				.timeDelta, EPSILON);
		assertEquals(.008f, inputs.capture(8 * SECOND + 28 * MILLI, 3f)
				.timeDelta, EPSILON);
		// Frames that stopped coming without a word: half a second at
		// most.
		assertEquals(.5f, inputs.capture(60 * SECOND, 3f).timeDelta, EPSILON);
		// A resize that keeps the targets keeps the clock.
		assertFalse(inputs.resize(100, 100));
		assertEquals(.1f, inputs.capture(60 * SECOND + 100 * MILLI, 3f)
				.timeDelta, EPSILON);
		// After a pause, and after a restart, as on the first frame.
		inputs.pause();
		assertEquals(1f / 60f, inputs.capture(90 * SECOND, 3f).timeDelta,
				EPSILON);
		assertEquals(.016f, inputs.capture(90 * SECOND + 16 * MILLI, 3f)
				.timeDelta, EPSILON);
		inputs.restart(.5f, 91 * SECOND);
		assertEquals(1f / 60f, inputs.capture(91 * SECOND + 300 * MILLI, 3f)
				.timeDelta, EPSILON);
	}

	// 60 until frames were counted for more than half a second, then
	// the frames of that time by its length, and so on. It is no
	// 1 / timeDelta.
	@Test
	public void frameRateIsCountedOverHalfASecond() {
		var inputs = new EngineInputs();
		inputs.resize(100, 100);
		inputs.restart(0L);
		long now = 0;
		// 30 frames a second: 15 frames reach half a second, the 16th
		// is past it.
		for (int i = 0; i < 15; ++i) {
			now += SECOND / 30;
			assertEquals(60f, inputs.capture(now, 3f).frameRate, 0f);
		}
		now += SECOND / 30;
		assertEquals(30f, inputs.capture(now, 3f).frameRate, 1e-3f);
		// One slow frame does not change it before the next count.
		now += 400 * MILLI;
		var in = inputs.capture(now, 3f);
		assertEquals(30f, in.frameRate, 1e-3f);
		assertEquals(.4f, in.timeDelta, EPSILON);
		// 2 frames in 600 ms.
		now += 200 * MILLI;
		assertEquals(2f / .6f, inputs.capture(now, 3f).frameRate, 1e-3f);
		// A pause is not counted: the rate stays, and frames are
		// counted from the first that comes.
		inputs.pause();
		now += 100 * SECOND;
		assertEquals(2f / .6f, inputs.capture(now, 3f).frameRate, 1e-3f);
		for (int i = 0; i < 60; ++i) {
			now += SECOND / 120;
			inputs.capture(now, 3f);
		}
		now += SECOND / 120;
		assertEquals(120f, inputs.capture(now, 3f).frameRate, 1e-2f);
		// A restart starts at 60 again.
		inputs.restart(now);
		assertEquals(60f, inputs.capture(now + MILLI, 3f).frameRate, 0f);
	}

	private static EngineInputs.Touch touch(int action, float... pointers) {
		return new EngineInputs.Touch(action, pointers, pointers.length / 3);
	}

	private static float[] slice(FrameInputs in) {
		float[] values = new float[in.pointerCount * 3];
		System.arraycopy(in.pointers, 0, values, 0, values.length);
		return values;
	}
}
