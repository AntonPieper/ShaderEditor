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

	@Test
	public void renderSizeIsSurfaceSizeTimesQuality() {
		assertEquals(540, EngineInputs.renderSize(1080, .5f));
		assertEquals(1080, EngineInputs.renderSize(1080, 1f));
		assertEquals(17, EngineInputs.renderSize(33, .5f));
		assertEquals(135, EngineInputs.renderSize(1080, .125f));
	}

	@Test
	public void resizeReportsOnlyChangedRenderSizes() {
		var inputs = new EngineInputs();
		inputs.restart(.5f, 0L);
		assertTrue(inputs.resize(1080, 1920, 0L));
		assertEquals(540, inputs.getRenderWidth());
		assertEquals(960, inputs.getRenderHeight());
		assertFalse(inputs.resize(1080, 1920, 0L));
		inputs.restart(1f, 0L);
		assertEquals(1080, inputs.getRenderWidth());
		assertFalse(inputs.resize(1080, 1920, 0L));

		var in = inputs.capture(0L, 3f);
		assertEquals(1080, in.surfaceWidth);
		assertEquals(1920, in.surfaceHeight);
		assertEquals(1080f, in.width, 0f);
		assertEquals(1920f, in.height, 0f);
	}

	@Test
	public void clockRestartsOnResizeAndCountsFrames() {
		var inputs = new EngineInputs();
		inputs.resize(100, 100, 1000000000L);
		var in = inputs.capture(1500000000L, 3f);
		assertEquals(.5f, in.time, EPSILON);
		assertEquals(0, in.frame);
		assertEquals(3f, in.fTimeMax, 0f);
		assertEquals(1, inputs.capture(1600000000L, 3f).frame);
		assertEquals(.1f, in.runtime, EPSILON);

		inputs.resize(100, 100, 2000000000L);
		in = inputs.capture(2000000000L, 3f);
		assertEquals(0, in.frame);
		assertEquals(0f, in.time, 0f);
		assertEquals(.1f, in.runtime, EPSILON);
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

	@Test
	public void touchIsScaledToTheRenderTarget() {
		var inputs = new EngineInputs();
		inputs.resize(200, 400, 0L);
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
		inputs.resize(100, 100, 0L);
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
		inputs.resize(100, 100, 0L);
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
		inputs.resize(100, 100, 0L);
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
		inputs.resize(800, 800, 0L);
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

	private static EngineInputs.Touch touch(int action, float... pointers) {
		return new EngineInputs.Touch(action, pointers, pointers.length / 3);
	}

	private static float[] slice(FrameInputs in) {
		float[] values = new float[in.pointerCount * 3];
		System.arraycopy(in.pointers, 0, values, 0, values.length);
		return values;
	}
}
