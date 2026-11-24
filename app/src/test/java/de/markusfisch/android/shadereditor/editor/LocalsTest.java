package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Set;

public class LocalsTest {
	@Test
	public void theInnermostDeclarationBeforeTheUseWins() {
		String t = "void main() {\n\tfloat k = 1.0;\n\t{\n\t\tfloat k = 2.0;\n" +
				"\t\tk += 1.0;\n\t}\n\t{ float k = 3.0; }\n\tk += 2.0;\n}\n";
		assertEquals(t.indexOf("k = 2.0"), at(t, "k += 1.0", "k"));
		// A later sibling block's does not count; the outer one does.
		assertEquals(t.indexOf("k = 1.0"), at(t, "k += 2.0", "k"));
	}

	@Test
	public void aNameDeclaredAfterTheUseIsNotSeen() {
		String t = "float k;\nvoid main() {\n\tk = 1.0;\n\tfloat k = 2.0;\n}\n";
		assertEquals(-1, at(t, "k = 1.0", "k"));
	}

	@Test
	public void aLocalHidesAParameterAndAParameterIsSeenInTheBody() {
		String t = "float f(float x, vec2 p) {\n\tfloat y = x + p.x;\n" +
				"\tfloat x = 2.0;\n\treturn x * y;\n}\n";
		assertEquals(t.indexOf("x, vec2"), at(t, "x + p", "x"));
		assertEquals(t.indexOf("p) {"), at(t, "p.x", "p"));
		assertEquals(t.indexOf("x = 2.0"), at(t, "x * y", "x"));
	}

	@Test
	public void anInitializerSeesTheOuterName() {
		String t = "void main() {\n\tfloat x = 1.0;\n\t{\n\t\tfloat x = x + 1.0;\n\t}\n}\n";
		assertEquals(t.indexOf("x = 1.0"), at(t, "x + 1.0", "x"));
	}

	@Test
	public void aForHeaderDeclaresForItsLoopOnly() {
		String t = "void main() {\n\tfor (int i = 0, j = 1; i < 4; ++i) {\n" +
				"\t\tj += i;\n\t}\n\ti;\n}\n";
		assertEquals(t.indexOf("i = 0"), at(t, "i;\n\t}", "i"));
		assertEquals(t.indexOf("j = 1"), at(t, "j += i", "j"));
		assertEquals(-1, at(t, "i;\n}", "i"));
	}

	@Test
	public void aSingleStatementDeclaresForItselfOnly() {
		String t = "uniform bool c;\nvoid main() {\n\tif (c) float k = 1.0;\n\tk;\n" +
				"\twhile (c) float m = 1.0;\n\tm;\n}\n";
		assertEquals(-1, at(t, "k;", "k"));
		assertEquals(-1, at(t, "m;", "m"));
	}

	@Test
	public void aDoBodyEndsBeforeItsCondition() {
		String t = "void main() {\n\tint n = 0;\n\tdo {\n\t\tint n = 1;\n\t\tn++;\n" +
				"\t} while (n < 3);\n}\n";
		assertEquals(t.indexOf("n = 1"), at(t, "n++", "n"));
		assertEquals(t.indexOf("n = 0"), at(t, "n < 3", "n"));
	}

	@Test
	public void aSwitchBodyIsOneBlock() {
		String t = "uniform int i;\nvoid main() {\n\tswitch (i) {\n\tcase 0:\n" +
				"\t\tfloat k = 1.0;\n\t\tbreak;\n\tcase 1:\n\t\tk = 2.0;\n\t}\n\tk;\n}\n";
		assertEquals(t.indexOf("k = 1.0"), at(t, "k = 2.0", "k"));
		assertEquals(-1, at(t, "k;\n}", "k"));
	}

	@Test
	public void noShortcutWhereAMacroOrADirectiveCouldChangeIt() {
		String t = "void main() {\n\tfloat k = 1.0;\n\tk;\n}\n";
		assertEquals(t.indexOf("k = 1.0"), at(t, "k;", "k"));
		assertEquals(-1, Locals.of(Definitions.index(t, "main.frag"),
				t.indexOf("k;"), "k", Set.of("k")));
		String macro = "void main() {\n\tfloat k = 1.0;\n\tDECL;\n\tk;\n}\n";
		assertEquals(-1, Locals.of(Definitions.index(macro, "main.frag"),
				macro.indexOf("k;"), "k", Set.of("DECL")));
		String directive = "void main() {\n#ifdef A\n\tfloat k = 1.0;\n#endif\n\tk;\n}\n";
		assertEquals(-1, at(directive, "k;", "k"));
		// Outside of a function there is none.
		assertEquals(-1, at("float k = 1.0;\nfloat m = k;\n", "k;", "k"));
	}

	// The second review's macro cases: the shortcut is not taken.
	@Test
	public void theReviewsMacroCasesTakeNoShortcut() {
		for (String[] c : new String[][]{
				{"#define r(a) mat2(cos(a), sin(a), -sin(a), cos(a))\nvoid main() {\n" +
						"\tvec2 p = vec2(1.0);\n\tfloat r = length(p);\n\tp *= r(0.5);\n}\n",
						"r(0.5)", "r"},
				{"float k = 0.5;\n#define DECL float k = 2.0\nuniform int i;\nvoid main() {\n" +
						"\tswitch (i) {\n\tcase 0: DECL;\n\t\tgl_FragColor = vec4(k);\n\t}\n}\n",
						"k);", "k"},
				{"float k = 0.5;\n#define DECL k = 2.0\nvoid main() {\n\tfloat y = 1.0, DECL;\n" +
						"\tgl_FragColor = vec4(k);\n}\n", "k);", "k"},
				{"float k = 0.5;\n#define BEGIN {\n#define END }\nuniform bool c;\nvoid main() {\n" +
						"\tif (c) BEGIN float k = 2.0; gl_FragColor = vec4(k); END\n}\n",
						"k); END", "k"}}) {
			Definitions.Index index = Definitions.index(c[0], "main.frag");
			assertEquals(c[0], -1, Locals.of(index, c[0].indexOf(c[1]), c[2],
					Definitions.macros(java.util.List.of(index))));
		}
	}

	@Test
	public void aFileIncludedInAFunctionIsSeen() {
		java.util.Map<String, String> files = java.util.Map.of(
				"main.frag", "void main() {\n\tfloat k = 2.0;\n#include \"body.glsl\"\n}\n",
				"body.glsl", "float t = k * 2.0;\n");
		assertEquals(true, Definitions.includedInFunction(Definitions.reach(
				files, "body.glsl", (file, path) -> path), (file, path) -> path));
	}

	private static int at(String text, String before, String word) {
		return Locals.of(Definitions.index(text, "main.frag"),
				text.indexOf(before), word, Set.of());
	}
}
