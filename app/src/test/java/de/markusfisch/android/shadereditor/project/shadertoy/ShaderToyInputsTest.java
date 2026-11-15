package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShaderToyInputsTest {
	@Test
	public void inputsWithAnEqualUniformAreRenamed() {
		assertEquals("resolution time time timeDelta frameRate frame " +
						"mouse date",
				ShaderToyInputs.rename("iResolution iTime iGlobalTime " +
						"iTimeDelta iFrameRate iFrame iMouse iDate"));
	}

	@Test
	public void onlyWholeNamesAreRenamed() {
		assertEquals("iTimeDeltas iChannel0 iChannelResolution[0] " +
						"iSampleRate myiTime iFrames timeDelta",
				ShaderToyInputs.rename("iTimeDeltas iChannel0 " +
						"iChannelResolution[0] iSampleRate myiTime " +
						"iFrames iTimeDelta"));
	}

	@Test
	public void onlyTheUniformsOfNamedInputsAreDeclaredOnce() {
		assertEquals("uniform float time;\n\n",
				ShaderToyInputs.declarations("iTime + iGlobalTime"));
		assertEquals("", ShaderToyInputs.declarations("myiTime iTimeDeltas"));
		// What a frame took is no time: each has its own uniform.
		assertEquals("uniform float timeDelta;\nuniform float frameRate;\n\n",
				ShaderToyInputs.declarations("iTimeDelta * iFrameRate"));
		assertTrue(ShaderToyInputs.names("a = iTime;", "iTime"));
		assertFalse(ShaderToyInputs.names("a = iTimeDelta;", "iTime"));
	}
}
