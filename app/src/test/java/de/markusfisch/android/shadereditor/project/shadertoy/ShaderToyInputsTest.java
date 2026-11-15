package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShaderToyInputsTest {
	@Test
	public void inputsWithAnEqualUniformAreRenamed() {
		assertEquals("resolution time time frame mouse date",
				ShaderToyInputs.rename(
						"iResolution iTime iGlobalTime iFrame iMouse iDate"));
	}

	@Test
	public void onlyWholeNamesAreRenamed() {
		assertEquals("iTimeDelta iChannel0 iChannelResolution[0] " +
						"iSampleRate myiTime iFrameRate",
				ShaderToyInputs.rename("iTimeDelta iChannel0 " +
						"iChannelResolution[0] iSampleRate myiTime " +
						"iFrameRate"));
	}

	@Test
	public void onlyTheUniformsOfNamedInputsAreDeclaredOnce() {
		assertEquals("uniform float time;\n\n",
				ShaderToyInputs.declarations("iTime + iGlobalTime"));
		assertEquals("", ShaderToyInputs.declarations("myiTime iTimeDelta"));
		assertTrue(ShaderToyInputs.names("a = iTime;", "iTime"));
		assertFalse(ShaderToyInputs.names("a = iTimeDelta;", "iTime"));
	}
}
