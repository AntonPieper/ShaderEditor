package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * Where a declaration is used, in one file. A row is what it is about
 * and a text in which the declaration asked about has "@" before its
 * name; every place expected is marked the same way before the name:
 * "@" one that is certain, "@!" one that is listed, with the reasons in
 * the row's last entries in their order. A word of the name without a
 * mark is no place at all.
 */
public class UsesTest {
	private static final String V3 = "#version 300 es\n";

	private static final String[][] ROWS = {
			{"a global, and a local that hides it",
					"float @speed = 1.0;\n" +
							"float f(float t) {\n\treturn t * @speed;\n}\n" +
							"float g() {\n\tvec2 speed = vec2(1.0);\n" +
							"\treturn speed.x;\n}\n" +
							"void main() {\n\tfloat a = @speed;\n\t{\n" +
							"\t\tint speed = 2;\n\t\tspeed;\n\t}\n" +
							"\ta += @speed; // not speed here\n}\n"},
			{"a local, in its scope only",
					"float d;\nvoid main() {\n\tfloat x = d;\n" +
							"\tvec3 @d = vec3(1.0);\n\tx = @d.x + @d.y;\n" +
							"\tfor (int i = 0; i < 2; ++i) {\n" +
							"\t\t@d *= 2.0;\n\t}\n}\n" +
							"float after() { return d; }\n"},
			{"a local is seen behind its initializer",
					"int @x;\nvoid main() {\n\t@x++;\n" +
							"\t{\n\t\tint x = @x + 1;\n\t\tx++;\n\t}\n" +
							"\t@x++;\n\tint x;\n\tx++;\n}\n"},
			{"a parameter",
					"float f(float @t, float u) {\n\treturn @t * u + @t;\n}\n" +
							"float g(float t) { return t; }\n"},
			{"a parameter hides a global, a function and a struct",
					"float @f();\nstruct S { float x; };\n" +
							"void g(int f) { f++; }\n" +
							"void h() { int f; f++; int S; S++; }\n" +
							"void i() { @f(); }\n"},
			{"a local hides a function behind its initializer",
					"float @f();\nvoid g() { float f = @f(); f; }\n"},
			{"what a for declares is seen in all its statement",
					"int @x;\nvoid f() {\n" +
							"\tfor (int x = 0; x < 2; x++) if (true) x++; " +
							"else x--;\n\t@x++;\n" +
							"\tfor (int x = 0; x < 2; x++) { x++; }\n\t@x++;\n" +
							"\tfor (int x = 0; x < 2; x++)\n" +
							"\t\tfor (int y = 0; y < x; y++) x += y;\n" +
							"\t@x++;\n}\n"},
			{"what the condition of a while declares",
					"bool @x;\nvoid f() {\n" +
							"\twhile (bool x = true) { x = false; }\n" +
							"\t@x = false;\n\twhile (@x) @x = false;\n}\n"},
			{"a do and its while",
					"int @x;\nvoid f() {\n" +
							"\tdo { int x = 1; x++; } while (@x < 2);\n" +
							"\tdo @x++; while (@x < 2);\n\t@x++;\n}\n"},
			{"an if, its else and what they declare",
					"int @x;\nvoid f(bool c) {\n" +
							"\tif (c) { int x = 1; x++; } else { @x++; }\n" +
							"\tif (c) @x++; else if (!c) { int x; x++; } " +
							"else @x--;\n\t@x++;\n}\n"},
			{"a switch is one scope",
					V3 + "int @x;\nvoid f(int k) {\n\tswitch (k) {\n" +
							"\t\tcase 0: int x = 1; x++; break;\n" +
							"\t\tdefault: x++;\n\t}\n\t@x++;\n}\n"},
			{"a function with one form",
					"float @map(vec3 p);\nvoid main() {\n" +
							"\tfloat d = @map(vec3(0.0)) + @map(vec3(1.0));\n" +
							"}\nfloat @map(vec3 p) {\n\treturn p.x;\n}\n"},
			{"a function with other forms: its arguments tell",
					"float @sd(vec3 p) { return p.x; }\n" +
							"float sd(vec2 p) { return p.x; }\n" +
							"void main() {\n\tvec2 uv;\n\tvec3 q;\n" +
							"\tfloat a = @sd(q) + sd(uv) + @sd(vec3(uv, 1.0));\n" +
							"\tfloat b = @!sd(unknown);\n\tfloat c = @sd(\n" +
							"\t\tq);\n}\n",
					"AMBIGUOUS"},
			{"a function is seen from its head on",
					"float f(float a) { return a; }\n" +
							"float g() { return f(1.0); }\n" +
							"float @f(vec2 a) { return @f(a) + f(1.0); }\n" +
							"float h() { return @f(vec2(1.0)); }\n"},
			{"the product of a matrix that is not square",
					V3 + "float f(vec2 p);\nfloat @f(vec3 p);\n" +
							"void g() {\n\tmat2x3 m;\n\tvec2 v;\n\tvec3 w;\n" +
							"\t@f(m * v);\n\tf(w * m);\n}\n"},
			{"forms that differ by the size of an array",
					"const int K = 3;\nfloat f(float a[3]);\n" +
							"float @f(float a[4]);\nvoid g() {\n" +
							"\tfloat b[4];\n\tfloat c[K + 1];\n\tfloat d[3];\n" +
							"\t@f(b);\n\t@!f(c);\n\tf(d);\n}\n",
					"AMBIGUOUS"},
			{"a struct and its field",
					"struct @Ray { vec3 o; vec3 d; };\n" +
							"@Ray make(vec3 o) {\n\treturn @Ray(o, o);\n}\n" +
							"void main() {\n\t@Ray r = make(vec3(0.0));\n}\n" +
							"struct Hit { @Ray ray; float Ray; };\n" +
							"float far(@Ray r, @Ray);\n"},
			{"a field: of values of its struct",
					"struct Ray { vec3 o; vec3 @d; };\n" +
							"struct Hit { float d; };\n" +
							"Ray make();\nvoid main() {\n" +
							"\tRay r;\n\tHit h;\n\tvec3 d;\n" +
							"\td = r.@d + make().@d * h.d;\n" +
							"\tr.@d.x = h.d;\n\tunknown.@!d = 1.0;\n}\n",
					"UNTYPED"},
			{"a name where something is declared is no use",
					V3 + "const int @n = 2;\n" +
							"struct S { float n; float m[@n]; };\n" +
							"float g(float n);\n" +
							"float h(float a[@n], float[@n] b);\n" +
							"layout(std140, n) uniform;\n" +
							"layout(n = 1) uniform float u;\n" +
							"float k[@n], j = float(@n), n2;\n" +
							"float i(S s) { return s.n; }\n"},
			{"the members of a block without a name are globals",
					V3 + "uniform Block { float @x; float y; };\n" +
							"void f() { @x; float x; x; }\nfloat z = @x;\n"},
			{"a macro that is a number is replaced, where it is defined",
					"float N;\n#define @N 3\nfloat a[@N];\n" +
							"#ifdef @N\nfloat b = float(@N);\n#endif\n" +
							"#if @N\nfloat c;\n#endif\n" +
							"// N in a comment\n#pragma N\n" +
							"#undef @N\nfloat d = N;\n"},
			{"an array of the size a macro has that is a number",
					"#define N 3\nfloat @a[N];\n" +
							"void f() { @a[0] = 1.0; float a[N]; a[1]; }\n" +
							"float b = @a[2];\n"},
			{"a function with a parameter of the size a macro has",
					"#define N 3\nfloat @sum(float v[N]) { return v[0]; }\n" +
							"void f() { float w[N]; @sum(w); }\n"},
			{"a field of the size a macro has that is a number",
					"#define N 3\nstruct S { float @v[N]; };\n" +
							"void f() { S s; s.@v[0] = 1.0; }\n"},
			{"a number under the name of a variable",
					"float @x;\nvoid e() { @x; }\n#define x (-1.0)\n" +
							"void f() { float y = 2.0-x; }\n#undef x\n" +
							"void g() { @x; }\n"},
			{"a macro defined again, and what means that one",
					"#define @X 1\nint a = @X;\n#undef @X\n" +
							"#define @!X 2\nint b = @!X;\n",
					"REDECLARED", "REDECLARED"},
			{"behind a macro that was not executed nothing is proven",
					"float @x;\n#define TWICE (@!x * 2.0)\n" +
							"void f() {\n\t@x;\n\tTWICE;\n\t@!x;\n}\n" +
							"void g() { @!x; }\n",
					"IN_MACRO", "IN_MACRO", "IN_MACRO"},
			{"a macro with parameters, asked about",
					"#define @SAT(v) clamp(v, 0.0, 1.0)\n" +
							"float a = @SAT(1.0);\nfloat b = @!SAT(2.0);\n",
					"IN_MACRO"},
			{"a macro passed to a macro declares the name",
					"float @x;\n#define CALL(m) m(1.)\n" +
							"#define DECL(v) float @!x = v;\n" +
							"void f() { CALL(DECL) @!x++; }\n",
					"IN_MACRO", "IN_MACRO"},
			{"a type as the argument of a macro declares the name",
					"float @x;\n#define ID(a) a\n" +
							"void f() { ID(float) @!x = 1.; @!x++; }\n",
					"IN_MACRO", "IN_MACRO"},
			{"a macro named like a type",
					V3 + "float f(vec2 p);\nfloat @f(vec3 p);\n" +
							"#define vec3 vec2\n" +
							"void g() { vec3 v = vec3(1.); @!f(v); }\n",
					"IN_MACRO"},
			{"a type written with a macro",
					"struct S { float @x; };\n#define SS S\n" +
							"void g() { SS s; s.@!x = 1.; }\n",
					"IN_MACRO"},
			{"a define that goes on before its parenthesis has parameters",
					V3 + "float @X;\n#define X\\\n(a) a\n" +
							"void f() { @X = 1.; @!X; }\n",
					"IN_MACRO"},
			{"the text of a macro that goes on over lines",
					V3 + "float @x;\n#define SUM(a) \\\n\t((a) + \\\n\t@!x)\n" +
							"float y = @x;\n",
					"IN_MACRO"},
			{"a macro that an arm that is not decided defines again",
					"float @x;\n#define DECL float @!x = 1.;\n#define N 3.\n" +
							"#ifdef GL_EXT_shader_texture_lod\n#undef N\n" +
							"#define N DECL\n#endif\n" +
							"void f() { N; @!x++; }\n",
					"IN_MACRO", "IN_MACRO"},
			{"what a character that is no ASCII is to the compiler",
					"float @x;\nvoid f() { float\u00a0x = 1.; x++; }\n" +
							"void g() { float x\u00e9 = 1.; x++; }\n" +
							"void h() { @x++; } // \uD83D\uDE00 x\n" +
							"float \uD83D\uDE00 y = @x;\n"},
			{"code that is not compiled",
					"float @far = 9.0;\n#if 0\nfloat near = @!far;\n#else\n" +
							"float near = @far * 0.1;\n#endif\n" +
							"#if 0 // off\n#if 1\nfloat a = @!far;\n#endif\n" +
							"float b = @!far;\n#elif 0\nfloat c = @!far;\n" +
							"#endif\nfloat c = @far;\n",
					"INACTIVE", "INACTIVE", "INACTIVE", "INACTIVE"},
			{"a declaration that is not compiled: nothing is certain",
					"float x;\n#ifdef MISSING\nfloat @!far = 9.0;\n" +
							"float near = @!far * 0.1;\n#endif\n" +
							"float far2 = 1.0;\n",
					"INACTIVE", "INACTIVE"},
			{"what is defined decides an arm",
					"#define SOFT\n#define GONE\n#undef GONE\n" +
							"float @far = 9.0;\n" +
							"#ifdef SOFT\nfloat a = @far;\n#else\n" +
							"float a = @!far;\n#endif\n" +
							"#ifdef HARD\nfloat b = @!far;\n#else\n" +
							"float b = @far;\n#endif\n" +
							"#ifdef GL_ES\nfloat d = @far;\n#endif\n" +
							"#if defined(GONE) || !defined SOFT\n" +
							"float e = @!far;\n#endif\n" +
							"#if !(defined(HARD) && 1) && (0 || 2)\n" +
							"float g = @far;\n#endif\n" +
							"#ifdef SHADER_EDITOR\nfloat h = @far;\n#endif\n",
					"INACTIVE", "INACTIVE", "INACTIVE"},
			{"what a compiler of version 300 defines",
					V3 + "float @x;\n#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
							"float a = @x;\n#else\nfloat b = @!x;\n#endif\n" +
							"#if __VERSION__\nfloat c = @x;\n#endif\n",
					"INACTIVE"},
			{"what a compiler of version 100 may define",
					"float @x;\n#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
							"float a = @!x;\n#else\nfloat b = @!x;\n#endif\n" +
							"float c = @!x;\n",
					"CONDITIONAL", "CONDITIONAL", "CONDITIONAL"},
			{"a name with two underscores is the compiler's to define",
					"float @x;\n#ifdef MY__FLAG\nfloat y;\n#endif\n" +
							"float a = @x;\n#ifdef MY__FLAG\nfloat b = @!x;\n" +
							"#endif\nfloat c = @!x;\n",
					"CONDITIONAL", "CONDITIONAL"},
			{"a condition the text does not decide: in and behind it",
					"float @x;\nfloat a() { return @x; }\n" +
							"void f() {\n\t@x;\n#if QUALITY > 1\n" +
							"\tfloat a = @!x;\n#else\n\tfloat b = @!x;\n" +
							"#endif\n\tfloat c = @!x;\n}\n" +
							"float g() { return @x; }\n",
					"CONDITIONAL", "CONDITIONAL", "CONDITIONAL"},
			{"a condition that is not decided and does not name it",
					"float @x;\nvoid f() {\n\tfloat y;\n" +
							"#if QUALITY > 1\n\ty = 1.0;\n#endif\n" +
							"\ty = @x;\n}\n"},
			{"arms that are not whole statements",
					"float @x;\n#if QUALITY > 1\nvoid f() {\n#else\n" +
							"void f(int a) {\n#endif\n\t@!x;\n}\n" +
							"float y = @!x;\n",
					"CONDITIONAL", "CONDITIONAL"},
			{"a type that an arm that is not decided may have changed",
					"float f(float p);\nfloat @f(vec2 p);\n" +
							"void g() {\n#if QUALITY > 1\n\tfloat v;\n#else\n" +
							"\tvec2 v;\n#endif\n\t@!f(v);\n}\n",
					"AMBIGUOUS"},
	};

	@Test
	public void usesOfADeclaration() {
		for (String[] row : ROWS) {
			List<String> expected = new ArrayList<>();
			List<Integer> listed = new ArrayList<>();
			String text = unmark(row[1], expected, listed);
			assertEquals(row[0], listed.size(), row.length - 2);
			for (int i = 0; i < listed.size(); ++i) {
				expected.set(listed.get(i), expected.get(listed.get(i))
						.replace("?", row[2 + i]));
			}
			// The declaration is the first place marked.
			int at = Integer.parseInt(expected.get(0).split(" ")[0]);
			Symbol symbol = UsesHarness.declared(text, at);
			ProjectKnowledge.Uses uses = UsesHarness.uses(text, at);
			assertNotNull(row[0], uses);
			List<String> got = new ArrayList<>();
			for (ProjectKnowledge.Place place : uses.places()) {
				assertEquals(row[0], UsesHarness.MAIN,
						place.file().toString());
				assertEquals(row[0], symbol.name(), text.substring(
						place.offset(), place.offset() + place.end() -
								place.start()));
				assertEquals(row[0], symbol.name(), place.text().substring(
						place.start(), place.end()));
				got.add(place.offset() + " " + (place.certain() ? "certain"
						: place.why()));
			}
			assertEquals(row[0], expected, got);
		}
	}

	@Test
	public void whatCannotBeToldExactlyIsNotTold() {
		String[] texts = {
				// A statement of more tokens than are kept.
				"float x;\nvoid f() {\n\tfloat a = " +
						"1.0 + ".repeat(1100) + "1.0, x = 0.0;\n\tx++;\n}\n",
				// More parameters than a symbol keeps.
				"float x;\nvoid f(" + "float p, ".repeat(256) +
						"float x) { x++; }\n",
				// A body that lost its brace, and one closed too often.
				"float x;\nvoid f() {\n\tif (x > 0.0) {\n\t\tx++;\n}\n" +
						"void g() { x++; }\n",
				"float x;\nvoid f() { x++; } }\nvoid g() { x++; }\n",
				// A struct without a name, and one in a body.
				"float x;\nstruct { float x; } s;\n",
				"float x;\nvoid f() { struct T { float x; }; }\n",
				// Code that declares nothing.
				"float x;\nx = 1.0;\n",
				// A conditional that does not end, or ends too often.
				"float x;\n#if 1\nfloat a = x;\n",
				"float x;\n#endif\nfloat a = x;\n",
				// A macro defined again with another text, and an
				// "#error" that is compiled.
				"float x;\n#define N 1\n#define N 2\n",
				"float x;\n#error no\n",
				// In version 100: a line that goes on in the next, also
				// a comment; and a word that only version 300 knows.
				"float x;\n// in C:\\shaders\\\nfloat y = x;\n",
				"float x;\n#define A \\\n 1\n",
				"float x;\nstruct centroid { float a; };\n",
				// In version 300: a name across a join, and a comment
				// that is open where a directive's line ends.
				V3 + "float x;\nfloat x\\\ny = x;\n",
				V3 + "float x;\n#define A 1. /* a\nb */ + x\n",
				// A file that is missing.
				"float x;\n#include \"no.glsl\"\n",
		};
		for (String text : texts) {
			assertNull(text.substring(0, Math.min(70, text.length())),
					UsesHarness.uses(text, text.indexOf("x;")));
		}
	}

	// The text without its marks; expected gets, for each mark, where
	// the name stands and "certain" or "?" for a listed one, whose
	// index in expected goes to listed.
	static String unmark(String text, List<String> expected,
			List<Integer> listed) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); ++i) {
			char c = text.charAt(i);
			if (c != '@') {
				sb.append(c);
			} else if (text.charAt(i + 1) == '!') {
				++i;
				listed.add(expected.size());
				expected.add(sb.length() + " ?");
			} else {
				expected.add(sb.length() + " certain");
			}
		}
		return sb.toString();
	}
}
