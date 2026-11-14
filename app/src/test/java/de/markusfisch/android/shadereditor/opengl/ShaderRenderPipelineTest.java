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

	// The shader clamps as PictureLimit.apply does, and only with a limit:
	// presented under a ceiling, an rgba16f output stays under it, while a
	// stretch of initial content into a history, drawn without a limit,
	// keeps values above 1.
	@Test
	public void theInternalShaderClampsOnlyWhenItLimits() {
		String shader = ShaderRenderPipeline.SURFACE_FRAGMENT_SHADER;
		int limited = shader.indexOf("if (limit > 0.0) {");
		int clamp = shader.indexOf("c = clamp(c, 0.0, 1.0);");
		assertTrue(limited > -1);
		assertTrue(clamp > limited);
		assertTrue(clamp < shader.indexOf("c.rgb /= 1.0 + limit"));
	}
}
