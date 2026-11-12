package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TokenizeCalculationTest {
	@Test
	public void aShaderToySourceComesWithItsConversion() {
		String src = "void mainImage(out vec4 c, in vec2 p) {\n\tc = vec4(1.0);\n}\n";
		ShaderEditor.Analysis analysis = new ShaderEditor.TokenizeCalculation(
				7, src, (revision, a, text) -> assertEquals(7, revision)).call();
		assertNotNull(analysis.shaderToyConversion());
		assertTrue(analysis.shaderToyConversion().contains("void main()"));
		assertTrue(analysis.tokens().size() > 0);
	}

	@Test
	public void aShaderWithMainNeedsNoConversion() {
		String src = "void mainImage(out vec4 c, in vec2 p) {}\nvoid main() {}\n";
		ShaderEditor.Analysis analysis = new ShaderEditor.TokenizeCalculation(
				1, src, (revision, a, text) -> {
				}).call();
		assertNull(analysis.shaderToyConversion());
	}
}
