package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

public class GlslDeclarationsTest {
	private static final Pattern UNIFORM = Pattern.compile(
			"^uniform\\s+\\w+\\s+(\\w+)", Pattern.MULTILINE);
	private static final Pattern FUNCTION = Pattern.compile(
			"^\\w+\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

	@Test
	public void bundledShaders() throws IOException {
		for (Map.Entry<String, String> shader :
				BundledShaders.read().entrySet()) {
			String text = shader.getValue();
			List<String> outline = outline(text);
			int found = 0;
			for (Matcher m = UNIFORM.matcher(text); m.find(); ++found) {
				assertTrue(shader.getKey() + ": " + m.group(1),
						outline.contains("UNIFORM " + m.group(1)));
			}
			for (Matcher m = FUNCTION.matcher(text); m.find(); ++found) {
				assertTrue(shader.getKey() + ": " + m.group(1),
						outline.contains("FUNCTION " + m.group(1)));
			}
			assertTrue(shader.getKey(), found > 0);
			assertRanges(text, GlslKnowledge.of(text));
		}
	}

	@Test
	public void declarationsOfEveryKind() {
		assertEquals(Arrays.asList(
						"MACRO PI",
						"UNIFORM resolution",
						"UNIFORM pointers",
						"CONSTANT a",
						"CONSTANT b",
						"CONSTANT c",
						"TYPE Ray",
						"VARIABLE fragColor",
						"FUNCTION trace",
						"FUNCTION main"),
				outline("#define PI 3.14159\n" +
						"precision mediump float;\n" +
						"uniform vec2 resolution;\n" +
						"uniform vec3 pointers[10];\n" +
						"const float a = max(1.0, 2.0), b, c[2] = float[2](a, b);\n" +
						"struct Ray { vec3 o; vec3 d; };\n" +
						"layout(location = 0) out vec4 fragColor;\n" +
						"float trace(Ray r, float t) { float local = t; return local; }\n" +
						"void main() { vec3 inner = vec3(0.0); }\n"));
	}

	@Test
	public void howADeclarationIsWritten() {
		assertEquals(Arrays.asList(
						"#define PI 3.14159",
						"#define SAT(x) clamp(x, 0.0, 1.0)",
						"uniform vec3 pointers[10]",
						"const float a = max(1.0, 2.0)",
						"const float b",
						"const float c[2] = float[2](a, b)",
						"struct Ray",
						"layout(location = 0) out vec4 fragColor",
						"float trace(Ray r, in float t)",
						"void main(void)"),
				details("#define PI 3.14159\n" +
						"#define  SAT(x)   clamp(x, 0.0,\\\n\t1.0)\n" +
						"uniform   vec3\tpointers[10];\n" +
						"const float a = max(1.0, 2.0), b, c[2] = float[2](a, b);\n" +
						"struct Ray { vec3 o; vec3 d; };\n" +
						"layout(location = 0) out vec4 fragColor;\n" +
						"float trace(Ray r,\n\t\tin float t) { return t; }\n" +
						"void main(void) {}\n"));
	}

	@Test
	public void parametersAndFields() {
		String text = "struct Ray { vec3 o, d; highp float t[2]; };\n" +
				"float f(Ray r, in vec2 uv, const float w[3], float) {}\n" +
				"#define MIX(a, b) mix(a, b, 0.5)\n" +
				"void main(void) {}\n";
		List<Symbol> symbols = GlslKnowledge.of(text).symbols();
		assertEquals(Arrays.asList("FIELD o: vec3 o", "FIELD d: vec3 d",
				"FIELD t: highp float t[2]"), parts(symbols.get(0)));
		assertEquals(Arrays.asList("PARAMETER r: Ray r",
				"PARAMETER uv: in vec2 uv",
				"PARAMETER w: const float w[3]"), parts(symbols.get(1)));
		assertEquals(Arrays.asList("PARAMETER a: a", "PARAMETER b: b"),
				parts(symbols.get(2)));
		assertEquals(Arrays.asList(), parts(symbols.get(3)));
		assertRanges(text, GlslKnowledge.of(text));
	}

	@Test
	public void ranges() {
		String text = "float x;\nvoid main() {\n}\n#define A 1\n" +
				"uniform float a, b;";
		List<Symbol> symbols = GlslKnowledge.of(text).symbols();
		assertEquals("float x;", range(text, symbols.get(0)));
		assertEquals("void main() {\n}", range(text, symbols.get(1)));
		assertEquals(14, symbols.get(1).start());
		assertEquals(18, symbols.get(1).end());
		assertEquals("#define A 1", range(text, symbols.get(2)));
		assertEquals("uniform float a", range(text, symbols.get(3)));
		assertEquals("b;", range(text, symbols.get(4)));
	}

	@Test
	public void theCommentThatBelongsToADeclaration() {
		String text = "// About the file.\n" +
				"\n" +
				"// A disc: 1 inside,\n" +
				"// 0 outside.\n" +
				"float disc(vec2 p) { return 1.0; }\n" +
				"uniform float time; // seconds since load\n" +
				"/**\n * The light.\n * Unit length.\n */\n" +
				"const vec3 LIGHT = vec3(0.0, 1.0, 0.0); // not this\n" +
				"// Above wins.\n" +
				"#define STEPS 48 // not this either\n" +
				"#define FAR 40.0 // how far a ray goes\n" +
				"float none;\n" +
				"// Not about b: an empty line stands between.\n" +
				"\n" +
				"float b; /* behind b */\n" +
				"float f(float x) { // about the body\n}\n";
		List<String> documentation = new ArrayList<>();
		for (Symbol s : GlslKnowledge.of(text).symbols()) {
			documentation.add(s.name() + ": " + s.documentation());
		}
		assertEquals(Arrays.asList(
				"disc: A disc: 1 inside, 0 outside.",
				"time: seconds since load",
				"LIGHT: The light. Unit length.",
				"STEPS: Above wins.",
				"FAR: how far a ray goes",
				"none: null",
				"b: behind b",
				"f: null"), documentation);
	}

	@Test
	public void declarationsInsideConditionals() {
		assertEquals(Arrays.asList(
						"VARIABLE high",
						"MACRO Q",
						"VARIABLE low",
						"VARIABLE other"),
				outline("#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
						"float high;\n" +
						"#if Q > 1\n" +
						"#define Q 2\n" +
						"#endif\n" +
						"#else\n" +
						"float low;\n" +
						"#endif\n" +
						"float other;\n"));
	}

	@Test
	public void structWithAnInstanceName() {
		assertEquals(Arrays.asList(
						"TYPE Light",
						"VARIABLE sun",
						"TYPE Material",
						"UNIFORM lamp",
						"VARIABLE anonymous",
						"UNIFORM colour",
						"UNIFORM block"),
				outline("struct Light { vec3 color; float power; } sun;\n" +
						"uniform struct Material { vec3 albedo; } lamp;\n" +
						"struct { int a; } anonymous;\n" +
						"uniform Loose { vec4 colour; };\n" +
						"uniform Named { vec4 inside; } block;\n"));
	}

	@Test
	public void prototypeAndDefinition() {
		String text = "vec3 shade(vec3 p);\n" +
				"void main() { gl_FragColor = vec4(shade(vec3(0.0)), 1.0); }\n" +
				"vec3 shade(vec3 p, float t) { return p; }\n";
		assertEquals(Arrays.asList(
						"FUNCTION shade",
						"FUNCTION main",
						"FUNCTION shade"),
				outline(text));
		Knowledge knowledge = GlslKnowledge.of(text);
		// A function answers with every form it is declared in.
		assertEquals(Arrays.asList("vec3 shade(vec3 p)",
				"vec3 shade(vec3 p, float t)"), details(knowledge.at(40,
				"shade")));
		// A parameter where its function is, not elsewhere.
		assertEquals(Arrays.asList("float t"), details(knowledge.at(
				text.indexOf("return"), "t")));
		assertEquals(Arrays.asList(), details(knowledge.at(40, "t")));
	}

	@Test
	public void colourTypesKeepsTokensWithoutTypes() {
		String text = "float x; vec3 f() { return vec3(x); }";
		Tokens tokens = GlslLexer.lex(text);
		assertSame(tokens, GlslKnowledge.of(text).colourTypes(tokens, text));
	}

	@Test
	public void macroWithArguments() {
		assertEquals(Arrays.asList(
						"MACRO SQUARE",
						"MACRO EMPTY",
						"MACRO texture",
						"MACRO LONG",
						"VARIABLE after"),
				outline("#define SQUARE(x) ((x) * (x))\n" +
						"# /* c */ define EMPTY\n" +
						"#define texture texture2D\n" +
						"#define LONG \\\n" +
						"\tfloat inMacro;\n" +
						"float after;\n"));
	}

	@Test
	public void namesInCommentsAndDisabledCode() {
		assertEquals(Arrays.asList(
						"VARIABLE enabled",
						"VARIABLE inElse",
						"VARIABLE after"),
				outline("// float commented;\n" +
						"/* void hidden() {} */\n" +
						"float enabled;\n" +
						"#if 0\n" +
						"float disabled;\n" +
						"#define DISABLED\n" +
						"#ifdef X\n" +
						"float nested;\n" +
						"#else\n" +
						"float nestedElse;\n" +
						"#endif\n" +
						"#else\n" +
						"float inElse;\n" +
						"#endif\n" +
						"#if 0\n" +
						"float disabledAgain;\n" +
						"#endif\n" +
						"float after;\n"));
	}

	@Test
	public void unfinishedCode() {
		assertEquals(Arrays.asList("VARIABLE a", "FUNCTION f"),
				outline("float a;\nvoid f() {\n\tfloat b;\n\tif (a > 0.0) {\n"));
		assertEquals(Arrays.asList("FUNCTION f"), outline("vec3 f(vec3"));
		assertEquals(Arrays.asList(), outline("x = y\nz = 1;"));
		assertEquals(Arrays.asList(), outline(""));
		assertEquals(Arrays.asList("FUNCTION f"), outline("vec3\nvoid f() {}"));
	}

	// A function head cannot stand in a body, so it ends the body that
	// lost its brace: nothing below it is lost.
	@Test
	public void aMissingBraceLosesNothingBelowIt() {
		String text = "float first(vec2 p) {\n" +
				"\tif (p.x > 0.0) {\n" +
				"\t\treturn 1.0;\n" +
				"\treturn 0.0;\n" +
				"}\n" +
				"\n" +
				"// The second.\n" +
				"vec3 second(vec3 p) {\n" +
				"\treturn p;\n" +
				"}\n" +
				"struct Ray { vec3 o; };\n" +
				"Ray third(Ray r) { return r; }\n" +
				"uniform float last;\n";
		assertEquals(Arrays.asList("FUNCTION first", "FUNCTION second",
				"TYPE Ray", "FUNCTION third", "UNIFORM last"),
				outline(text));
		List<Symbol> symbols = GlslKnowledge.of(text).symbols();
		assertEquals("The second.", symbols.get(1).documentation());
		assertEquals("vec3 second(vec3 p)", symbols.get(1).detail());
		// The first ends where the second begins at the latest.
		assertTrue(symbols.get(0).to() <= symbols.get(1).from());
		// The same for a function with a struct for a type, and for
		// what only stands outside of functions.
		assertEquals(Arrays.asList("TYPE Ray", "FUNCTION a", "FUNCTION b",
				"UNIFORM u", "FUNCTION c"),
				outline("struct Ray { vec3 o; };\n" +
						"void a() {\n\tfloat x = 1.0;\n" +
						"Ray b(Ray r) {\n\treturn r;\n" +
						"uniform float u;\n" +
						"void c() {}\n"));
	}

	// A declaration that lost its ";" ends where a new line starts
	// another one.
	@Test
	public void aMissingSemicolonLosesNothingBelowIt() {
		assertEquals(Arrays.asList("VARIABLE a", "UNIFORM b", "CONSTANT c",
				"FUNCTION f", "VARIABLE d", "TYPE S", "VARIABLE e",
				"FUNCTION g"),
				outline("float a = 1.0\n" +
						"uniform vec2 b\n" +
						"const vec3 c = vec3(\n\t1.0)\n" +
						"float f(float x)\n" +
						"vec3 d\n" +
						"struct S { float x; }\n" +
						"float e\n" +
						"void g() {}\n"));
		// What may go on in the next line does.
		assertEquals(Arrays.asList("CONSTANT c", "VARIABLE a", "VARIABLE b",
				"FUNCTION f", "TYPE Ray", "VARIABLE r"),
				outline("const vec3 c =\n\tvec3(1.0);\n" +
						"float a,\n\tb;\n" +
						"float\nf(vec2 p)\n{\n}\n" +
						"struct Ray { vec3 o; };\nRay\nr;\n"));
	}

	// A head written once per branch with one body.
	@Test
	public void aHeadInBothBranchesOfAConditional() {
		String text = "#ifdef WIDE\n" +
				"float map(vec3 p, float t) {\n" +
				"#else\n" +
				"float map(vec3 p) {\n" +
				"#endif\n" +
				"\treturn p.x;\n" +
				"}\n" +
				"void main() {\n" +
				"#if FAST\n" +
				"\tif (true) {\n" +
				"#else\n" +
				"\tif (false) {\n" +
				"#endif\n" +
				"\t}\n" +
				"}\n" +
				"float after;\n";
		assertEquals(Arrays.asList("FUNCTION map", "FUNCTION map",
				"FUNCTION main", "VARIABLE after"), outline(text));
		List<Symbol> symbols = GlslKnowledge.of(text).symbols();
		assertEquals("float map(vec3 p) {\n#endif\n\treturn p.x;\n}",
				range(text, symbols.get(1)));
		assertTrue(range(text, symbols.get(2)).endsWith("\t}\n}"));
	}

	@Test
	public void theFilesATextNames() {
		String text = "#include \"lib/noise.glsl\"\n" +
				"#pragma se input \"paper.png\" filter=linear\n" +
				"#pragma se target scale=0.5 initial=\"seed.frag\"\n" +
				"#pragma se input backbuffer\n" +
				"#pragma other \"not a file\"\n" +
				"#if 0\n#include \"../also.glsl\"\n#endif\n" +
				"#include <angle>\n";
		List<String> links = new ArrayList<>();
		for (Symbol s : GlslKnowledge.of(text).links()) {
			links.add(s.kind() + " " + s.name() + " " +
					text.substring(s.start(), s.end()));
		}
		assertEquals(Arrays.asList(
				"INCLUDE lib/noise.glsl lib/noise.glsl",
				"FILE paper.png paper.png",
				"FILE seed.frag seed.frag",
				"INCLUDE ../also.glsl ../also.glsl"), links);
	}

	@Test
	public void whatFolds() {
		String text = "// One line does not fold.\n" + // 0
				"\n" +
				"// Two lines\n" + // 2
				"// do.\n" +
				"float f(vec2 p) {\n" + // 4
				"\tfor (;;) {\n" + // 5
				"\t\tif (p.x > 0.0) { break; }\n" +
				"\t}\n" + // 7
				"}\n" + // 8
				"/* A block\n" + // 9
				"\n" +
				"   of three */\n" + // 11
				"struct Ray {\n" + // 12
				"\tvec3 o;\n" +
				"};\n" + // 14
				"#ifdef A\n" + // 15
				"float a;\n" +
				"#else\n" +
				"float b;\n" +
				"#endif\n" + // 19
				"void g() {}\n";
		assertEquals("[2, 3, 4, 8, 5, 7, 9, 11, 12, 14, 15, 19]",
				Arrays.toString(GlslKnowledge.of(text).folds()));
	}

	@Test
	public void keepsTheFirstSymbolsOfABudget() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 100; ++i) {
			sb.append("// Ten chars\nfloat v").append(i).append(";\n");
		}
		LineTokens tokens = new LineTokens();
		Knowledge.Builder few = GlslKnowledge.builder(7, 1 << 20);
		// A quarter of the characters is for names: three of them. The
		// rest is for what one symbol says beside its name, how it is
		// written and its comment, and one character more.
		Knowledge.Builder short_ = GlslKnowledge.builder(1000,
				4 * 3 * "v0".length());
		int start = 0;
		for (String line : sb.toString().split("\n")) {
			GlslLexer.lexLine(line, true, GlslLexer.TEXT_START, tokens);
			few.addLine(line, start, tokens);
			short_.addLine(line, start, tokens);
			start += line.length() + 1;
		}
		assertEquals(7, few.build().symbols().size());
		List<Symbol> symbols = short_.build().symbols();
		assertEquals(3, symbols.size());
		assertEquals("float v0", symbols.get(0).detail());
		assertEquals("Ten chars", symbols.get(0).documentation());
		// Names are kept for as long as names fit.
		assertEquals("v2", symbols.get(2).name());
		assertEquals("v2", symbols.get(2).detail());
		assertNull(symbols.get(2).documentation());
	}

	@Test
	public void wordsBeforeTheTypeLoseNoName() {
		// Macros for a precision or a qualifier, and what other shading
		// languages write before a type.
		assertEquals(Arrays.asList(
						"MACRO PREC",
						"UNIFORM speed",
						"VARIABLE TEX0",
						"CONSTANT scale",
						"UNIFORM a",
						"UNIFORM b",
						"UNIFORM c",
						"VARIABLE Color",
						"FUNCTION half2",
						"FUNCTION main"),
				outline("#define PREC highp\n" +
						"uniform PREC float speed;\n" +
						"COMPAT_VARYING vec4 TEX0;\n" +
						"static const float scale = 2.0;\n" +
						"uniform COMPAT_PRECISION float a, b,\n\tc;\n" +
						"IN vec4 Color;\n" +
						"inline float2 half2(float2 p) { return p * 0.5; }\n" +
						"void main() {}\n"));
	}

	@Test
	public void aTextStartsWithItsFirstWord() {
		// A byte order mark, and what else no declaration starts with.
		assertEquals(Arrays.asList("VARIABLE first", "FUNCTION main"),
				outline("\uFEFFvec3 first;\nvoid main() {}\n"));
		assertEquals(Arrays.asList("VARIABLE x", "VARIABLE y"),
				outline(") float x;\n= 1.0 $ float y;\n"));
	}

	@Test
	public void aCommentBehindIsNotAboutTheMacroAbove() {
		GlslKnowledge known = GlslKnowledge.of("#define STEPS 48\n" +
				"float x; // The x.\n" +
				"#define FAR 9.0 // How far.\n" +
				"float y; // The y.\n");
		assertNull(known.at(-1, "STEPS").get(0).documentation());
		assertEquals("The x.", known.at(-1, "x").get(0).documentation());
		assertEquals("How far.", known.at(-1, "FAR").get(0).documentation());
		assertEquals("The y.", known.at(-1, "y").get(0).documentation());
	}

	// One line of a hundred thousand declarations, each with a comment
	// behind it: read in a time that grows with the line, not with its
	// square, which took half a minute.
	@Test(timeout = 5000)
	public void manyCommentsOnOneLine() {
		int count = 100000;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; ++i) {
			sb.append("float a").append(i).append("; /*c").append(i)
					.append("*/ ");
		}
		List<Symbol> symbols = GlslKnowledge.of(sb).symbols();
		assertEquals(count, symbols.size());
		for (int i = 0; i < count; i += 997) {
			assertEquals("a" + i, symbols.get(i).name());
			assertEquals("c" + i, symbols.get(i).documentation());
		}
		// One comment is about all that was declared since the last.
		symbols = GlslKnowledge.of("float a; float b; /* Both. */ " +
				"float c; // One.\n").symbols();
		assertEquals("Both.", symbols.get(0).documentation());
		assertEquals("Both.", symbols.get(1).documentation());
		assertEquals("One.", symbols.get(2).documentation());
	}

	// A parameter is looked up in the function around the caret, also
	// behind ten thousand macros: in no time that grows with them.
	@Test(timeout = 5000)
	public void lookUpBehindManyMacros() {
		int count = 50000;
		StringBuilder sb = new StringBuilder("float f(float inner) {\n");
		for (int i = 0; i < count; ++i) {
			sb.append("#define M").append(i).append(" 1\n");
		}
		int inside = sb.length();
		sb.append("\treturn inner;\n}\n");
		int outside = sb.length();
		sb.append("float after;\n");
		GlslKnowledge known = GlslKnowledge.of(sb);
		for (int i = 0; i < count; ++i) {
			assertEquals(Knowledge.Kind.PARAMETER,
					known.at(inside, "inner").get(0).kind());
			assertTrue(known.at(outside, "inner").isEmpty());
		}
	}

	// The old editor has the tokens of the whole text and reads from
	// them: the same as from the lines.
	@Test
	public void readsFromTheTokensOfTheWholeText() throws IOException {
		List<String> texts = new ArrayList<>(BundledShaders.read().values());
		texts.addAll(Arrays.asList("", "\n\n", "/* open\n\nfloat x;",
				"/* a\n\n b */\nfloat x; // behind\n",
				"// one \\\n two\nfloat x;\n",
				"#define A(x) \\\n\tx + \\\n\t1 // doc\nfloat y;\n",
				"#define B /* over\nlines */ float z;\n",
				"#if 0\nfloat hidden;\n#else\nfloat shown;\n#endif",
				"struct S {\n\tfloat a;\n};\nS f(S s) {\n\treturn s;\n}"));
		Random random = new Random(7);
		for (int i = 0, n = texts.size(); i < n; ++i) {
			String text = texts.get(i);
			for (int k = 0; k < 20 && text.length() > 0; ++k) {
				int at = random.nextInt(text.length());
				texts.add(text.substring(0, at) + (k % 2 == 0 ? ""
						: "/*") + text.substring(at + 1));
			}
		}
		for (String text : texts) {
			GlslKnowledge lines = GlslKnowledge.of(text);
			GlslKnowledge whole = GlslKnowledge.of(GlslLexer.lex(text),
					text);
			assertEquals(text, lines.symbols(), whole.symbols());
			assertEquals(text, lines.links(), whole.links());
			assertEquals(text, Arrays.toString(lines.folds()),
					Arrays.toString(whole.folds()));
		}
		// Lines that end in "\r\n" have the same names.
		assertEquals(outline("uniform float a; // A.\nvoid main() {\n}\n"),
				whole("uniform float a; // A.\r\nvoid main() {\r\n}\r\n"));
	}

	private static List<String> whole(String text) {
		List<String> list = new ArrayList<>();
		for (Symbol symbol : GlslKnowledge.of(GlslLexer.lex(text), text)
				.symbols()) {
			list.add(symbol.kind() + " " + symbol.name());
		}
		return list;
	}

	// Any text gives symbols that lie in it.
	@Test
	public void randomFragments() {
		String[] fragments = {"#if 0\n", "#else\n", "#endif\n", "#define ",
				"struct ", "uniform ", "float ", "Ray ", "x", "(", ")", "{",
				"}", "[", "]", ";", ",", "=", "/*", "*/", "//", "\n", " ",
				"#include \"a\"", "const ", "f(", "\\\n", "vec3 y", "1.0"};
		Random random = new Random(11);
		for (int n = 0; n < 5000; ++n) {
			StringBuilder sb = new StringBuilder();
			for (int i = random.nextInt(40); i-- > 0; ) {
				sb.append(fragments[random.nextInt(fragments.length)]);
			}
			String text = sb.toString();
			assertRanges(text, GlslKnowledge.of(text));
		}
	}

	// A bundled shader damaged at random never fails, and what is
	// found lies in the text.
	@Test
	public void damagedShaders() throws IOException {
		String[] junk = {"{", "}", "(", ")", "[", "]", ";", ",", ".", "/*",
				"*/", "//", "#", "#if", "#else", "#endif", "\"", "\\\n",
				"struct", "for", "if", "uniform", "vec3", "=", "\n", "é",
				"((((((((", "float f("};
		List<String> shaders = new ArrayList<>(
				BundledShaders.read().values());
		Random random = new Random(20261115);
		for (int n = 0; n < 3000; ++n) {
			StringBuilder sb = new StringBuilder(shaders.get(
					random.nextInt(shaders.size())));
			for (int k = 1 + random.nextInt(6); k-- > 0 &&
					sb.length() > 2; ) {
				int at = random.nextInt(sb.length());
				if (random.nextBoolean()) {
					sb.delete(at, Math.min(sb.length(), at + 1 +
							random.nextInt(40)));
				} else {
					sb.insert(at, junk[random.nextInt(junk.length)]);
				}
			}
			String text = sb.toString();
			assertRanges(text, GlslKnowledge.of(text));
		}
	}

	// A statement typed character by character loses no function.
	@Test
	public void typingAStatement() throws IOException {
		for (Map.Entry<String, String> shader :
				BundledShaders.read().entrySet()) {
			String[] lines = shader.getValue().split("\n", -1);
			int at = -1;
			for (int i = lines.length / 2; i < lines.length; ++i) {
				if (lines[i].startsWith("\t") && lines[i].endsWith(";")) {
					at = i;
					break;
				}
			}
			if (at < 0) {
				continue;
			}
			List<String> whole = functions(shader.getValue());
			String line = lines[at];
			for (int k = 1; k < line.length(); ++k) {
				lines[at] = line.substring(0, k);
				assertEquals(shader.getKey() + ": " + lines[at], whole,
						functions(String.join("\n", lines)));
			}
		}
	}

	private static List<String> functions(String text) {
		List<String> functions = new ArrayList<>();
		for (Symbol s : GlslKnowledge.of(text).symbols()) {
			if (s.kind() == Knowledge.Kind.FUNCTION) {
				functions.add(s.name());
			}
		}
		return functions;
	}

	// Every symbol lies in the text, its name is where it says, and
	// symbols come in the order they start.
	private static void assertRanges(String text, Knowledge knowledge) {
		int from = 0;
		for (Symbol s : knowledge.symbols()) {
			assertEquals(text, s.name(), text.substring(s.start(), s.end()));
			assertTrue(text, from <= s.from() && s.from() <= s.start() &&
					s.end() <= s.to() && s.to() <= text.length());
			from = s.from();
			for (Symbol part : s.parts()) {
				assertEquals(text, part.name(), text.substring(part.start(),
						part.end()));
				assertTrue(text, s.from() <= part.from() &&
						part.from() <= part.start() &&
						part.end() <= part.to() && part.to() <= s.to());
			}
			assertNull(s.file());
		}
		for (Symbol s : knowledge.links()) {
			assertEquals(text, s.name(), text.substring(s.start(), s.end()));
		}
		int[] folds = knowledge.folds();
		for (int i = 0; i < folds.length; i += 2) {
			assertTrue(text, folds[i] < folds[i + 1]);
		}
	}

	private static String range(String text, Symbol symbol) {
		return text.substring(symbol.from(), symbol.to());
	}

	private static List<String> parts(Symbol symbol) {
		List<String> parts = new ArrayList<>();
		for (Symbol part : symbol.parts()) {
			parts.add(part.kind() + " " + part.name() + ": " +
					part.detail());
		}
		return parts;
	}

	private static List<String> details(String text) {
		return details(GlslKnowledge.of(text).symbols());
	}

	private static List<String> details(List<Symbol> symbols) {
		List<String> details = new ArrayList<>();
		for (Symbol symbol : symbols) {
			details.add(symbol.detail());
		}
		return details;
	}

	private static List<String> outline(String text) {
		List<String> result = new ArrayList<>();
		for (Symbol symbol : GlslKnowledge.of(text).symbols()) {
			result.add(symbol.kind() + " " + symbol.name());
		}
		return result;
	}
}
