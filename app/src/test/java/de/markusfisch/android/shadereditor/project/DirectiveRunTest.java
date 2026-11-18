package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Random;

public class DirectiveRunTest {
	@Test
	public void readsTheShapesShadersUse() {
		assertStates(300, """
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
				#ifdef GL_FRAGMENT_PRECISION_HIGH | ON
				high                  | ON
				#else                 | ON
				medium                | OFF
				#endif                | ON
				""");
	}

	// A macro is replaced by its body before the condition is read, as
	// the preprocessor does: 3 - N is 3 - 1 + 1.
	@Test
	public void readsAMacroAsItsBody() {
		assertStates(300, """
				#define AA -2         | ON
				#define N 1 + 1       | ON
				#define M N           | ON
				#if AA > 1            | ON
				aa                    | OFF
				#endif                | ON
				#if 3 - M == 3        | ON
				three                 | ON
				#endif                | ON
				#if defined(M) && !defined N | ON
				n                     | OFF
				#endif                | ON
				""");
		for (String odd : new String[]{"#define A A\n#if A",
				"#define F(x) 1\n#if F", "#define D defined(X)\n#if D",
				"#define E\n#if E"}) {
			String text = odd + "\nx\n#endif\n";
			assertEquals(odd, DirectiveRun.State.UNKNOWN,
					DirectiveRun.of(text, 300).state(2));
		}
	}

	@Test
	public void tellsNothingFromWhatItDoesNotRead() {
		for (String odd : new String[]{"#define F(x) x\n#if F(1)",
				"#if UNDEFINED", "#if defined(X) && X", "#if 1 ? 1 : 0",
				"#if 1u", "#if 010", "#if 2 * 3", "#define A 1\n#define A 1",
				"#ifdef GL_EXT_x", "#define A 1 \\", "#if 0\n#else\n#else",
				"#define P \"/*\"", "#undef GL_ES"}) {
			String text = odd + "\nx\n#endif\n";
			// The line x, which the #endif after it cannot have hidden.
			int x = text.split("\n", -1).length - 3;
			assertEquals(odd, DirectiveRun.State.UNKNOWN,
					DirectiveRun.of(text, 300).state(x));
		}
	}

	// The app's template starts ES 1.00 shaders with a switch only the
	// driver can decide; the switches after it are read all the same.
	@Test
	public void aGroupItCannotDecideLeavesOnlyItsArmsUnknown() {
		assertStates(100, """
				#ifdef GL_FRAGMENT_PRECISION_HIGH | ON
				precision highp float; | UNKNOWN
				#else                 | UNKNOWN
				precision mediump float; | UNKNOWN
				#endif                | ON
				#define AA 2          | ON
				#if AA > 1            | ON
				aa                    | ON
				#else                 | ON
				one                   | OFF
				#endif                | ON
				#if 0                 | ON
				a                     | OFF
				#elif EXT             | ON
				b                     | UNKNOWN
				#ifdef GL_EXT_x       | UNKNOWN
				c                     | UNKNOWN
				#endif                | UNKNOWN
				#endif                | ON
				#if 0                 | ON
				d                     | OFF
				#endif                | ON
				""");
		// An arm that defines or joins something may change what follows.
		for (String arm : new String[]{"#define A 1", "#undef AA",
				"x \\"}) {
			String text = "#define AA 2\n#ifdef GL_EXT_x\n" + arm +
					"\n#endif\n#if 0\nd\n#endif\n";
			assertEquals(arm, DirectiveRun.State.UNKNOWN,
					DirectiveRun.of(text, 300).state(5));
		}
	}

	// A block comment that a directive line leaves open goes on with the
	// directive to the end of the line where it closes, as Mesa reads it:
	// "#if 0 /*" and "*/ || 1" is "#if 0 || 1".
	@Test
	public void tellsNothingAfterACommentADirectiveLeavesOpen() {
		for (String open : new String[]{"#if 0 /* why,\n*/ || 1",
				"#define Q 0 /* low,\n*/ + 1\n#if Q",
				"#if 1 /* a\n*/ && 0\n#else",
				"#if 1\n#elif 0 /* a\n*/ || 1",
				"#ifdef GL_EXT_x\n#endif /* a\n*/\n#if 0"}) {
			String text = "#version 300 es\n" + open + "\nx\n#endif\n";
			int x = text.split("\n", -1).length - 3;
			assertEquals(open, DirectiveRun.State.UNKNOWN,
					DirectiveRun.of(text, 300).state(x));
		}
	}

	// Macros that grow at every level stop at the 256 tokens a condition
	// may have, before they are expanded.
	@Test(timeout = 5000)
	public void stopsAMacroThatGrowsTooLong() {
		StringBuilder sb = new StringBuilder();
		for (int level = 0; level < 10; ++level) {
			sb.append("#define M").append(level);
			for (int i = 0; i < 6; ++i) {
				sb.append(" M").append(level + 1);
			}
			sb.append('\n');
		}
		sb.append("#define M10 1\n#if M0\nx\n#endif\n");
		assertEquals(DirectiveRun.State.UNKNOWN,
				DirectiveRun.of(sb.toString(), 300).state(12));
	}

	// Random directive lines, deep nesting and long chains never throw.
	@Test
	public void neverThrows() {
		String[] parts = {"#if", "#ifdef", "#ifndef", "#elif", "#else",
				"#endif", "#define", "#undef", "#line", "#", "defined", "(",
				")", "A", "B", "GL_ES", "__VERSION__", "0", "1", "-", "!",
				"+", "&&", "||", "==", "<", ">=", "/*", "*/", "//", "\\",
				"\"", "\n", " ", "x", "F(x)", "999999999", "2147483647",
				"0x1", "1.0", "\r", "\t"};
		Random random = new Random(7);
		for (int round = 0; round < 20000; ++round) {
			StringBuilder sb = new StringBuilder();
			for (int i = 1 + random.nextInt(60); i-- > 0; ) {
				sb.append(parts[random.nextInt(parts.length)]);
				sb.append(random.nextInt(3) == 0 ? "\n" : " ");
			}
			String text = sb.toString();
			DirectiveRun run = DirectiveRun.of(text, random.nextBoolean()
					? 100 : 300);
			for (int line = 0; line < text.split("\n", -1).length; ++line) {
				run.state(line);
			}
		}
		DirectiveRun.of("#if 1\n".repeat(20000) + "#endif\n".repeat(20000),
				300);
		for (String chain : new String[]{"!".repeat(100000),
				"(".repeat(100000), "- ".repeat(250),
				"(".repeat(120) + "1" + ")".repeat(120)}) {
			DirectiveRun.of("#if " + chain + "1\nx\n#endif\n", 300);
		}
	}

	@Test
	public void tellsNothingOfAGroupLeftOpen() {
		DirectiveRun run = DirectiveRun.of("a\n#if 0\nb\n", 300);
		for (int n = 0; n < 3; ++n) {
			assertEquals(DirectiveRun.State.UNKNOWN, run.state(n));
		}
	}

	// Lines "text | STATE"; the text is what is left of the bar.
	private static void assertStates(int version, String table) {
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
