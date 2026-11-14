package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShaderRenderPipelineTest {
	// A sampler is lowp by default in a fragment shader, which loses what
	// an rgba16f target holds when it is presented or stretched over a
	// history.
	@Test
	public void theInternalShaderSamplesTargetsAtTheBestPrecision() {
		String shader = ShaderRenderPipeline.SURFACE_FRAGMENT_SHADER;
		assertTrue(shader.contains("precision highp sampler2D;"));
		assertTrue(shader.contains("precision mediump sampler2D;"));
	}

	// The shader clamps as PictureLimit.apply does, so an rgba16f output
	// stays under the ceiling.
	@Test
	public void theInternalShaderClampsBeforeItLimits() {
		String shader = ShaderRenderPipeline.SURFACE_FRAGMENT_SHADER;
		int clamp = shader.indexOf("c = clamp(c, 0.0, 1.0);");
		assertTrue(clamp > -1);
		assertTrue(clamp < shader.indexOf("c.rgb /= 1.0 + limit"));
	}
}
