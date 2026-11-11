package de.markusfisch.android.shadereditor.hardware;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GyroscopeListenerTest {
	private static final float EPSILON = 1e-6f;

	@Test
	public void slowTurnIntegratesItsFullAngle() {
		// 0.3 rad/s about z for one second, in 100 steps.
		float angle = 0f;
		float[] q = new float[4];
		for (int i = 0; i < 100; ++i) {
			GyroscopeListener.deltaRotation(q, 0f, 0f, .3f, .01f);
			assertEquals(1f, length(q), EPSILON);
			angle += 2f * (float) Math.atan2(q[2], q[3]);
		}
		assertEquals(.3f, angle, 1e-4f);
	}

	@Test
	public void rotatesAboutTheRateAxis() {
		float[] q = new float[4];
		GyroscopeListener.deltaRotation(q, .03f, 0f, .04f, 2f);
		// |rate| = 0.05 rad/s for 2 s: 0.1 rad about (0.6, 0, 0.8).
		float s = (float) Math.sin(.05);
		assertArrayEquals(new float[]{.6f * s, 0f, .8f * s, (float) Math.cos(.05)},
				q, EPSILON);
	}

	@Test
	public void noRateIsNoRotation() {
		float[] q = new float[4];
		GyroscopeListener.deltaRotation(q, 0f, 0f, 0f, .01f);
		assertArrayEquals(new float[]{0f, 0f, 0f, 1f}, q, 0f);
	}

	private static float length(float[] q) {
		return (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
	}
}
