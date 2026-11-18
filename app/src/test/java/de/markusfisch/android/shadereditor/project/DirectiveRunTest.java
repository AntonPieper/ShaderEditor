package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Map;

public class DirectiveRunTest {
	@Test
	public void runsASwitchOnTheShadersOwnDefine() {
		assertStates("""
				#version 300 es       | ON
				#define AA 1          | ON
				#if AA>1              | ON
				float x = 1.0;        | OFF
				#else                 | ON
				float x = 2.0;        | ON
				#endif                | ON
				""");
	}

	@Test
	public void aCommentedOutDefineDefinesNothing() {
		assertStates("""
				// #define SOFT       | ON
				/* #define SOFT */    | ON
				#ifdef SOFT           | ON
				soft                  | OFF
				#endif                | ON
				#ifndef SOFT          | ON
				hard                  | ON
				#endif                | ON
				""");
	}

	@Test
	public void offArmsHoldTheirNestedDirectives() {
		assertStates("""
				#if 0                 | ON
				#if UNDEFINED         | OFF
				#error never          | OFF
				#define X 1           | OFF
				#else                 | OFF
				#endif                | OFF
				#endif                | ON
				#ifdef X              | ON
				x                     | OFF
				#endif                | ON
				""");
	}

	@Test
	public void anUndefinedNameInIfCannotBeTold() {
		// An error in GLSL ES, 0 for a C preprocessor.
		assertStates("""
				#if UNDEFINED         | ON
				a                     | UNKNOWN
				#elif 1               | ON
				b                     | UNKNOWN
				#else                 | ON
				c                     | OFF
				#endif                | ON
				#if 0 && UNDEFINED    | ON
				d                     | UNKNOWN
				#endif                | ON
				""");
	}

	@Test
	public void aDefineThatMayRunMakesItsNameUnknown() {
		assertStates("""
				#define A 1           | ON
				#if X_UNKNOWN_GL      | ON
				#endif                | ON
				#if GL_OES_standard_derivatives | ON
				#undef A              | UNKNOWN
				#if 0                 | UNKNOWN
				z                     | OFF
				#endif                | UNKNOWN
				#endif                | ON
				#if A                 | ON
				a                     | UNKNOWN
				#endif                | ON
				""");
	}

	@Test
	public void knowsTheNamesOfTheDriverThatAreSure() {
		assertStates("""
				#version 300 es                 | ON
				#if __VERSION__ == 300 && GL_ES | ON
				a                               | ON
				#endif                          | ON
				#ifndef GL_FRAGMENT_PRECISION_HIGH | ON
				b                               | OFF
				#endif                          | ON
				#ifdef GL_EXT_shader_texture_lod | ON
				c                               | UNKNOWN
				#endif                          | ON
				#if __LINE__ > 0                | ON
				d                               | UNKNOWN
				#endif                          | ON
				""");
		assertStates("""
				#ifdef GL_FRAGMENT_PRECISION_HIGH | ON
				precision highp float;          | UNKNOWN
				#else                           | ON
				precision mediump float;        | UNKNOWN
				#endif                          | ON
				#if __VERSION__ == 100          | ON
				a                               | ON
				#endif                          | ON
				""", 100);
	}

	@Test
	public void evaluatesWithThePreprocessorsIntegers() {
		assertStates("""
				#if -7 / 2 == -3 && -7 % 2 == -1 | ON
				a                     | ON
				#endif                | ON
				#if 010 == 8 && 0x1F == 31 && (1 << 4) == 16 | ON
				b                     | ON
				#endif                | ON
				#if !0 + ~0 == 0 && (3 > 2) + (2 >= 2) == 2 | ON
				c                     | ON
				#endif                | ON
				#if 1 / 0             | ON
				d                     | UNKNOWN
				#endif                | ON
				#if 2147483647 + 1 > 0 | ON
				e                     | UNKNOWN
				#endif                | ON
				#if 1u                | ON
				f                     | UNKNOWN
				#endif                | ON
				#if 1.0               | ON
				g                     | UNKNOWN
				#endif                | ON
				#if 1 ? 2 : 3         | ON
				h                     | UNKNOWN
				#endif                | ON
				""");
	}

	@Test
	public void expandsMacrosInConditions() {
		assertStates("""
				#define ONE 1         | ON
				#define TWO (ONE + ONE) | ON
				#define F(x) ((x) * TWO) | ON
				#define E             | ON
				#if F(ONE) == 2 && defined(E) && defined ONE | ON
				a                     | ON
				#endif                | ON
				#if E                 | ON
				b                     | UNKNOWN
				#endif                | ON
				#if F                 | ON
				c                     | UNKNOWN
				#endif                | ON
				#if F(1, 2)           | ON
				d                     | UNKNOWN
				#endif                | ON
				#define D defined(ONE) | ON
				#if D                 | ON
				e                     | UNKNOWN
				#endif                | ON
				""");
	}

	@Test
	public void aCallWhoseParenFollowsAnExpansionCannotBeTold() {
		// Preprocessors differ on what may expand again in such a call.
		assertStates("""
				#define F(x) (x + 1)  | ON
				#define G F           | ON
				#if G(1) == 2         | ON
				a                     | UNKNOWN
				#endif                | ON
				#if F(1) == 2         | ON
				b                     | ON
				#endif                | ON
				""");
	}

	@Test
	public void aRedefinitionMakesItsNameUnknown() {
		assertStates("""
				#define A 1           | ON
				#define A 1           | ON
				#if A                 | ON
				a                     | ON
				#endif                | ON
				#define A 2           | ON
				#if A                 | ON
				b                     | UNKNOWN
				#endif                | ON
				#undef A              | ON
				#ifdef A              | ON
				c                     | OFF
				#endif                | ON
				""");
	}

	@Test
	public void joinsContinuedDirectivesInEs3Only() {
		assertStates("""
				#version 300 es       | ON
				#define A 1 \\        | ON
				+ 1                   | ON
				#if A == 2 && \\      | ON
				1                     | ON
				x                     | ON
				#endif                | ON
				#if 0                 | ON
				#define B \\          | OFF
				#endif                | OFF
				#endif                | ON
				""");
		// No line of a text where drivers may disagree.
		assertStates("""
				#define A 1 \\        | UNKNOWN
				+ 1                   | UNKNOWN
				#if 0                 | UNKNOWN
				x                     | UNKNOWN
				#endif                | UNKNOWN
				""", 100);
		assertStates("""
				#version 300 es       | UNKNOWN
				float x = 1.0; \\     | UNKNOWN
				#if 0                 | UNKNOWN
				#endif                | UNKNOWN
				""");
	}

	@Test
	public void readsCommentsAsTheBuildDoes() {
		assertStates("""
				#if 0 // note         | ON
				a                     | OFF
				#endif /* end */      | ON
				/*                    | ON
				#if 1                 | ON
				*/                    | ON
				#if 0                 | ON
				b /* x                | OFF
				#endif                | OFF
				*/                    | OFF
				#endif                | ON
				""");
	}

	@Test
	public void aBrokenTextTellsNothing() {
		for (String broken : new String[]{"#else\n", "#if 1\n#else\n#else\n" +
				"#endif\n", "#if 0\n", "#endif\n", "#if 1\n#else x\n#endif\n",
				"#undef GL_ES\n", "#define defined 1\n",
				"#define P \"/*\"\n#if 0\n#endif\n"}) {
			DirectiveRun run = DirectiveRun.of(broken + "x\n", 300);
			int lines = (broken + "x\n").split("\n", -1).length;
			for (int n = 0; n < lines; ++n) {
				assertEquals(broken, DirectiveRun.State.UNKNOWN, run.state(n));
			}
		}
	}

	@Test
	public void expandsAUseFully() {
		DirectiveRun run = DirectiveRun.of("""
				#version 300 es
				#define PI 3.14159
				#define TAU (2.0 * PI)
				#define R(a) mat2(cos(a), sin(a), -sin(a), cos(a))
				#define NEG -x
				#define ADD(a, b) ((a) + \\
				(b))
				void main() {
					mat2 m = R(TAU * 0.5);
					float y = -NEG;
					float z = ADD(
						1.0, y);
					float w = R
						(1.0)[0][0];
				}
				""", 300);
		Map<String, String> expected = Map.of(
				"R", "mat2(cos((2.0 * 3.14159) * 0.5), sin((2.0 * 3.14159) " +
						"* 0.5), -sin((2.0 * 3.14159) * 0.5), " +
						"cos((2.0 * 3.14159) * 0.5))",
				"NEG", "-x",
				"ADD", "((1.0) + (y))");
		assertEquals(expected.get("R"), run.expansion(8, 10, "R"));
		assertEquals(expected.get("NEG"), run.expansion(9, 12, "NEG"));
		assertEquals(expected.get("ADD"), run.expansion(10, 11, "ADD"));
		assertEquals("mat2(cos(1.0), sin(1.0), -sin(1.0), cos(1.0))",
				run.expansion(12, 11, "R"));
		// The argument of another macro: what the compiler gets of it is
		// that macro's.
		assertNull(run.expansion(8, 12, "TAU"));
		// No macro there.
		assertNull(run.expansion(8, 7, "m"));
		assertNull(run.expansion(8, 10, "TAU"));
	}

	@Test
	public void expandsNothingItCannotTell() {
		DirectiveRun run = DirectiveRun.of("""
				#define F(x) G
				#define G(y) (y)
				#define ALIAS G
				#define L __LINE__
				#define TWICE(x) x x
				#ifdef GL_EXT_x
				#define U 1
				#endif
				a = F(1)(2);
				b = ALIAS(3);
				c = L;
				d = U;
				e = TWICE(1);
				f = F(1);
				#if 0
				#define DEAD 1
				#endif
				g = DEAD;
				""", 300);
		// It takes part of what follows it.
		assertNull(run.expansion(8, 4, "F"));
		assertNull(run.expansion(9, 4, "ALIAS"));
		assertNull(run.expansion(10, 4, "L"));
		assertNull(run.expansion(11, 4, "U"));
		assertEquals("1 1", run.expansion(12, 4, "TWICE"));
		assertEquals("G", run.expansion(13, 4, "F"));
		assertNull(run.expansion(17, 4, "DEAD"));
	}

	@Test
	public void spellsWhatReadsAsTheSameTokens() {
		DirectiveRun run = DirectiveRun.of("""
				#define M -1
				#define P +
				#define J(a, b) a b
				#define N -M
				x = -M;
				y = J(+, +)P;
				z = J(a, b);
				w = N;
				""", 300);
		assertEquals("-1", run.expansion(4, 5, "M"));
		assertEquals("+ +", run.expansion(5, 4, "J"));
		assertEquals("a b", run.expansion(6, 4, "J"));
		assertEquals("- -1", run.expansion(7, 4, "N"));
	}

	@Test
	public void answersOnlyForTheNameAtThePlace() {
		DirectiveRun run = DirectiveRun.of("""
				#define F(x) (x)
				a = /* F */ F(1);
				""", 300);
		assertEquals("(1)", run.expansion(1, 12, "F"));
		assertNull(DirectiveRun.of("#define FOO(x) (x)\na = FOO(1);\n", 300)
				.expansion(1, 4, "FO"));
		assertNull(DirectiveRun.of("#define F(x) (x)\na = XF(1);\n", 300)
				.expansion(1, 5, "F"));
		// In the comment.
		assertNull(run.expansion(1, 7, "F"));
		assertNull(run.expansion(1, 6, "F"));
	}

	@Test
	public void aMacroWithAParenOfItsOwnHidesWhoCalls() {
		// OPEN makes the use an argument of F, which no paren of the code
		// tells.
		DirectiveRun run = DirectiveRun.of("""
				#define F(x)
				#define G(y) (y)
				#define OPEN F(
				a = OPEN G(1));
				b = G(2);
				""", 300);
		assertNull(run.expansion(3, 9, "G"));
		assertNull(run.expansion(4, 4, "G"));
		// Also when it is not known what OPEN is.
		assertNull(DirectiveRun.of("""
				#define G(y) (y)
				#define OPEN F( #
				a = OPEN G(1));
				""", 300).expansion(2, 9, "G"));
	}

	@Test
	public void spellsNoComment() {
		DirectiveRun run = DirectiveRun.of("""
				#define E(a) a*b
				#define S(a) a/c
				x = E(/);
				y = S(/);
				""", 300);
		assertEquals("/ *b", run.expansion(2, 4, "E"));
		assertEquals("/ /c", run.expansion(3, 4, "S"));
	}

	@Test
	public void cannotTellWhatNestsTooDeep() {
		String deep = "(".repeat(5000) + "1" + ")".repeat(5000);
		assertStates("#if " + deep + " | ON\nx | UNKNOWN\n#endif | ON\n");
		StringBuilder calls = new StringBuilder("#define F(x) x\na = ");
		calls.append("F(".repeat(5000)).append('1')
				.append(")".repeat(5000)).append(";\n");
		assertNull(DirectiveRun.of(calls.toString(), 300)
				.expansion(1, 4, "F"));
		assertEquals("1", DirectiveRun.of("#define F(x) x\na = " +
				"F(".repeat(60) + "1" + ")".repeat(60) + ";\n", 300)
				.expansion(1, 4, "F"));
	}

	@Test
	public void showsNoEmptyExpansion() {
		DirectiveRun run = DirectiveRun.of("#define E\na = E 1;\n", 300);
		assertNull(run.expansion(1, 4, "E"));
	}

	private static void assertStates(String table) {
		assertStates(table, 300);
	}

	// Lines "text | STATE"; the text is what is left of the bar.
	private static void assertStates(String table, int version) {
		String[] rows = table.split("\n");
		StringBuilder text = new StringBuilder();
		for (String row : rows) {
			text.append(row.substring(0, row.lastIndexOf('|')).strip())
					.append('\n');
		}
		DirectiveRun run = DirectiveRun.of(text.toString(), version);
		for (int n = 0; n < rows.length; ++n) {
			assertEquals(rows[n], DirectiveRun.State.valueOf(
					rows[n].substring(rows[n].lastIndexOf('|') + 1).strip()),
					run.state(n));
		}
	}
}
