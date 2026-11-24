package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
		assertEquals("float d = a +\n    b * c + dd;",
				DocsSheet.wrap("float d = a + b * c + dd;", MONO, 15));
		// Two columns in where that saves a line.
		assertEquals("float d = a +\n  b * c + dd;",
				DocsSheet.wrap("float d = a + b * c + dd;", MONO, 13));
		assertEquals("float d = a +\n  b * c + dd +\n  e * f;",
				DocsSheet.wrap("float d = a + b * c + dd + e * f;", MONO,
						14));
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
		// What has no place to break is cut, never wider than the box.
		assertEquals("vec4\n colour_of\n _the_sky;",
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

	@Test
	public void showsADefineWithItsHeadOnALineAndItsBodyUnderIt() {
		assertEquals("#define MIX3(a, b, c, t)\n" +
						"    mix(\n" +
						"        mix(a, b, S(0.0, 0.5, t)),\n" +
						"        c,\n" +
						"        S(0.5, 1.0, t))",
				DocsSheet.wrap("#define MIX3(a, b, c, t) mix(mix(a, b, " +
						"S(0.0, 0.5, t)), c, S(0.5, 1.0, t))", MONO, 40));
		assertEquals("#define RING(p, r, w)\n" +
						"    S(w, 0.0, abs(length(p) - (r)))",
				DocsSheet.wrap("#define RING(p, r, w) S(w, 0.0, " +
						"abs(length(p) - (r)))", MONO, 40));
		// A head too wide breaks after a comma, two steps in.
		assertEquals("#define MIX3(a, b,\n  c, t)\n mix(\n  mix(\n" +
						"   a,\n   b,\n   S(0.0, 0.5, t)),\n  c,\n" +
						"  S(0.5, 1.0, t))",
				DocsSheet.wrap("#define MIX3(a, b, c, t) mix(mix(a, b, " +
						"S(0.0, 0.5, t)), c, S(0.5, 1.0, t))", MONO, 20));
		assertEquals("#define\n F(a, b)\n a",
				DocsSheet.wrap("#define F(a, b) a", MONO, 8));
	}

	@Test
	public void keepsAnInnerCallThatFitsOnOneLine() {
		assertEquals("mix(\n" +
						"    mix(\n" +
						"        vec3(0.1, 0.2, 0.5),\n" +
						"        vec3(0.9, 0.4, 0.2),\n" +
						"        smoothstep(0.0, 0.5, length(uv))),\n" +
						"    vec3(1.0, 0.9, 0.6),\n" +
						"    smoothstep(0.5, 1.0, length(uv)))",
				DocsSheet.wrap("mix(mix(vec3(0.1, 0.2, 0.5), vec3(0.9, 0.4, " +
						"0.2), smoothstep(0.0, 0.5, length(uv))), vec3(1.0, " +
						"0.9, 0.6), smoothstep(0.5, 1.0, length(uv)))", MONO,
						60));
	}

	@Test
	public void laysOutDeepNestingAndWhatCannotFit() {
		// A step of one where two does not fit.
		assertEquals("f(\n g(\n  h(\n   1,\n   2)))",
				DocsSheet.wrap("f(g(h(1, 2)))", MONO, 8));
		// A name wider than the box is cut, one column further in.
		assertEquals("f(\n averyvery\n  verylong\n  name)",
				DocsSheet.wrap("f(averyveryverylongname)", MONO, 10));
	}

	// The expansions of Parameters.frag and Nested.frag as the largest
	// system font gives them room: 20 columns, and 24 and 30.
	private static final String RING =
			"smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))";
	private static final String MIX3 = "mix(mix(vec3(0.1, 0.2, 0.5), " +
			"vec3(0.9, 0.4, 0.2), smoothstep(0.0, 0.5, length(uv))), " +
			"vec3(1.0, 0.9, 0.6), smoothstep(0.5, 1.0, length(uv)))";
	private static final String SPIN =
			"mat2(cos(((time * 0.05) * (2.0 * 3.14159265))), " +
			"sin(((time * 0.05) * (2.0 * 3.14159265))), " +
			"-sin(((time * 0.05) * (2.0 * 3.14159265))), " +
			"cos(((time * 0.05) * (2.0 * 3.14159265))))";

	@Test
	public void laysOutRingAt20And24And30() {
		assertEquals(lines("smoothstep(", "  0.02,", "  0.0,", "  abs(",
				"    length(uv)", "      - (0.3)))"), DocsSheet.wrap(RING,
				MONO, 20));
		assertEquals(lines("smoothstep(", "  0.02,", "  0.0,", "  abs(",
				"    length(uv) - (0.3)))"), DocsSheet.wrap(RING, MONO, 24));
		assertEquals(lines("smoothstep(", "    0.02,", "    0.0,",
				"    abs(length(uv) - (0.3)))"), DocsSheet.wrap(RING, MONO,
				30));
	}

	@Test
	public void laysOutMix3At20And24And30() {
		assertEquals(lines("mix(", "  mix(", "    vec3(", "      0.1,",
				"      0.2,", "      0.5),", "    vec3(", "      0.9,",
				"      0.4,", "      0.2),", "    smoothstep(",
				"      0.0,", "      0.5,", "      length(uv))),", "  vec3(",
				"    1.0,", "    0.9,", "    0.6),", "  smoothstep(",
				"    0.5,", "    1.0,", "    length(uv)))"),
				DocsSheet.wrap(MIX3, MONO, 20));
		// Two columns in keep each vec3 and length(uv) whole.
		assertEquals(lines("mix(", "  mix(", "    vec3(0.1, 0.2, 0.5),",
				"    vec3(0.9, 0.4, 0.2),", "    smoothstep(", "      0.0,",
				"      0.5,", "      length(uv))),", "  vec3(1.0, 0.9, 0.6),",
				"  smoothstep(", "    0.5,", "    1.0,", "    length(uv)))"),
				DocsSheet.wrap(MIX3, MONO, 24));
		assertEquals(lines("mix(", "    mix(", "        vec3(0.1, 0.2, 0.5),",
				"        vec3(0.9, 0.4, 0.2),", "        smoothstep(",
				"            0.0,", "            0.5,",
				"            length(uv))),", "    vec3(1.0, 0.9, 0.6),",
				"    smoothstep(", "        0.5,", "        1.0,",
				"        length(uv)))"), DocsSheet.wrap(MIX3, MONO, 30));
	}

	@Test
	public void laysOutSpinAt20And24And30() {
		StringBuilder at20 = new StringBuilder("mat2(");
		StringBuilder at24 = new StringBuilder("mat2(");
		StringBuilder at30 = new StringBuilder("mat2(");
		String[] calls = {"cos(", "sin(", "-sin(", "cos("};
		for (int i = 0; i < calls.length; ++i) {
			String end = i < calls.length - 1 ? ")))," : "))))";
			at20.append("\n ").append(calls[i]).append("\n  ((time * 0.05)")
					.append("\n   * (2.0\n    * 3.14159265").append(end);
			at24.append("\n  ").append(calls[i])
					.append("\n    ((time * 0.05)\n      * (2.0")
					.append("\n        * 3.14159265").append(end);
			at30.append("\n  ").append(calls[i])
					.append("\n    ((time * 0.05)")
					.append("\n      * (2.0 * 3.14159265").append(end);
		}
		assertEquals(at20.toString(), DocsSheet.wrap(SPIN, MONO, 20));
		assertEquals(at24.toString(), DocsSheet.wrap(SPIN, MONO, 24));
		assertEquals(at30.toString(), DocsSheet.wrap(SPIN, MONO, 30));
	}

	// No line is ever wider than the box, which would go on at its left
	// edge, and no call shorter than its line's room is broken.
	@Test
	public void neverHandsTheBoxALineWiderThanItself() {
		for (String code : new String[]{RING, MIX3, SPIN,
				"#define MIX3(a, b, c, t) mix(mix(a, b, S(0.0, 0.5, t)), " +
						"c, S(0.5, 1.0, t))",
				"float disc = 1.0 - smoothstep(0.29, 0.31, length(uv));"}) {
			for (int width = 8; width <= 80; ++width) {
				String laid = DocsSheet.wrap(code, MONO, width);
				assertEquals(laid, code.replaceAll("\\s", ""),
						laid.replaceAll("\\s", ""));
				for (String line : laid.split("\n")) {
					assertTrue(width + ":\n" + laid, line.length() <= width);
					if (width >= 20) {
						assertTrue(width + ":\n" + laid,
								!line.endsWith("length("));
					}
				}
			}
		}
	}

	private static String lines(String... lines) {
		return String.join("\n", lines);
	}

	@Test
	public void keepsTheCodeOfEveryExpansion() {
		for (String code : new String[]{
				"smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))",
				"smoothstep(0.04 * pulse, 0.0, abs(length(uv) - (0.9)))",
				"mix(mix(vec3(0.1, 0.2, 0.5), vec3(0.9, 0.4, 0.2), " +
						"smoothstep(0.0, 0.5, length(uv))), vec3(1.0, 0.9, " +
						"0.6), smoothstep(0.5, 1.0, length(uv)))",
				"mat2(cos(((time * 0.05) * (2.0 * 3.14159265))), " +
						"sin(((time * 0.05) * (2.0 * 3.14159265))), " +
						"-sin(((time * 0.05) * (2.0 * 3.14159265))), " +
						"cos(((time * 0.05) * (2.0 * 3.14159265))))",
				"(2.0 * 3.14159265)",
				"mod(floor((uv).x * (8.0)) + floor((uv).y * (8.0)), 2.0)",
				"{ vec3 t_ = dark; dark = light; light = t_; }",
				"col = clamp(col, 0.0, 1.0)",
				"mat2(cos(iTime * 0.3), -sin(iTime * 0.3), " +
						"sin(iTime * 0.3), cos(iTime * 0.3))",
				"max(abs((p).x), abs((p).y))"}) {
			for (int width : new int[]{24, 48}) {
				String laid = DocsSheet.wrap(code, MONO, width);
				assertEquals(laid, code.replaceAll("\\s", ""),
						laid.replaceAll("\\s", ""));
				// Lines break only between parts, never in a number, a
				// name or a swizzle.
				String[] lines = laid.split("\n");
				for (int i = 1; i < lines.length; ++i) {
					String before = lines[i - 1].trim();
					String after = lines[i].trim();
					assertTrue(laid, before.matches(".*([(,{;]| [-+*/=<>]+)") ||
							after.matches("([-+*/=<>|&]+ .*|}.*)"));
				}
			}
		}
	}
}
