package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShaderManagerTest {
	@Test
	public void armsCrashGuardForSavedShaderRenderedInBackground() {
		assertEquals(42, ShaderManager.getPendingCrashShaderId(true, 42));
	}

	@Test
	public void leavesCrashGuardOffForUnsavedShader() {
		assertEquals(0, ShaderManager.getPendingCrashShaderId(true, 0));
	}

	@Test
	public void leavesCrashGuardOffWithoutBackgroundPreview() {
		assertEquals(0, ShaderManager.getPendingCrashShaderId(false, 42));
	}
}
