package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * What the reader of declarations does when a text lost a delimiter, as
 * one table: it leaves what is being read only for what can start a
 * declaration outside of it and cannot go on with it. Each context is
 * there twice, written over several lines as it may be, where nothing
 * must be left, and with its delimiter missing, where nothing below
 * must be lost.
 */
public class GlslRecoveryTest {
	// A row: what it is about, the text, and the declarations expected,
	// each as "KIND name", with " // " and its comment if it has one.
	private static final String[][] MATRIX = {
			// ---- An expression outside of functions ------------------
			{"expression, valid, over lines",
					"const mat2 m = mat2(\n" +
							"\tcos(1.0), sin(1.0),\n" +
							"\tfloat(2), vec2(1.0).x);\n" +
							"float after;\n",
					"CONSTANT m", "VARIABLE after"},
			{"expression, valid, a constructor first on its line",
					"vec3 v = normalize(\n" +
							"\tvec3 (1.0));\n" +
							"float after;\n",
					"VARIABLE v", "VARIABLE after"},
			{"expression, valid, an array size over lines",
					"float a[\n\tSIZE\n], b;\nfloat after;\n",
					"VARIABLE a", "VARIABLE b", "VARIABLE after"},
			{"expression, missing ) before a function",
					"float x = sin(\n" +
							"// The start.\n" +
							"void main() {\n}\n" +
							"float after;\n",
					"VARIABLE x", "FUNCTION main // The start.",
					"VARIABLE after"},
			{"expression, missing ] before a function",
					"uniform vec3 lights[4;\n" +
							"highp float f(float a) { return a; }\n",
					"UNIFORM lights", "FUNCTION f(a)"},
			{"expression, missing ) before a uniform",
					"float x = max(1.0,\nuniform float u;\nfloat after;\n",
					"VARIABLE x", "UNIFORM u", "VARIABLE after"},
			{"expression, missing operand before a function",
					"float x = 1.0 +\nvec3 f(vec3 p) {\n\treturn p;\n}\n",
					"VARIABLE x", "FUNCTION f(p)"},
			{"expression, missing ) before a type and a name",
					// Not enough to tell from a parameter: it stays, and
					// the function below is found.
					"float x = sin(\nfloat y = 2.0;\nvoid main() {\n}\n",
					"VARIABLE x", "FUNCTION main"},
			{"expression, missing ) at the end of the text",
					"float before;\nfloat x = sin(", "VARIABLE before",
					"VARIABLE x"},
			{"initializer, valid, braces in braces",
					"float a[2][2] = { {1.0, 2.0},\n\t{3.0, 4.0} };\n" +
							"float after;\n",
					"VARIABLE a", "VARIABLE after"},

			// ---- The parameters of a function ------------------------
			{"parameters, valid, one on each line",
					"float f(\n\tin vec3 p,\n\tfloat t,\n\tRay r)\n{\n" +
							"\treturn t;\n}\nfloat after;\n",
					"FUNCTION f(p, t, r)", "VARIABLE after"},
			{"parameters, valid, a comment before one",
					"float f(vec3 p,\n\t// How far.\n\tfloat t) {\n}\n",
					"FUNCTION f(p, t)"},
			{"parameters, missing ) before the body",
					"float f(vec3 p {\n\treturn p.x;\n}\nfloat after;\n",
					"FUNCTION f(p)", "VARIABLE after"},
			{"parameters, missing ) before a function",
					"float f(vec3 p,\n" +
							"/* Next. */\n" +
							"vec3 g(vec3 q) {\n\treturn q;\n}\n",
					"FUNCTION f(p)", "FUNCTION g(q) // Next."},
			{"parameters, missing ) at the end of the text",
					"float f(vec3 p,\n\tfloat t", "FUNCTION f(p, t)"},

			// ---- The fields of a struct or a block -------------------
			{"fields, valid, one on each line",
					"struct Ray {\n\tvec3 o;\n\tvec3 d;\n\tfloat t[2];\n};\n" +
							"Ray make(vec3 o) {\n}\n",
					"TYPE Ray(o, d, t)", "FUNCTION make(o)"},
			{"fields, valid, a comment before one",
					"uniform Block {\n\t// The light.\n\tvec3 light;\n" +
							"\tfloat power;\n};\nfloat after;\n",
					"UNIFORM light", "UNIFORM power", "VARIABLE after"},
			{"fields, missing } before a function",
					"struct Ray {\n\tvec3 o;\n\tvec3 d;\n" +
							"// Makes one.\n" +
							"Ray make(vec3 o) {\n\treturn Ray(o, o);\n}\n" +
							"float after;\n",
					"TYPE Ray(o, d)", "FUNCTION make(o) // Makes one.",
					"VARIABLE after"},
			{"fields, missing } and ; before a function",
					"struct Ray {\n\tvec3 o\nvec3 f(vec3 p) {\n}\n",
					"TYPE Ray(o)", "FUNCTION f(p)"},
			{"fields, missing } before a uniform",
					"struct Ray {\n\tvec3 o;\nuniform float u;\n",
					"TYPE Ray(o)", "UNIFORM u"},
			{"fields, missing } at the end of the text",
					"float before;\nstruct Ray {\n\tvec3 o;\n\tvec3 d",
					"VARIABLE before", "TYPE Ray(o, d)"},
			{"fields, missing } and more of them than a struct has",
					"struct Ray {\n" + repeat("\tfloat a;\n", 800) +
							"void main() {\n}\n",
					"TYPE Ray", "FUNCTION main"},

			// ---- The body of a function ------------------------------
			{"body, valid, locals of every shape",
					"void main() {\n\tfloat a;\n\tvec3 b = vec3(1.0);\n" +
							"\tRay r;\n\tconst highp float c = f(a,\n" +
							"\t\tb.x);\n\tMACRO(a);\n\tfloat d[2];\n" +
							"\tx = y;\n}\nfloat after;\n",
					"FUNCTION main", "VARIABLE after"},
			{"body, valid, a comment before a local",
					"void main() {\n\t// The colour.\n\tvec3 c;\n" +
							"\tgl_FragColor = vec4 (c, 1.0);\n}\n",
					"FUNCTION main"},
			{"body, valid, a word and a call that are no head",
					"void main() {\n\tEXPORT helper(1.0);\n\treturn\n" +
							"\tfloat (1);\n}\nfloat after;\n",
					"FUNCTION main", "VARIABLE after"},
			{"body, missing } before a function",
					"void first() {\n\tfloat a;\n" +
							"// The second.\n" +
							"float second(float x) {\n\treturn x;\n}\n",
					"FUNCTION first", "FUNCTION second(x) // The second."},
			{"body, missing } before a function over two lines",
					"void first() {\n\tfloat a;\n" +
							"// The second.\n" +
							"highp vec3\nsecond(vec3 x) {\n\treturn x;\n}\n",
					"FUNCTION first", "FUNCTION second(x) // The second."},
			{"body, missing } before a function of a struct",
					"struct Ray { vec3 o; };\nvoid first() {\n\tRay r;\n" +
							"Ray second() {\n}\n",
					"TYPE Ray(o)", "FUNCTION first", "FUNCTION second"},
			{"body, missing } before a uniform",
					"void first() {\n\tfloat a;\nuniform float u;\n" +
							"varying vec2 v;\n",
					"FUNCTION first", "UNIFORM u", "VARIABLE v"},
			{"body, missing } at the end of the text",
					"float before;\nvoid first() {\n\tfloat a;",
					"VARIABLE before", "FUNCTION first"},

			// ---- The branches of a conditional -----------------------
			{"conditional, valid, a head in each branch",
					"#ifdef A\nvoid f() {\n#else\nvoid f(int a) {\n#endif\n" +
							"\tfloat x;\n}\nfloat after;\n",
					"FUNCTION f", "FUNCTION f(a)", "VARIABLE after"},
			{"conditional, valid, a parameter in each branch",
					"float f(vec3 p,\n#ifdef A\n\tfloat t)\n#else\n" +
							"\tint t)\n#endif\n{\n}\n",
					"FUNCTION f(p, t)"},
			{"conditional, missing } in a branch before a function",
					"#ifdef A\nvoid first() {\n\tfloat a;\n" +
							"// The second.\n" +
							"float second(float x) {\n}\n#endif\n" +
							"float after;\n",
					"FUNCTION first", "FUNCTION second(x) // The second.",
					"VARIABLE after"},
			{"conditional, missing ) in a branch before a function",
					"#if X\nfloat x = sin(\n#else\nfloat x = cos(\n#endif\n" +
							"void main() {\n}\n",
					"VARIABLE x", "FUNCTION main"},
			{"conditional, code that is not read",
					"#if 0\nfloat x = sin(\nstruct S {\n#endif\n" +
							"void main() {\n}\n",
					"FUNCTION main"},

			// ---- A declaration that lost its ";" ---------------------
			{"statements, valid, a declaration over lines",
					"uniform\n\thighp\n\tvec3\n\ta,\n\tb;\nRay\nr;\n",
					"UNIFORM a", "UNIFORM b", "VARIABLE r"},
			{"statements, missing ; before a declaration",
					"uniform vec3 a\n// The b.\nuniform vec3 b\n" +
							"float c = 1.0\nvec3 d(vec3 p)\n{\n}\n",
					"UNIFORM a", "UNIFORM b // The b.", "VARIABLE c",
					"FUNCTION d(p)"},
			{"statements, missing ; behind a precision",
					"precision mediump float\nvoid main(void) {\n}\n",
					"FUNCTION main"},
			{"statements, missing , before a function",
					"float a,\nvec3 f(vec3 p) {\n}\n",
					"VARIABLE a", "FUNCTION f(p)"},
	};

	@Test
	public void matrix() {
		for (String[] row : MATRIX) {
			assertEquals(row[0], Arrays.asList(row).subList(2, row.length),
					outline(GlslKnowledge.of(row[1])));
		}
	}

	@Test
	public void everyCutOfEveryRow() {
		// A text that ends anywhere: nothing fails, what is found lies
		// in the text, and what ended before the cut stays found.
		for (String[] row : MATRIX) {
			String text = row[1];
			if (text.length() > 2000) {
				continue;
			}
			int found = 0;
			for (int cut = 0; cut <= text.length(); ++cut) {
				GlslKnowledge known = GlslKnowledge.of(text.substring(0,
						cut));
				for (Symbol symbol : known.symbols()) {
					assertTrue(row[0] + " at " + cut, symbol.from() >= 0 &&
							symbol.from() <= symbol.start() &&
							symbol.start() < symbol.end() &&
							symbol.end() <= symbol.to() &&
							symbol.to() <= cut);
				}
				found = Math.max(found, known.symbols().size());
			}
			assertTrue(row[0], found >= row.length - 2);
		}
	}

	@Test
	public void whatIsWrittenOverLinesIsLeftAlone() {
		// The rows of valid text again, with every line end doubled and
		// with none: the layout tells nothing a delimiter does not.
		for (String[] row : MATRIX) {
			if (!row[0].contains("valid") ||
					row[0].startsWith("statements")) {
				continue;
			}
			List<String> expected = names(GlslKnowledge.of(row[1]));
			assertEquals(row[0], expected, names(GlslKnowledge.of(
					row[1].replace("\n", "\n\n"))));
			if (!row[1].contains("//") && !row[1].contains("#")) {
				assertEquals(row[0], expected, names(GlslKnowledge.of(
						row[1].replace("\n", " "))));
			}
		}
	}

	private static List<String> names(GlslKnowledge known) {
		List<String> list = new ArrayList<>();
		for (Symbol symbol : known.symbols()) {
			list.add(symbol.kind() + " " + symbol.name());
		}
		return list;
	}

	// A function and a struct with the names of their parts, if any.
	private static List<String> outline(GlslKnowledge known) {
		List<String> list = new ArrayList<>();
		for (Symbol symbol : known.symbols()) {
			StringBuilder sb = new StringBuilder();
			sb.append(symbol.kind()).append(' ').append(symbol.name());
			if (!symbol.parts().isEmpty() && symbol.parts().size() < 9) {
				sb.append('(');
				for (Symbol part : symbol.parts()) {
					sb.append(sb.charAt(sb.length() - 1) == '(' ? "" : ", ")
							.append(part.name());
				}
				sb.append(')');
			}
			if (symbol.documentation() != null) {
				sb.append(" // ").append(symbol.documentation());
			}
			list.add(sb.toString());
		}
		return list;
	}

	private static String repeat(String text, int times) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < times; ++i) {
			sb.append(text);
		}
		return sb.toString();
	}
}
