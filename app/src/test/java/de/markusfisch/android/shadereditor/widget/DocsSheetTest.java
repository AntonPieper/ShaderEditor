package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** How the sheet's code wraps where its box is too narrow. */
public class DocsSheetTest {
	// One unit per character.
	private static final MessageRow.Measure MONO = String::length;

	@Test
	public void breaksAStatementAfterTheLastCommaThatFits() {
		assertEquals("float disc = 1.0 - smoothstep(0.29,\n" +
						"    0.31, length(uv));",
				DocsSheet.wrap("float disc = 1.0 - smoothstep(0.29, " +
						"0.31, length(uv));", MONO, 36));
	}

	@Test
	public void breaksAfterAnOperatorAndAsOftenAsItTakes() {
		assertEquals("float d = a +\n    b * c +\n    dd;",
				DocsSheet.wrap("float d = a + b * c + dd;", MONO, 13));
	}

	@Test
	public void keepsTheIndentOfTheLine() {
		assertEquals("  x = f(a,\n      b);",
				DocsSheet.wrap("  x = f(a, b);", MONO, 10));
	}

	@Test
	public void leavesALineThatFitsOrHasNoPlaceToBreak() {
		assertEquals("float x = 1.0;",
				DocsSheet.wrap("float x = 1.0;", MONO, 14));
		assertEquals("vec4 colour_of_the_sky;",
				DocsSheet.wrap("vec4 colour_of_the_sky;", MONO, 10));
	}

	@Test
	public void givesAWideFormAParameterPerLine() {
		// Indented by four, as the rest of a statement is.
		assertEquals("float smoothstep(\n    float edge0,\n" +
						"    float edge1,\n    float x)",
				DocsSheet.wrap("float smoothstep(float edge0, float edge1, " +
						"float x)", MONO, 20));
	}
}
