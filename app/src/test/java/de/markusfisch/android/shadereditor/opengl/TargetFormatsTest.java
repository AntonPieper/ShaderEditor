package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.opengl.GLES20;
import android.opengl.GLES30;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.HostDirective.Format;

public class TargetFormatsTest {
	@Test
	public void everyContextDrawsIntoRgba8AndFiltersIt() {
		var formats = TargetFormats.of(2, "");
		assertNull(formats.refusal(Format.RGBA8));
		assertTrue(formats.filters(Format.RGBA8));
		assertEquals(GLES20.GL_RGBA, formats.internalFormat(Format.RGBA8));
		assertEquals(GLES20.GL_UNSIGNED_BYTE, formats.type(Format.RGBA8));
	}

	@Test
	public void gles2NeedsHalfFloatTexturesAndRenderingForRgba16f() {
		assertEquals("format=rgba16f needs half-float textures " +
						"(OES_texture_half_float), which this device does not " +
						"have; use format=rgba8",
				TargetFormats.of(2, "").refusal(Format.RGBA16F));
		assertEquals("format=rgba16f needs a device that draws into " +
						"half-float textures (EXT_color_buffer_half_float), " +
						"which this device does not have; use format=rgba8",
				TargetFormats.of(2, "GL_OES_texture_half_float")
						.refusal(Format.RGBA16F));
		// That extension needs OpenGL ES 3.0.
		assertTrue(TargetFormats.of(2, "GL_OES_texture_half_float " +
				"GL_EXT_color_buffer_float").refusal(Format.RGBA16F) != null);
		var drawn = TargetFormats.of(2, " GL_OES_texture_half_float  " +
				"GL_EXT_color_buffer_half_float ");
		assertNull(drawn.refusal(Format.RGBA16F));
		assertEquals(GLES20.GL_RGBA, drawn.internalFormat(Format.RGBA16F));
		assertEquals(0x8D61, drawn.type(Format.RGBA16F));
	}

	@Test
	public void gles2FiltersHalfFloatOnlyWithItsExtension() {
		assertFalse(TargetFormats.of(2, "GL_OES_texture_half_float " +
				"GL_EXT_color_buffer_half_float").filters(Format.RGBA16F));
		assertTrue(TargetFormats.of(2, "GL_OES_texture_half_float " +
				"GL_EXT_color_buffer_half_float " +
				"GL_OES_texture_half_float_linear").filters(Format.RGBA16F));
		assertFalse(TargetFormats.of(2, "GL_OES_texture_half_float_linear")
				.filters(Format.RGBA16F));
	}

	// Rendering is not inferred from the version.
	@Test
	public void gles3FiltersRgba16fButDrawsIntoItOnlyWithAnExtension() {
		var bare = TargetFormats.of(3, "");
		assertEquals("format=rgba16f needs a device that draws into " +
						"half-float textures (EXT_color_buffer_half_float or " +
						"EXT_color_buffer_float), which this device does not " +
						"have; use format=rgba8",
				bare.refusal(Format.RGBA16F));
		assertTrue(bare.filters(Format.RGBA16F));
		for (var extension : new String[]{"GL_EXT_color_buffer_float",
				"GL_EXT_color_buffer_half_float"}) {
			var formats = TargetFormats.of(3, extension);
			assertNull(formats.refusal(Format.RGBA16F));
			assertEquals(GLES30.GL_RGBA16F,
					formats.internalFormat(Format.RGBA16F));
			assertEquals(GLES30.GL_HALF_FLOAT, formats.type(Format.RGBA16F));
		}
	}
}
