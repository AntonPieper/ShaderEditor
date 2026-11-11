package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;

import android.opengl.GLES20;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.legacy.LegacySamplerSettings;

public class GlSamplingTest {
	@Test
	public void minificationShortcutsSetTheOldFilters() {
		String[] shortcuts = {"n", "l", "nn", "ln", "nl", "ll"};
		int[] filters = {
				GLES20.GL_NEAREST,
				GLES20.GL_LINEAR,
				GLES20.GL_NEAREST_MIPMAP_NEAREST,
				GLES20.GL_LINEAR_MIPMAP_NEAREST,
				GLES20.GL_NEAREST_MIPMAP_LINEAR,
				GLES20.GL_LINEAR_MIPMAP_LINEAR};
		for (int i = 0; i < shortcuts.length; ++i) {
			assertEquals(shortcuts[i], filters[i],
					GlSampling.minFilter(sampling("min:" + shortcuts[i])));
		}
		assertEquals(GLES20.GL_NEAREST_MIPMAP_LINEAR,
				GlSampling.minFilter(sampling("min:x")));
	}

	@Test
	public void magnificationShortcutsSetTheOldFilters() {
		assertEquals(GLES20.GL_NEAREST,
				GlSampling.magFilter(sampling("mag:n")));
		assertEquals(GLES20.GL_LINEAR,
				GlSampling.magFilter(sampling("mag:l")));
		assertEquals(GLES20.GL_LINEAR,
				GlSampling.magFilter(sampling("mag:x")));
	}

	@Test
	public void wrapShortcutsSetTheOldModes() {
		Sampling sampling = sampling("s:c;t:m");
		assertEquals(GLES20.GL_CLAMP_TO_EDGE,
				GlSampling.wrap(sampling.wrapS()));
		assertEquals(GLES20.GL_MIRRORED_REPEAT,
				GlSampling.wrap(sampling.wrapT()));
		sampling = sampling("s:r;t:x");
		assertEquals(GLES20.GL_REPEAT, GlSampling.wrap(sampling.wrapS()));
		assertEquals(GLES20.GL_REPEAT, GlSampling.wrap(sampling.wrapT()));
		assertEquals(GLES20.GL_REPEAT, GlSampling.wrap(Wrap.REPEAT));
	}

	@Test
	public void defaultsSetTheOldParameters() {
		Sampling texture = LegacySamplerSettings.TEXTURE.sampling();
		assertEquals(GLES20.GL_NEAREST, GlSampling.minFilter(texture));
		assertEquals(GLES20.GL_LINEAR, GlSampling.magFilter(texture));
		assertEquals(GLES20.GL_REPEAT, GlSampling.wrap(texture.wrapS()));
		Sampling backbuffer = LegacySamplerSettings.BACKBUFFER.sampling();
		assertEquals(GLES20.GL_NEAREST, GlSampling.minFilter(backbuffer));
		assertEquals(GLES20.GL_NEAREST, GlSampling.magFilter(backbuffer));
		assertEquals(GLES20.GL_CLAMP_TO_EDGE,
				GlSampling.wrap(backbuffer.wrapT()));
		// Thumbnails and bitmap transforms.
		assertEquals(GLES20.GL_LINEAR, GlSampling.minFilter(Sampling.IMAGE));
		assertEquals(GLES20.GL_LINEAR, GlSampling.magFilter(Sampling.IMAGE));
		assertEquals(GLES20.GL_CLAMP_TO_EDGE,
				GlSampling.wrap(Sampling.IMAGE.wrapS()));
	}

	private static Sampling sampling(String params) {
		return LegacySamplerSettings.TEXTURE
				.parse("///" + params + ";", false)
				.sampling();
	}
}
