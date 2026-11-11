package de.markusfisch.android.shadereditor.highlighter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LexerCompletionTest {
	@Test
	public void findTokenReturnsTheTokenContainingTheOffset() {
		List<Token> tokens = Styles.tokens("float x;");
		assertEquals(TokenType.FLOAT, Lexer.findToken(tokens, 0).type());
		assertEquals(TokenType.FLOAT, Lexer.findToken(tokens, 4).type());
		assertEquals(TokenType.IDENTIFIER, Lexer.findToken(tokens, 6).type());
		assertEquals(TokenType.SEMICOLON, Lexer.findToken(tokens, 7).type());
	}

	@Test
	public void findTokenReturnsNullBetweenTokensAndAtTheEnd() {
		List<Token> tokens = Styles.tokens("float x;");
		assertNull(Lexer.findToken(tokens, 5));
		assertNull(Lexer.findToken(tokens, 8));
		assertNull(Lexer.findToken(Styles.tokens(""), 0));
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
		assertEquals(Collections.emptyList(),
				Lexer.completeKeyword("fl", Token.Category.TRIVIA));
	}

	@Test
	public void completeDirectivesAndKeywordsInDirectives() {
		assertEquals(Arrays.asList("pragma"),
				Lexer.completeKeyword("prag", Token.Category.PREPROC));
		assertEquals(Arrays.asList("define", "default", "degrees", "determinant"),
				Lexer.completeKeyword("de", Token.Category.PREPROC));
	}

	@Test
	public void completeWordThatIsDirectiveAndKeyword() {
		// Wrong: else is offered twice.
		assertEquals(Arrays.asList("elif", "else", "endif", "error", "else", "equal", "exp",
						"exp2"),
				Lexer.completeKeyword("e", Token.Category.PREPROC));
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
	public void insideAComment() {
		assertTrue(inComment("// fl", 5));
		assertTrue(inComment("x; // fl\ny", 8));
		assertTrue(inComment("/* fl", 5));
		assertTrue(inComment("/* fl */ x", 5));
		assertTrue(inComment("/*", 2));
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
		return Lexer.completeKeyword(prefix, Token.Category.NORMAL);
	}

	/**
	 * The text the editor completes.
	 */
	@NonNull
	private static String prefixAt(@NonNull String text, int cursor) {
		Token token = Lexer.findCompletionToken(Styles.tokens(text), cursor);
		if (token == null) {
			return "";
		}
		return Lexer.tokenSource(token, text)
				.subSequence(0, cursor - token.startOffset())
				.toString();
	}

	private static boolean inComment(@NonNull String text, int cursor) {
		Token token = Lexer.findCompletionToken(Styles.tokens(text), cursor);
		return token != null && Lexer.isInComment(token, text, cursor);
	}
}
