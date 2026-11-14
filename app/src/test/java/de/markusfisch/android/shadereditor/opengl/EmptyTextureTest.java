package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import org.junit.Test;

public class EmptyTextureTest {
	private static final int[] SAMPLERS = {
			GLES20.GL_SAMPLER_2D,
			GLES20.GL_SAMPLER_CUBE,
			GLES11Ext.GL_SAMPLER_EXTERNAL_OES,
			GLES30.GL_SAMPLER_3D,
			GLES30.GL_SAMPLER_2D_SHADOW,
			GLES30.GL_SAMPLER_2D_ARRAY,
			GLES30.GL_SAMPLER_2D_ARRAY_SHADOW,
			GLES30.GL_SAMPLER_CUBE_SHADOW,
			GLES30.GL_INT_SAMPLER_2D,
			GLES30.GL_INT_SAMPLER_3D,
			GLES30.GL_INT_SAMPLER_CUBE,
			GLES30.GL_INT_SAMPLER_2D_ARRAY,
			GLES30.GL_UNSIGNED_INT_SAMPLER_2D,
			GLES30.GL_UNSIGNED_INT_SAMPLER_3D,
			GLES30.GL_UNSIGNED_INT_SAMPLER_CUBE,
			GLES30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY};

	// SPEC 3.2: a sampler nothing provides reads zero. Every sampler
	// type gets an empty texture of its kind, which reads zero only when
	// target and format fit the type.
	@Test
	public void everySamplerTypeHasAnEmptyTextureOfItsKind() {
		for (int type : SAMPLERS) {
			var sampler = new GlUniform("s", type, 1, 0);
			assertTrue(sampler.isSampler());
			assertNotNull(EmptyTexture.of(type));
		}
		assertEquals(GLES30.GL_TEXTURE_3D,
				EmptyTexture.of(GLES30.GL_SAMPLER_3D).target);
		assertEquals(GLES30.GL_TEXTURE_2D_ARRAY,
				EmptyTexture.of(GLES30.GL_INT_SAMPLER_2D_ARRAY).target);
		var unsigned = EmptyTexture.of(GLES30.GL_UNSIGNED_INT_SAMPLER_2D);
		assertEquals(GLES30.GL_RGBA8UI, unsigned.internalFormat);
		assertEquals(GLES30.GL_RGBA_INTEGER, unsigned.format);
		var signed = EmptyTexture.of(GLES30.GL_INT_SAMPLER_CUBE);
		assertEquals(GLES20.GL_TEXTURE_CUBE_MAP, signed.target);
		assertEquals(GLES30.GL_RGBA8I, signed.internalFormat);
		var shadow = EmptyTexture.of(GLES30.GL_SAMPLER_2D_SHADOW);
		assertTrue(shadow.compares);
		assertEquals(GLES30.GL_DEPTH_COMPONENT16, shadow.internalFormat);
		assertFalse(EmptyTexture.of(GLES20.GL_SAMPLER_2D).compares);
		assertEquals(2, EmptyTexture.of(GLES20.GL_SAMPLER_2D).glesVersion);
		assertEquals(3, EmptyTexture.of(GLES30.GL_SAMPLER_3D).glesVersion);
	}
}
