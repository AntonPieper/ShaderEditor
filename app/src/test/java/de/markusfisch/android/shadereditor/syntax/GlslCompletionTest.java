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

	@NonNull
	private static List<String> complete(@NonNull String prefix) {
		return words(prefix, false);
	}

	@NonNull
	private static List<String> words(@NonNull String prefix,
			boolean inDirective) {
		return GlslCompletion.words(prefix, inDirective,
				Outline.of(GlslLexer.lex(""), ""), -1);
	}

	/**
	 * What the editor offers with the cursor at cursor in text.
	 */
	@NonNull
	private static List<String> completeAt(@NonNull String text, int cursor) {
		Tokens tokens = GlslLexer.lex(text);
		int index = GlslCompletion.tokenBefore(tokens, cursor);
		return GlslCompletion.words(
				text.substring(tokens.start(index), cursor),
				tokens.inDirective(index),
				Outline.of(tokens, text),
				tokens.start(index));
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
}
