package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class ShaderToyFrameReviewTest {
	@Test
	public void frameIsNotDeclaredWhenTheSourceDoesNotUseIFrame() {
		String converted = ShaderToySource.convert(
				"float frame(vec2 p) { return step(0.9, fract(p.x * 8.0)); }\n" +
						"void mainImage(out vec4 c, in vec2 p) {\n" +
						"\tc = vec4(frame(p / iResolution.xy));\n}\n");
		assertNotNull(converted);
		assertFalse("declares uniform int frame beside the port's own frame()",
				converted.contains("uniform int frame;"));
	}
}
