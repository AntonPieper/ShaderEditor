package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GlslCompletionTest {
	@Test
	public void indexAtReturnsTheTokenContainingTheOffset() {
		Tokens tokens = GlslLexer.lex("float x;");
		assertEquals(Style.PRIMITIVE, tokens.style(tokens.indexAt(0)));
		assertEquals(Style.PRIMITIVE, tokens.style(tokens.indexAt(4)));
		assertEquals(Style.IDENTIFIER, tokens.style(tokens.indexAt(6)));
		assertEquals(Style.OPERATOR, tokens.style(tokens.indexAt(7)));
	}

	@Test
	public void indexAtReturnsNoneBetweenTokensAndAtTheEnd() {
		Tokens tokens = GlslLexer.lex("float x;");
		assertEquals(-1, tokens.indexAt(5));
		assertEquals(-1, tokens.indexAt(8));
		assertEquals(-1, GlslLexer.lex("").indexAt(0));
	}

	@Test
	public void tokenBeforeTheCursor() {
		Tokens tokens = GlslLexer.lex("float x;");
		assertEquals(0, GlslCompletion.tokenBefore(tokens, 1));
		assertEquals(0, GlslCompletion.tokenBefore(tokens, 5));
		assertEquals(-1, GlslCompletion.tokenBefore(tokens, 6));
		assertEquals(1, GlslCompletion.tokenBefore(tokens, 7));
		assertEquals(2, GlslCompletion.tokenBefore(tokens, 8));
		assertEquals(-1, GlslCompletion.tokenBefore(tokens, 0));
		assertEquals(-1, GlslCompletion.tokenBefore(GlslLexer.lex(""), 0));
	}

	@Test
	public void completeKeywordsByPrefix() {
		assertEquals(Arrays.asList("vec2", "vec3", "vec4"),
				complete("vec"));
		assertEquals(Arrays.asList("flat", "float", "floatBitsToInt", "floatBitsToUint",
						"floor"),
				complete("fl"));
		assertEquals(Arrays.asList("float", "floatBitsToInt", "floatBitsToUint"),
				complete("float"));
	}

	@Test
	public void completeNothing() {
		assertEquals(Collections.emptyList(), complete(""));
		assertEquals(Collections.emptyList(), complete("xyz"));
		assertEquals(Collections.emptyList(), complete("Vec"));
		assertEquals(Collections.emptyList(), words("", true));
	}

	@Test
	public void completeDirectivesAndKeywordsInDirectives() {
		assertEquals(Arrays.asList("pragma"),
				words("prag", true));
		assertEquals(Arrays.asList("define", "default", "degrees", "determinant"),
				words("de", true));
	}

	@Test
	public void completeWordThatIsDirectiveAndKeyword() {
		assertEquals(Arrays.asList("elif", "else", "endif", "error", "extension",
						"equal", "exp", "exp2"),
				words("e", true));
	}

	@Test
	public void completeOwnNamesBeforeLanguageWords() {
		String text = "#define NORMAL_SCALE 2.0\n" +
				"struct Noisy { float n; };\n" +
				"uniform int nightMode;\n" +
				"float nothing;\n" +
				"float noise(vec2 p) { float nolocal = 1.0; return nolocal; }\n" +
				"void main() { float x = no";
		assertEquals(Arrays.asList("noise", "nothing", "normalize", "not",
						"notEqual"),
				completeAt(text, text.length()));
		assertEquals(Arrays.asList("NORMAL_SCALE", "Noisy"),
				completeAt(text + "\nN", text.length() + 2));
	}

	@Test
	public void completeOwnNamesOnce() {
		String text = "vec3 shade(vec3 p);\n" +
				"#define texture texture2D\n" +
				"vec3 shade(vec3 p) { return texture(s, p.xy).rgb; }\n" +
				"void main() { sha";
		assertEquals(Arrays.asList("shade"), completeAt(text, text.length()));
		text += "\ntexture";
		List<String> textures = completeAt(text, text.length());
		assertEquals(Arrays.asList("texture", "texture2D", "texture2DProj",
						"textureCube"),
				textures.subList(0, 4));
		assertEquals(1, Collections.frequency(textures, "texture"));
	}

	@Test
	public void completeOwnMacrosInDirectives() {
		String text = "#define FTIME_PERIOD 4.0\n#ifdef FT";
		assertEquals(Arrays.asList("FTIME_PERIOD"),
				completeAt(text, text.length()));
	}

	@Test
	public void completeBuiltinUniformsAfterLanguageWords() {
		List<String> table = Arrays.asList("mouse", "resolution", "time",
				"touch", "touchStart");
		assertEquals(Arrays.asList("resolution"),
				completeAt("gl_FragCoord.xy / res;", 21, table));
		assertEquals(Arrays.asList("time"),
				completeAt("float t = tim", 13, table));
		assertEquals(Arrays.asList("mat2", "mat2x2", "mat2x3", "mat2x4",
						"mat3", "mat3x2", "mat3x3", "mat3x4", "mat4", "mat4x2",
						"mat4x3", "mat4x4", "matrixCompMult", "max", "mediump",
						"min", "mix", "mod", "modf", "mouse"),
				completeAt("m", 1, table));
		assertEquals(Arrays.asList("touch", "touchStart"),
				completeAt("to", 2, table));
	}

	@Test
	public void completeADeclaredBuiltinUniformOnce() {
		String text = "uniform vec2 touchStart;\nvoid main() { tou";
		assertEquals(Arrays.asList("touchStart", "touch"),
				completeAt(text, text.length(),
						Arrays.asList("touch", "touchStart")));
	}

	@Test
	public void completeNotTheNameBeingDeclared() {
		String text = "float noi() {}";
		assertEquals(Collections.emptyList(), completeAt(text, 9));
		text = "float noise;\nfloat noi;";
		assertEquals(Arrays.asList("noise"), completeAt(text, 22));
	}

	@Test
	public void completeBuiltinsOfOpenGlEs() {
		assertEquals(Arrays.asList("gl_FragColor", "gl_FragCoord", "gl_FragData",
						"gl_FragDepth"),
				complete("gl_Frag"));
		assertEquals(Arrays.asList("texelFetch", "texelFetchOffset", "texture",
						"texture2D", "texture2DProj", "textureCube", "textureGrad",
						"textureGradOffset", "textureLod", "textureLodOffset",
						"textureOffset", "textureProj", "textureProjGrad",
						"textureProjGradOffset", "textureProjLod", "textureProjLodOffset",
						"textureProjOffset", "textureSize"),
				complete("tex"));
		// restrict cannot be used in an OpenGL ES fragment shader.
		assertEquals(Collections.emptyList(), complete("res"));
	}

	@Test
	public void completeNoTypesOfDesktopGlsl() {
		assertEquals(Collections.emptyList(), complete("samplerB"));
		assertEquals(Collections.emptyList(), complete("samplerb"));
		assertEquals(Collections.emptyList(), complete("uimage1"));
		assertEquals(Arrays.asList("samplerExternalOES"), complete("samplerE"));
	}

	@Test
	public void prefixInsideAWord() {
		assertEquals("fl", prefixAt("x = flo;", 6));
	}

	@Test
	public void prefixAtTheEndOfTheText() {
		assertEquals("fl", prefixAt("x = fl", 6));
	}

	@Test
	public void prefixBeforeWhitespace() {
		assertEquals("fl", prefixAt("x = fl ", 6));
	}

	@Test
	public void prefixBeforeAClosingParenthesis() {
		assertEquals("no", prefixAt("vec3(no)", 7));
	}

	@Test
	public void prefixBeforeASemicolon() {
		assertEquals("fl", prefixAt("x = fl;", 6));
	}

	@Test
	public void prefixInADirective() {
		Tokens tokens = GlslLexer.lex("#def");
		int index = GlslCompletion.tokenBefore(tokens, 4);
		assertEquals("def", prefixAt("#def", 4));
		assertTrue(tokens.inDirective(index));
		assertFalse(GlslLexer.lex("x = def").inDirective(2));
	}

	@Test
	public void insideAComment() {
		assertTrue(inComment("// fl", 5));
		assertTrue(inComment("x; // fl\ny", 8));
		assertTrue(inComment("/* fl", 5));
		assertTrue(inComment("/* fl */ x", 5));
		assertTrue(inComment("/*", 2));
		assertTrue(inComment("/*/", 3));
		assertTrue(inComment("#define A // fl", 15));
	}

	@Test
	public void notInsideAComment() {
		assertFalse(inComment("x = fl", 6));
		assertFalse(inComment("/* a */", 7));
		assertFalse(inComment("/* a */fl", 9));
		assertFalse(inComment("// a\nfl", 7));
	}

	@Test
	public void functionsCompleteWithTheirParenthesis() {
		String text = "struct Ray { vec3 o; };\n" +
				"float disc(vec2 p) { return 0.0; }\n" +
				"uniform float dim;\n" +
				"void main() {}\n";
		GlslKnowledge outline = GlslKnowledge.of(text);
		assertEquals(Arrays.asList("mix(", "max(", "disc(", "dim", "main",
						"mat2", "Ray", "gl_FragCoord", "{"),
				GlslCompletion.withParentheses(Arrays.asList("mix", "max",
						"disc", "dim", "main", "mat2", "Ray", "gl_FragCoord",
						"{"), outline));
	}

	@NonNull
	private static List<String> complete(@NonNull String prefix) {
		return words(prefix, false);
	}

	@NonNull
	private static List<String> words(@NonNull String prefix,
			boolean inDirective) {
		return GlslCompletion.words(prefix, inDirective,
				GlslKnowledge.NONE, -1, Collections.emptyList());
	}

	/**
	 * What the editor offers with the cursor at cursor in text.
	 */
	@NonNull
	private static List<String> completeAt(@NonNull String text, int cursor) {
		return completeAt(text, cursor, Collections.emptyList());
	}

	@NonNull
	private static List<String> completeAt(@NonNull String text, int cursor,
			@NonNull List<String> uniforms) {
		Tokens tokens = GlslLexer.lex(text);
		int index = GlslCompletion.tokenBefore(tokens, cursor);
		return GlslCompletion.words(
				text.substring(tokens.start(index), cursor),
				tokens.inDirective(index),
				GlslKnowledge.of(text),
				tokens.start(index),
				uniforms);
	}

	/**
	 * The text the editor completes.
	 */
	@NonNull
	private static String prefixAt(@NonNull String text, int cursor) {
		Tokens tokens = GlslLexer.lex(text);
		int index = GlslCompletion.tokenBefore(tokens, cursor);
		if (index < 0) {
			return "";
		}
		return text.substring(tokens.start(index), cursor);
	}

	private static boolean inComment(@NonNull String text, int cursor) {
		Tokens tokens = GlslLexer.lex(text);
		int index = GlslCompletion.tokenBefore(tokens, cursor);
		return index > -1 &&
				GlslCompletion.isInComment(tokens, text, index, cursor);
	}

	// The call the caret is in, from the tokens before it.
	@Test
	public void theCallAroundTheCaret() {
		assertEquals("smoothstep 1", call("float g = smoothstep(0.3, |"));
		assertEquals("smoothstep 0", call("float g = smoothstep(|"));
		assertEquals("smoothstep 2", call("smoothstep(0.3, 0.0, x|"));
		// Arguments of a call inside count for that call.
		assertEquals("mix 2", call("mix(a, max(b, c), |"));
		assertEquals("max 1", call("mix(a, max(b, |"));
		// A constructor, a group and an index are arguments of the
		// call around them.
		assertEquals("mix 1", call("mix(a, vec3(1.0, |"));
		assertEquals("mix 0", call("mix((a + |"));
		assertEquals("mix 1", call("mix(a, b[i, |"));
		// Over lines, and past a comment.
		assertEquals("mix 2", call("x = mix(a,\n\t// (not, this\n\tb, |"));
		assertEquals("SAT 0", call("#define A SAT /* c */ (|"));
		// No call: a closed one, a statement, a condition, a type.
		assertEquals(null, call("mix(a, b, c)|"));
		assertEquals(null, call("mix(a, b); float x = |"));
		assertEquals(null, call("if (x > |"));
		assertEquals(null, call("void main() { |"));
		assertEquals(null, call("vec3(1.0, |"));
		assertEquals(null, call("|mix(a"));
		// It looks back a bounded way.
		assertEquals(null, call("mix(a," + "\n".repeat(
				GlslCompletion.CALL_LINES) + "b|"));
	}

	// The call at the mark "|" of text, as "name argument", or null.
	@Test
	public void membersBehindADot() {
		String text = "struct Hit { float d; vec3 normal; };\n" +
				"struct Ray { vec3 origin; vec3 dir; Hit last; };\n" +
				"uniform vec2 resolution;\n" +
				"Ray march(Ray r);\n" +
				"void main() {\n" +
				"\tRay ray = Ray(vec3(0.0), vec3(0.0, 0.0, 1.0), Hit(1.0, vec3(0.0)));\n" +
				"\tvec4 colour = vec4(1.0);\n" +
				"\tfloat d = 1.0;\n" +
				"\t|\n" +
				"}\n";
		// The fields of a struct, in their order.
		assertEquals("origin dir last @0", members(text, "ray."));
		assertEquals("dir @1", members(text, "x = ray.d"));
		assertEquals("d normal @0", members(text, "ray.last."));
		assertEquals("normal @1", members(text, "f(ray.last.n"));
		assertEquals("origin dir last @0", members(text, "march(ray)."));
		// The components of a vector, for its size.
		assertEquals("x y r g s t xy @0", members(text, "resolution."));
		assertEquals("x y z r g b s t p xy xyz rgb @0",
				members(text, "ray.origin."));
		assertEquals("x y z w r g b a s t p q xy xyz rgb @0",
				members(text, "colour."));
		assertEquals("r rgb @1", members(text, "colour.r"));
		assertEquals("xy xyz @2", members(text, "colour.xy"));
		assertEquals("x y z r g b s t p xy xyz rgb @0",
				members(text, "normalize(ray.dir)."));
		assertEquals("x y z r g b s t p xy xyz rgb @0",
				members(text, "(ray.origin + ray.dir * d)."));
		assertEquals("x y z w r g b a s t p q xy xyz rgb @0",
				members(text, "gl_FragCoord."));
		// A local hides what the file declares: d is a float here.
		assertEquals(KEYS, members(text, "d."));
		// What has no members, what is not known, and a number: as
		// without a dot.
		assertEquals(KEYS, members(text, "unknown."));
		assertEquals(KEYS, members(text, "ray.last.d."));
		assertEquals(KEYS, members(text, "1."));
		assertEquals("resolution reflect refract return @2",
				members(text, "unknown.re"));
		// Nothing else starts with what is typed.
		assertEquals(KEYS, members(text, "ray.origin"));
		assertEquals(KEYS, members(text, "ray.q"));
	}

	// A value with more tokens before its "." than are read: the rest
	// of it would be told as another value, so nothing is offered.
	@Test
	public void aValueLongerThanIsReadHasNoMembers() {
		String text = "struct S { vec2 a[9]; };\nstruct W { S a[9]; };\n" +
				"void main() {\n\tW w;\n\tvec3 a[9];\n\t|\n}\n";
		String index = "0" + " + 0".repeat(120);
		assertEquals("x y r g s t xy @0", members(text, "w.a[0].a[0]."));
		assertEquals("x y z r g b s t p xy xyz rgb @0",
				members(text, "a[" + "0" + " + 0".repeat(40) + "]."));
		// Cut behind "w.", what is left reads as the local "a".
		assertEquals(KEYS, members(text, "w.a[" + index + "]."));
		assertEquals(KEYS, members(text, "w.a[0].a[" + index + "]."));
	}

	private static final String KEYS = String.join(" ",
			GlslCompletion.KEYS) + " @0";

	// What is offered behind typed, written where the "|" of text is.
	private static String members(String text, String typed) {
		int at = text.indexOf('|');
		String whole = text.substring(0, at) + typed + text.substring(at + 1);
		int caret = at + typed.length();
		int lineStart = whole.lastIndexOf('\n', caret - 1) + 1;
		int lineEnd = whole.indexOf('\n', caret);
		GlslCompletion.Completions found = GlslCompletion.atColumn(
				LexedLine.of(GlslSyntax.INSTANCE, whole.substring(lineStart,
						lineEnd), true, GlslLexer.TEXT_START,
						new LineTokens()), caret - lineStart, lineStart,
				GlslCompletion.Names.of(GlslKnowledge.of(text.replace("|",
						""))), false, List.of(), Knowledge.Scope.NONE);
		return String.join(" ", found.words()) + " @" + found.position();
	}

	@Test
	public void theCallAroundTheCaretOfALongLine() {
		// The caret is at the start of a line with a hundred thousand
		// tokens behind it: they are not walked over.
		StringBuilder sb = new StringBuilder("mix(a, |");
		for (int i = 0; i < 20000; ++i) {
			sb.append(" + b[").append(i).append("]");
		}
		assertEquals("mix 1", call(sb.toString()));
		assertEquals("mix 1", call("mix(a, |b, c)"));
		assertEquals("mix 0", call("mix(|a, b, c)"));
		assertEquals("null", String.valueOf(call("|mix(a, b, c)")));
	}

	private static String call(String text) {
		int caret = text.indexOf('|');
		String[] lines = (text.substring(0, caret) +
				text.substring(caret + 1)).split("\n", -1);
		LexedLine[] lexed = new LexedLine[lines.length];
		int state = GlslLexer.TEXT_START;
		int line = 0;
		int column = caret;
		for (int n = 0; n < lines.length; ++n) {
			lexed[n] = LexedLine.of(GlslSyntax.INSTANCE, lines[n],
					n < lines.length - 1, state, new LineTokens());
			state = lexed[n].endState();
			if (column > lines[n].length() && n < lines.length - 1) {
				column -= lines[n].length() + 1;
				line = n + 1;
			}
		}
		Syntax.Call call = GlslSyntax.INSTANCE.call(n -> lexed[n], line,
				column);
		return call == null ? null : call.name() + " " + call.argument();
	}
}
