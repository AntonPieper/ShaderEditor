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
		assertEquals(Collections.emptyList(), GlslCompletion.words("", true));
	}

	@Test
	public void completeDirectivesAndKeywordsInDirectives() {
		assertEquals(Arrays.asList("pragma"),
				GlslCompletion.words("prag", true));
		assertEquals(Arrays.asList("define", "default", "degrees", "determinant"),
				GlslCompletion.words("de", true));
	}

	@Test
	public void completeWordThatIsDirectiveAndKeyword() {
		// Wrong: else is offered twice.
		assertEquals(Arrays.asList("elif", "else", "endif", "error", "else", "equal", "exp",
						"exp2"),
				GlslCompletion.words("e", true));
	}

	@Test
	public void completeBuiltinsOfOpenGlEs() {
		// Wrong: gl_FragColor is missing.
		assertEquals(Arrays.asList("gl_FragCoord", "gl_FragDepth"),
				complete("gl_Frag"));
		// Wrong: texture2D is missing.
		assertEquals(Arrays.asList("texelFetch", "texelFetchOffset", "texture",
						"textureGather", "textureGatherOffset", "textureGatherOffsets",
						"textureGrad", "textureGradOffset", "textureLod", "textureLodOffset",
						"textureOffset", "textureProj", "textureProjGrad",
						"textureProjGradOffset", "textureProjLod", "textureProjLodOffset",
						"textureProjOffset", "textureQueryLevels", "textureQueryLod",
						"textureSamples", "textureSize"),
				complete("tex"));
		// Wrong: restrict cannot be used in an OpenGL ES fragment shader.
		assertEquals(Arrays.asList("restrict"),
				complete("res"));
	}

	@Test
	public void completeMisspelledTypes() {
		// Wrong: samplerBuffer is the type, not samplerbuffer.
		assertEquals(Collections.emptyList(), complete("samplerB"));
		assertEquals(Arrays.asList("samplerbuffer"), complete("samplerb"));
		// Wrong: uimage1DArray is missing.
		assertEquals(Arrays.asList("uimage1D"), complete("uimage1"));
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
		return GlslCompletion.words(prefix, false);
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
