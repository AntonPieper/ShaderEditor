package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DirectiveFloorTest {
	@Test
	public void readsTheShapesShadersUse() {
		assertStates("""
				#version 300 es       | ON
				#define AA 1          | ON
				#define HW_PERFORMANCE 0 | ON
				//#define SOFT        | ON
				#define LOW           | ON
				#if AA>1              | ON
				aa                    | OFF
				#else                 | ON
				one                   | ON
				#endif                | ON
				#ifdef SOFT           | ON
				soft                  | OFF
				#elif HW_PERFORMANCE==0 | ON
				cheap                 | ON
				#endif                | ON
				#ifndef LOW           | ON
				high                  | OFF
				#endif                | ON
				#if 0                 | ON
				#if 1                 | OFF
				dead                  | OFF
				#endif                | OFF
				#endif                | ON
				#if defined(AA) && AA == 1 || !defined LOW | ON
				both                  | ON
				#endif                | ON
				""");
	}

	@Test
	public void tellsNothingFromWhatItDoesNotRead() {
		for (String odd : new String[]{"#define F(x) x\n#if F(1)",
				"#if UNDEFINED", "#if defined(X) && X", "#if 1 ? 1 : 0",
				"#if 1u", "#if 010", "#if 2 * 3", "#define A 1\n#define A 1",
				"#ifdef GL_EXT_x", "#define A 1 \\", "#if 0\n#else\n#else",
				"#define P \"/*\"", "#undef GL_ES"}) {
			String text = odd + "\nx\n#endif\n";
			DirectiveFloor floor = DirectiveFloor.of(text, 300);
			// The line x, which the #endif after it cannot have hidden.
			int x = text.split("\n", -1).length - 3;
			assertEquals(odd, DirectiveRun.State.UNKNOWN, floor.state(x));
		}
	}

	@Test
	public void joinsContinuedDirectivesWhenAsked() {
		String text = """
				#version 300 es
				#define R(a) mat2(cos(a), \\
				\tsin(a), -sin(a), cos(a))
				#if 0
				x
				#endif
				""";
		assertEquals(DirectiveRun.State.UNKNOWN,
				DirectiveFloor.of(text, 300).state(4));
		assertEquals(DirectiveRun.State.OFF,
				DirectiveFloor.of(text, 300, true).state(4));
		assertEquals(DirectiveRun.State.UNKNOWN,
				DirectiveFloor.of(text.replace("#version 300 es\n", ""), 100,
						true).state(3));
	}

	@Test
	public void expandsTheUseAskedFor() {
		String text = """
				#define PI 3.14159
				#define TAU (2.0 * PI)
				#define R(a) mat2(cos(a), sin(a), -sin(a), cos(a))
				#define SQ(x) ((x) * (x))
				#define REC REC
				#define CAT(a, b) a ## b
				#define G F
				#define F(x) (x)
				m = R(TAU * 0.25);
				d = SQ(SQ(2.0));
				e = SQ(
					1.0);
				r = REC;
				c = CAT(a, b);
				g = G(1);
				h = F(v[1, 2]);
				i = F(SQ(2.0));
				""";
		assertEquals("mat2(cos((2.0 * 3.14159) * 0.25), sin((2.0 * 3.14159)" +
				" * 0.25), -sin((2.0 * 3.14159) * 0.25), cos((2.0 * 3.14159)" +
				" * 0.25))", ExpansionFloor.of(text, 300, false, 8, 4, "R"));
		assertEquals("((((2.0) * (2.0))) * (((2.0) * (2.0))))",
				ExpansionFloor.of(text, 300, false, 9, 4, "SQ"));
		// A use over lines, a macro that reaches itself, "##", what
		// follows taking part, a bracket open in an argument, a use in an
		// argument: no row.
		assertNull(ExpansionFloor.of(text, 300, false, 10, 4, "SQ"));
		assertNull(ExpansionFloor.of(text, 300, false, 12, 4, "REC"));
		assertNull(ExpansionFloor.of(text, 300, false, 13, 4, "CAT"));
		assertNull(ExpansionFloor.of(text, 300, false, 14, 4, "G"));
		assertNull(ExpansionFloor.of(text, 300, false, 15, 4, "F"));
		assertNull(ExpansionFloor.of(text, 300, false, 16, 6, "SQ"));
		assertNull(ExpansionFloor.of(text, 300, false, 8, 0, "m"));
	}

	// Lines "text | STATE"; the text is what is left of the bar.
	private static void assertStates(String table) {
		String[] rows = table.split("\n");
		StringBuilder text = new StringBuilder();
		for (String row : rows) {
			text.append(row.substring(0, row.lastIndexOf('|')).strip())
					.append('\n');
		}
		DirectiveFloor floor = DirectiveFloor.of(text.toString(), 300);
		for (int n = 0; n < rows.length; ++n) {
			assertEquals(rows[n], DirectiveRun.State.valueOf(
					rows[n].substring(rows[n].lastIndexOf('|') + 1).strip()),
					floor.state(n));
		}
	}
}
