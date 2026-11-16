package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * What a name means at a place: the locals and parameters of bodies,
 * and where each can be seen.
 */
public class GlslLocalsTest {
	// Rows: what it is about, a text with "|" behind each word asked
	// about, and for each what it means there as "KIND type name@line",
	// or "-" for nothing.
	private static final String[][] ROWS = {
			{"a local hides a global, in its function only",
					"float d;\nvoid main() {\n\tvec3 d = vec3(1.0);\n" +
							"\tgl_FragColor = vec4(d|, 1.0);\n}\n" +
							"float f() {\n\treturn d|;\n}\n",
					"VARIABLE vec3 d@2", "VARIABLE float d@0"},
			{"a parameter, in its body",
					"float f(in vec3 p, float t) {\n\treturn p|.x * t|;\n}\n" +
							"float g() { return p|; }\n",
					"PARAMETER vec3 p@0", "PARAMETER float t@0", "-"},
			{"not before it is declared",
					"float x;\nvoid main() {\n\tfloat y = x|;\n" +
							"\tfloat x = 2.0;\n\ty = x|;\n}\n",
					"VARIABLE float x@0", "VARIABLE float x@3"},
			{"not in its own initializer",
					"void main() {\n\tint x = 1;\n\t{\n" +
							"\t\tint x = x| + 1;\n\t\tx|;\n\t}\n\tx|;\n}\n",
					"VARIABLE int x@1", "VARIABLE int x@3",
					"VARIABLE int x@1"},
			{"where its name is declared",
					"float x;\nvoid main() {\n\tvec2 x| = vec2(0.0);\n}\n",
					"VARIABLE vec2 x@2"},
			{"a block ends what it declares",
					"void main() {\n\t{\n\t\tfloat a;\n\t\ta|;\n\t}\n\ta|;\n" +
							"\tif (true) {\n\t\tvec3 a;\n\t} else {\n" +
							"\t\ta|;\n\t}\n}\n",
					"VARIABLE float a@2", "-", "-"},
			{"the header of a for, with a block",
					"void main() {\n\tfor (int i = 0; i| < 3; ++i|) {\n" +
							"\t\tfloat f = float(i|);\n\t}\n\ti|;\n" +
							"\tfor (int i = 9; i > 0; --i) {\n\t\ti|;\n\t}\n}\n",
					"VARIABLE int i@1", "VARIABLE int i@1",
					"VARIABLE int i@1", "-", "VARIABLE int i@5"},
			{"the header of a for, with one statement",
					"void main() {\n\tfloat s;\n" +
							"\tfor (int i = 0; i < 3; ++i)\n" +
							"\t\ts += float(i|);\n\ti|;\n" +
							"\tfor (int j = 0; j < 3; ++j)\n" +
							"\t\tfor (int k = 0; k < 3; ++k)\n" +
							"\t\t\ts += float(j| * k|);\n\tj|; k|;\n}\n",
					"VARIABLE int i@2", "-", "VARIABLE int j@5",
					"VARIABLE int k@6", "-", "-"},
			{"what a for declares is seen in all its statement",
					"void main() {\n\tfor (int x = 0; x < 2; x++) " +
							"if (true) x|++; else x|--;\n\tx|;\n}\n",
					"VARIABLE int x@1", "VARIABLE int x@1", "-"},
			{"what the condition of a while declares",
					"void main() {\n\twhile (bool x = true) { x| = false; }" +
							"\n\tx|;\n}\n",
					"VARIABLE bool x@1", "-"},
			{"a do and its while",
					"void main() {\n\tdo { int x = 1; x|; } " +
							"while (x| < 2);\n\tdo x|; while (true);\n}\n",
					"VARIABLE int x@1", "-", "-"},
			{"the statement of an if is a scope, and so is its else",
					"void main() {\n\tif (true) int x = 1; else x|;\n" +
							"\tx|;\n\tif (true) {} else if (false) int y = 1; " +
							"else y|;\n\ty|;\n}\n",
					"-", "-", "-", "-"},
			{"a branch of a conditional starts where its first one did",
					"float x;\nvoid main() {\n#ifdef A\n\tint x;\n" +
							"\tx|;\n#else\n\tx|;\n#endif\n\tx|;\n}\n",
					"VARIABLE int x@3", "VARIABLE float x@0",
					"VARIABLE float x@0"},
			{"several of one type, arrays, constants",
					"void main() {\n\tfloat a = 1.0, b[2], c;\n" +
							"\tconst highp vec3 up = vec3(0.0);\n" +
							"\tRay[2] rays;\n\ta|; b|; c|; up|; rays|;\n}\n",
					"VARIABLE float a@1", "VARIABLE float[2] b@1",
					"VARIABLE float c@1", "CONSTANT vec3 up@2",
					"VARIABLE Ray[2] rays@3"},
			{"types the language does not know",
					"void main() {\n\tPREC float x = 1.0;\n\tfloat2 q;\n" +
							"\tx|; q|;\n}\n",
					"VARIABLE float x@1", "VARIABLE float2 q@2"},
			{"what declares nothing",
					"void main() {\n\ta = b;\n\tfoo(x);\n\tx[0] = 1.0;\n" +
							"\treturn a * b;\n\tEXPORT helper(c);\n" +
							"\ta|; b|; x|; c|; foo|; helper|;\n}\n",
					"-", "-", "-", "-", "-", "-"},
			{"each function has its own",
					"float f() {\n\tfloat v = 1.0;\n\treturn v|;\n}\n" +
							"vec3 g() {\n\tvec3 v = vec3(1.0);\n" +
							"\treturn v|;\n}\nvoid main() { v|; }\n",
					"VARIABLE float v@1", "VARIABLE vec3 v@5", "-"},
			{"a body that lost its brace",
					"void first() {\n\tfloat a;\n\tif (a > 0.0) {\n" +
							"\t\tfloat b;\n" +
							"float second(float x) {\n\ta|; b|;\n" +
							"\treturn x|;\n}\n",
					"-", "-", "PARAMETER float x@4"},
			{"both branches of a conditional",
					"void main() {\n#ifdef A\n\tfloat a = 1.0;\n" +
							"\tif (a > 0.0) {\n#else\n\tvec2 a = vec2(1.0);\n" +
							"\tif (a.x > 0.0) {\n#endif\n\t\ta|;\n\t}\n}\n",
					"VARIABLE vec2 a@5"},
			{"a struct's variable and what a switch declares",
					"struct Ray { vec3 o; };\nvoid main() {\n" +
							"\tRay r = Ray(vec3(0.0));\n\tswitch (1) {\n" +
							"\tcase 1:\n\t\tint n = 2;\n\t\tn|;\n\t\tbreak;\n" +
							"\t}\n\tr|; n|;\n}\n",
					"VARIABLE int n@5", "VARIABLE Ray r@2", "-"},
	};

	@Test
	public void whatANameMeansAtAPlace() {
		for (String[] row : ROWS) {
			List<Integer> marks = new ArrayList<>();
			String text = unmark(row[1], marks);
			GlslKnowledge known = GlslKnowledge.of(text);
			List<String> found = new ArrayList<>();
			for (int mark : marks) {
				int start = mark;
				while (start > 0 && (Character.isLetterOrDigit(text.charAt(
						start - 1)) || text.charAt(start - 1) == '_')) {
					--start;
				}
				// From anywhere in the word, and from its end.
				String word = text.substring(start, mark);
				List<Symbol> at = known.at(start, word);
				assertEquals(row[0], at, known.at(mark, word));
				found.add(at.isEmpty() ? "-" : at.get(0).kind() + " " +
						at.get(0).type() + " " + at.get(0).name() + "@" +
						lineOf(text, at.get(0).start()));
				if (!at.isEmpty()) {
					assertEquals(row[0], word, text.substring(
							at.get(0).start(), at.get(0).end()));
				}
			}
			assertEquals(row[0], Arrays.asList(row).subList(2, row.length),
					found);
			// What the reader had to recover from is not all there is
			// to know.
			assertEquals(row[0], !row[0].contains("lost its brace"),
					known.complete());
			// Asked by the name alone, nobody sees a local.
			for (int mark : marks) {
				for (Symbol symbol : known.at(-1, "a")) {
					assertTrue(row[0], symbol.from() == 0 ||
							text.charAt(symbol.from() - 1) == '\n');
				}
			}
		}
	}

	@Test
	public void theTypeOfAnExpressionAtAPlace() {
		String text = "struct Ray { vec3 o; vec3 d; };\n" +
				"uniform vec2 resolution;\n" +
				"float map(vec3 p);\n" +
				"vec3 shade(Ray r, float t) {\n" +
				"\tvec3 p = r.o + r.d * t;\n" +
				"\tfloat t = map(p);\n" +
				"\t|\n" +
				"}\n" +
				"void main() {\n" +
				"\tvec2 t = gl_FragCoord.xy / resolution;\n" +
				"\t|\n" +
				"}\n";
		List<Integer> marks = new ArrayList<>();
		text = unmark(text, marks);
		GlslKnowledge known = GlslKnowledge.of(text);
		GlslExpression.Names inShade = known.names(marks.get(0),
				Knowledge.Scope.NONE);
		assertEquals("vec3", type("p", inShade));
		assertEquals("float", type("t", inShade));
		assertEquals("vec3", type("r.d * t + p", inShade));
		assertEquals("float", type("map(r.o)", inShade));
		assertEquals("vec3", type("shade(r, t)", inShade));
		assertEquals("vec2", type("resolution", inShade));
		GlslExpression.Names inMain = known.names(marks.get(1),
				Knowledge.Scope.NONE);
		assertEquals("vec2", type("t", inMain));
		assertEquals("float", type("t.x * resolution.y", inMain));
		assertEquals("vec4", type("gl_FragCoord", inMain));
		assertEquals(null, type("p", inMain));
		assertEquals(null, type("r.o", inMain));
	}

	// A type a macro writes is whatever the macro makes of it: it is
	// not known, and neither is a function with such a type.
	@Test
	public void aTypeWrittenWithAMacroIsNotKnown() {
		String text = "#define VEC vec2\n#define vec3 vec2\n" +
				"struct S { float x; };\n#define SS S\n" +
				"float f(VEC p);\nfloat g(vec2 p);\n" +
				"void main() {\n\tVEC a;\n\tvec3 b;\n\tSS s;\n\tS t;\n" +
				"\tvec2 c;\n\tVEC d[2];\n\t|\n}\n";
		List<Integer> marks = new ArrayList<>();
		text = unmark(text, marks);
		GlslExpression.Names names = GlslKnowledge.of(text).names(
				marks.get(0), Knowledge.Scope.NONE);
		assertEquals(null, type("a", names));
		assertEquals(null, type("b", names));
		assertEquals(null, type("s", names));
		assertEquals(null, type("s.x", names));
		assertEquals(null, type("d[0]", names));
		assertEquals(null, type("f(c)", names));
		assertEquals("float", type("t.x", names));
		assertEquals("vec2", type("c", names));
		assertEquals("float", type("g(c)", names));
		// Also a macro of a file around, which is asked about a type
		// word that neither the language nor the text knows.
		String[] asked = {""};
		Knowledge.Scope outside = new Knowledge.Scope() {
			@Override
			public List<String> names() {
				return List.of("REAL");
			}

			@Override
			public List<Symbol> find(CharSequence word) {
				asked[0] += word + " ";
				return word.toString().equals("REAL")
						? GlslKnowledge.of("#define REAL float\n").symbols()
						: List.of();
			}

			@Override
			public int line(Symbol symbol) {
				return 0;
			}
		};
		text = "void main() {\n\tREAL r;\n\tvec3 v;\n\tOther o;\n\t|\n}\n";
		marks.clear();
		text = unmark(text, marks);
		names = GlslKnowledge.of(text).names(marks.get(0), outside);
		assertEquals(null, type("r", names));
		assertEquals("vec3", type("v", names));
		assertEquals("Other", type("o", names));
		assertEquals("REAL Other ", asked[0]);
	}

	// Of several declarations of a name outside of functions, a place
	// means the one declared last before it.
	@Test
	public void theDeclarationLastBeforeAPlace() {
		String text = "float x;\nvoid e() { x|; }\n#define x| 1.0\n" +
				"void f() { float a = x|; }\n" +
				"#define y 2.0\n#undef y\nfloat y|;\n" +
				"void g() { y|++; h|(1.0); }\n" +
				"float h(float a);\nfloat h(vec2 a);\n" +
				"void i() { h|(1.0); }\n";
		List<Integer> marks = new ArrayList<>();
		text = unmark(text, marks);
		GlslKnowledge known = GlslKnowledge.of(text);
		String[] expected = {"VARIABLE x@0", "MACRO x@2", "MACRO x@2",
				"VARIABLE y@6", "VARIABLE y@6",
				// Not declared before it: what the text declares at all.
				"FUNCTION h@8 FUNCTION h@9", "FUNCTION h@8 FUNCTION h@9"};
		for (int i = 0; i < expected.length; ++i) {
			int mark = marks.get(i);
			StringBuilder found = new StringBuilder();
			for (Symbol symbol : known.at(mark, text.substring(mark - 1,
					mark))) {
				found.append(found.length() > 0 ? " " : "")
						.append(symbol.kind()).append(' ')
						.append(symbol.name()).append('@')
						.append(lineOf(text, symbol.start()));
			}
			assertEquals("mark " + i, expected[i], found.toString());
		}
	}

	private static String type(String text, GlslExpression.Names names) {
		GlslStatement s = GlslStatementTest.statement(text);
		return GlslExpression.type(s, 0, s.size(), names);
	}

	@Test
	public void whoReadsNoBodiesHasNoLocals() throws IOException {
		// The editor release builds ship reads from the tokens of the
		// whole text: the same declarations, and no body is read.
		String text = "float d;\nvoid main() {\n\tvec3 d = vec3(1.0);\n}\n";
		GlslKnowledge whole = GlslKnowledge.of(GlslLexer.lex(text), text);
		assertEquals("float", whole.at(text.indexOf("d = vec3"), "d").get(0)
				.type());
		for (String shader : BundledShaders.read().values()) {
			assertEquals(GlslKnowledge.of(shader).symbols(),
					GlslKnowledge.of(GlslLexer.lex(shader), shader)
							.symbols());
		}
	}

	@Test
	public void localsHaveALimitOfTheirOwn() {
		StringBuilder sb = new StringBuilder("void main() {\n");
		for (int i = 0; i < 100; ++i) {
			sb.append("\tfloat local").append(i).append(";\n");
		}
		sb.append("}\n");
		for (int i = 0; i < 20; ++i) {
			sb.append("float global").append(i).append(";\n");
		}
		String text = sb.toString();
		int inMain = text.indexOf("}");
		// Ten symbols and so ten locals: the rest of them is not kept,
		// and the knowledge says that it is not all there is.
		GlslKnowledge few = read(text, 10, 1 << 20);
		assertEquals(10, few.symbols().size());
		assertFalse(few.at(inMain, "local9").isEmpty());
		assertTrue(few.at(inMain, "local10").isEmpty());
		assertFalse(few.complete());
		// No local costs a declaration its name: names come first.
		GlslKnowledge tight = read(text, 1000, 4 * (4 + 20 * 8));
		assertEquals(21, tight.symbols().size());
		assertEquals("global19", tight.symbols().get(20).name());
		assertFalse(tight.complete());
		assertTrue(read(text, 1000, 1 << 20).complete());
	}

	// A statement keeps 2,048 tokens. One that had more where a token
	// can declare something makes the knowledge say that it is not all
	// there is; a table of numbers of any length does not.
	@Test
	public void aStatementThatHadMoreThanItKeeps() {
		StringBuilder numbers = new StringBuilder();
		for (int i = 0; i < 3000; ++i) {
			numbers.append(i == 0 ? "" : ", ").append(i).append(".0");
		}
		GlslKnowledge table = GlslKnowledge.of(
				"const float table[3000] = float[3000](" + numbers + ");\n" +
						"void main() {\n\tfloat local[3000] = float[3000](" +
						numbers + ");\n\tlocal;\n}\nfloat after;\n");
		assertTrue(table.complete());
		assertEquals(Arrays.asList("table", "main", "after"),
				names(table));
		// A name behind the table is one it could not keep.
		GlslKnowledge behind = GlslKnowledge.of(
				"const float table[3000] = float[3000](" + numbers +
						"), lost = 1.0;\nfloat after;\n");
		assertFalse(behind.complete());
		assertEquals(Arrays.asList("table", "after"), names(behind));
		// Fields, and names of one declaration, beyond what it keeps.
		StringBuilder fields = new StringBuilder("struct Big {\n");
		StringBuilder list = new StringBuilder("float v0");
		for (int i = 1; i < 1500; ++i) {
			fields.append("\tfloat f").append(i).append(";\n");
			list.append(", v").append(i);
		}
		assertFalse(GlslKnowledge.of(fields + "};\n").complete());
		assertFalse(GlslKnowledge.of(list + ";\n").complete());
		assertFalse(GlslKnowledge.of("void main() {\n\t" + list +
				";\n}\n").complete());
	}

	private static List<String> names(GlslKnowledge known) {
		List<String> names = new ArrayList<>();
		for (Symbol symbol : known.symbols()) {
			names.add(symbol.name());
		}
		return names;
	}

	private static GlslKnowledge read(String text, int maxSymbols,
			int maxChars) {
		Knowledge.Builder builder = GlslKnowledge.builder(maxSymbols,
				maxChars);
		LineTokens tokens = new LineTokens();
		int state = GlslLexer.TEXT_START;
		int start = 0;
		String[] lines = text.split("\n", -1);
		for (int n = 0; n < lines.length; ++n) {
			state = GlslLexer.lexLine(lines[n], n < lines.length - 1, state,
					tokens);
			builder.addLine(lines[n], start, tokens);
			start += lines[n].length() + 1;
		}
		return (GlslKnowledge) builder.build();
	}

	// Bodies no one writes: none fails, none takes time that grows
	// faster than the text.
	@Test(timeout = 20000)
	public void hugeBodies() {
		StringBuilder deep = new StringBuilder("void main() {\n");
		for (int i = 0; i < 100000; ++i) {
			deep.append("{ float a").append(i % 7).append("; ");
		}
		GlslKnowledge.of(deep);
		StringBuilder fors = new StringBuilder("void main() {\n");
		for (int i = 0; i < 100000; ++i) {
			fors.append("for (int i = 0; i < 3; ++i) ");
		}
		GlslKnowledge.of(fors.append("x;\n}\n"));
		StringBuilder parens = new StringBuilder("void main() {\nfloat a = ");
		for (int i = 0; i < 100000; ++i) {
			parens.append('(');
		}
		GlslKnowledge.of(parens);
		// One line of 2 MB: a hundred thousand locals in one body.
		StringBuilder line = new StringBuilder("void main() { ");
		for (int i = 0; i < 100000; ++i) {
			line.append("float v").append(i).append(" = 1.0; ");
		}
		String text = line.append("}").toString();
		GlslKnowledge known = GlslKnowledge.of(text);
		int end = text.length() - 1;
		for (int i = 0; i < 100000; i += 1009) {
			assertEquals("float", known.at(end, "v" + i).get(0).type());
		}
		// The same name declared a hundred thousand times in one scope.
		StringBuilder same = new StringBuilder("void main() { ");
		for (int i = 0; i < 100000; ++i) {
			same.append("float v = 1.0; ");
		}
		text = same.append("}").toString();
		known = GlslKnowledge.of(text);
		for (int i = 0; i < 1000; ++i) {
			assertEquals(1, known.at(text.length() - 1, "v").size());
		}
	}

	private static String unmark(String text, List<Integer> marks) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); ++i) {
			if (text.charAt(i) == '|') {
				marks.add(sb.length());
			} else {
				sb.append(text.charAt(i));
			}
		}
		return sb.toString();
	}

	private static int lineOf(String text, int offset) {
		int line = 0;
		for (int i = 0; i < offset; ++i) {
			if (text.charAt(i) == '\n') {
				++line;
			}
		}
		return line;
	}

	// ---- Cost, as counts ------------------------------------------------

	@Test
	public void externalLookupIsNotAskedForALocal() {
		int[] calls = {0};
		Knowledge.Scope outside = new Knowledge.Scope() {
			@Override
			public List<String> names() {
				return List.of();
			}

			@Override
			public List<Symbol> find(CharSequence word) {
				calls[0]++;
				return List.of();
			}

			@Override
			public int line(Symbol s) {
				return 0;
			}
		};
		String text = "void f(){vec3 p; p;}";
		GlslKnowledge k = GlslKnowledge.of(text);
		GlslExpression.Names names = k.names(text.lastIndexOf("p;"),
				outside);
		assertEquals("vec3", names.variable("p"));
		assertEquals(0, calls[0]);
		// What the text does not know is asked outside once, however
		// often it is written.
		for (int i = 0; i < 5; ++i) {
			assertNull(names.variable("q"));
			assertTrue(names.functions("q").isEmpty());
		}
		assertEquals(1, calls[0]);
	}

	// A word that counts how often it is read.
	private static final class Word implements CharSequence {
		int reads;

		@Override
		public int length() {
			return 1;
		}

		@Override
		public char charAt(int i) {
			reads++;
			return 'x';
		}

		@Override
		public CharSequence subSequence(int a, int b) {
			return "x".subSequence(a, b);
		}
	}

	@Test
	public void repeatedNameLookupCost() {
		GlslLocals locals = new GlslLocals();
		for (int i = 0; i < 10000; i++) {
			locals.add("x", Knowledge.Kind.VARIABLE, "float", i * 10,
					i * 10 + 1, i * 10 + 2);
			locals.close(i, i * 10 + 9);
		}
		locals.freeze();
		Word word = new Word();
		assertEquals(0, locals.find(word, 3));
		// Of 10,000 locals of the name the lookup reads the name some
		// 15 times, not 10,000 times.
		assertTrue("characters read: " + word.reads, word.reads < 100);
		// Nested as deep as scopes go, it walks outward and no more.
		locals = new GlslLocals();
		for (int i = 0; i < 64; i++) {
			locals.add("x", Knowledge.Kind.VARIABLE, "float", i * 10,
					i * 10 + 1, i * 10 + 2);
		}
		locals.freeze();
		word.reads = 0;
		assertEquals(63, locals.find(word, 5000));
		assertEquals(0, locals.find(word, 3));
		assertTrue("characters read: " + word.reads, word.reads < 100);
	}
}
