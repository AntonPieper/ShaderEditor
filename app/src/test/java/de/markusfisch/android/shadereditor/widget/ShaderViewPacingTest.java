package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.opengl.GLSurfaceView;

import org.junit.Test;

// When a frame rate limit asks for frames, and when it must not: F1, a
// detached view keeps no callback, which would keep the view.
public class ShaderViewPacingTest {
	private static final int ALWAYS = GLSurfaceView.RENDERMODE_CONTINUOUSLY;
	private static final int ON_DEMAND = GLSurfaceView.RENDERMODE_WHEN_DIRTY;

	@Test
	public void aLimitAsksForFramesOnlyWhileTheViewIsShown() {
		assertTrue(ShaderView.paces(false, 15, ALWAYS, true, true));
		// Detached without a pause first.
		assertFalse(ShaderView.paces(false, 15, ALWAYS, true, false));
		assertFalse(ShaderView.paces(false, 15, ALWAYS, false, true));
		assertFalse(ShaderView.paces(true, 15, ALWAYS, true, true));
		// The wallpaper's low-battery mode, or no limit.
		assertFalse(ShaderView.paces(false, 15, ON_DEMAND, true, true));
		assertFalse(ShaderView.paces(false, 0, ALWAYS, true, true));
	}

	@Test
	public void theRenderModeFollowsTheLimitAndPause() {
		assertEquals(ON_DEMAND, ShaderView.renderMode(false, 15, ALWAYS));
		assertEquals(ON_DEMAND, ShaderView.renderMode(true, 0, ALWAYS));
		assertEquals(ALWAYS, ShaderView.renderMode(false, 0, ALWAYS));
		assertEquals(ON_DEMAND, ShaderView.renderMode(false, 0, ON_DEMAND));
	}
}
