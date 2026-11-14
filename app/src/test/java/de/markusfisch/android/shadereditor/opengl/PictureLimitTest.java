package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PictureLimitTest {
	private static final float DELTA = 1e-3f;

	@Test
	public void whiteLandsOnTheCeiling() {
		for (int ceiling : new int[]{PictureLimit.DIM, PictureLimit.BALANCED,
				PictureLimit.BRIGHT, PictureLimit.NONE}) {
			assertEquals(ceiling / 255f, gray(1f, ceiling), 1e-6f);
		}
	}

	@Test
	public void darkPartsPassAndBrightPartsArePulledDown() {
		assertEquals(.0417f, gray(.05f, PictureLimit.BALANCED), DELTA);
		assertEquals(.111f, gray(.2f, PictureLimit.BALANCED), DELTA);
		assertEquals(.167f, gray(.5f, PictureLimit.BALANCED), DELTA);
		assertEquals(.5f, gray(.5f, PictureLimit.NONE), 0f);
		float previous = 0f;
		for (int i = 1; i <= 255; ++i) {
			float shown = gray(i / 255f, PictureLimit.BALANCED);
			assertTrue(shown > previous);
			previous = shown;
		}
	}

	// An rgba16f output holds values above 1, which the ceiling bounds
	// as it bounds white.
	@Test
	public void valuesAboveOneStayUnderTheCeiling() {
		for (int ceiling : new int[]{PictureLimit.DIM, PictureLimit.BALANCED,
				PictureLimit.BRIGHT}) {
			for (float value : new float[]{1.5f, 4f, 1000f}) {
				assertEquals(ceiling / 255f, gray(value, ceiling), 1e-6f);
			}
		}
		float strength = PictureLimit.strength(PictureLimit.BALANCED / 255f);
		assertEquals(0f, PictureLimit.apply(-2f, 4f, strength), 0f);
	}

	@Test
	public void theHueIsKept() {
		float strength = PictureLimit.strength(PictureLimit.DIM / 255f);
		float r = PictureLimit.apply(.9f, .9f, strength);
		float g = PictureLimit.apply(.3f, .9f, strength);
		assertEquals(3f, r / g, 1e-5f);
	}

	private static float gray(float value, int ceiling) {
		return PictureLimit.apply(value, value, PictureLimit.strength(ceiling / 255f));
	}
}
