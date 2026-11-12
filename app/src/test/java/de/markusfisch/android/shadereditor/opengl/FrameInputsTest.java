package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameInputsTest {
	private static final float DELTA = 1e-4f;

	@Test
	public void aPassSeesItsOwnSizeAndPixelPositionsScaledToIt() {
		var project = new FrameInputs();
		project.width = 1080;
		project.height = 1920;
		project.surfaceWidth = 1080;
		project.surfaceHeight = 1920;
		project.touchX = 540;
		project.touchY = 480;
		project.touchStartX = 108;
		project.touchStartY = 192;
		project.mouseX = .5f;
		project.mouseY = .25f;
		project.pointerCount = 2;
		project.pointers[0] = 1080;
		project.pointers[1] = 0;
		project.pointers[2] = 7;
		project.pointers[3] = 270;
		project.pointers[4] = 960;
		project.pointers[5] = 9;
		project.time = 1.5f;
		project.frame = 42;
		project.offsetX = .3f;

		var pass = new FrameInputs();
		pass.scale(project, 540, 480);

		assertEquals(540, pass.width, DELTA);
		assertEquals(480, pass.height, DELTA);
		assertEquals(270, pass.touchX, DELTA);
		assertEquals(120, pass.touchY, DELTA);
		assertEquals(54, pass.touchStartX, DELTA);
		assertEquals(48, pass.touchStartY, DELTA);
		// Normalized positions, sizes and the clock are not in pixels.
		assertEquals(.5f, pass.mouseX, DELTA);
		assertEquals(.25f, pass.mouseY, DELTA);
		assertEquals(2, pass.pointerCount);
		assertEquals(540, pass.pointers[0], DELTA);
		assertEquals(0, pass.pointers[1], DELTA);
		assertEquals(7, pass.pointers[2], DELTA);
		assertEquals(135, pass.pointers[3], DELTA);
		assertEquals(240, pass.pointers[4], DELTA);
		assertEquals(9, pass.pointers[5], DELTA);
		assertEquals(1.5f, pass.time, DELTA);
		assertEquals(42, pass.frame);
		assertEquals(.3f, pass.offsetX, DELTA);
		assertEquals(1080, pass.surfaceWidth);
	}

	@Test
	public void aPassOfTheProjectSizeSeesTheProjectFrame() {
		var project = new FrameInputs();
		project.width = 100;
		project.height = 50;
		project.touchX = 33;
		project.touchY = 17;
		var pass = new FrameInputs();
		pass.scale(project, 100, 50);
		assertEquals(33, pass.touchX, DELTA);
		assertEquals(17, pass.touchY, DELTA);
		assertEquals(100, pass.width, DELTA);
	}
}
