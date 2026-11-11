package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Random;

/**
 * Properties of {@link GlslLexer} for any input. Colours are pinned in
 * the highlighter's LexerStylesTest.
 */
public class GlslLexerTest {
	private static final String[] FRAGMENTS = {
			"#", "#define A ", "# pragma se input ", "#include ", "#/**/",
			"\"", "\"a//b/*c\"", "/*", "*/", "//", "/", "*", "\\", "\\\n",
			"\\\r\n", "\\\r", "\n", "\r\n", "\r", " ", "\t", "\f", "float",
			"vec3", "struct", "Ray", "x", ".", ".5", "1.0e", "1e+", "0x",
			"0x1F", "019", "1u", "2.0f", "{", "}", "(", ")", ";", "=",
			"gl_FragCoord", "@", "$", "`", "'", "\u0000", "\uD83D",
			"\uDE00", "\uDCDC", "\u00e9", "\u0663", "\u2028", "\uFEFF"};

	@Test
	public void bundledShaders() throws IOException {
		for (Map.Entry<String, String> shader : BundledShaders.read().entrySet()) {
			assertWellFormed(shader.getKey(), shader.getValue());
		}
	}

	@Test
	public void randomCharacters() {
		Random random = new Random(42);
		for (int n = 0; n < 5000; ++n) {
			char[] chars = new char[random.nextInt(120)];
			for (int i = 0; i < chars.length; ++i) {
				int r = random.nextInt(20);
				chars[i] = r == 0
						? (char) (0xD800 + random.nextInt(0x800))
						: (char) random.nextInt(r == 1 ? 0x10000 : 0x80);
			}
			assertWellFormed("random characters #" + n, new String(chars));
		}
	}

	@Test
	public void randomFragments() {
		Random random = new Random(7);
		for (int n = 0; n < 5000; ++n) {
			StringBuilder sb = new StringBuilder();
			for (int i = random.nextInt(40); i-- > 0; ) {
				sb.append(FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
			}
			assertWellFormed("random fragments #" + n, sb.toString());
		}
	}

	@Test
	public void emptyText() {
		// The old lexer returns one empty EOF token; the new one returns none.
		Tokens tokens = GlslLexer.lex("");
		assertEquals(0, tokens.size());
		assertEquals(1, tokens.lineCount());
		assertEquals(-1, tokens.indexAt(0));
	}

	@Test
	public void trailingUnpairedSurrogate() {
		// The old lexer ends this token at 4, past the end of the text.
		Tokens tokens = assertWellFormed("", "a \uD83D");
		assertEquals(2, tokens.size());
		assertEquals(Style.INVALID, tokens.style(1));
		assertEquals(2, tokens.start(1));
		assertEquals(3, tokens.end(1));
	}

	@Test
	public void surrogatePairIsOneToken() {
		Tokens tokens = assertWellFormed("", "x = \uD83D\uDE00;");
		assertEquals(4, tokens.size());
		assertEquals(4, tokens.start(2));
		assertEquals(6, tokens.end(2));
	}

	@Test
	public void loneBackslashAtTheEnd() {
		Tokens tokens = assertWellFormed("", "x \\");
		assertEquals(2, tokens.size());
		assertEquals(Style.INVALID, tokens.style(1));
	}

	@Test
	public void unterminatedComments() {
		assertComment("/* a\nb", 0, 6);
		assertComment("x /*", 2, 4);
		assertComment("// a \\\nb\nc", 0, 8);
	}

	@Test
	public void eachLineEndCountsOnce() {
		// The old lexer counts CRLF as two lines.
		Tokens tokens = assertWellFormed("", "a\r\nb\rc\nd\n\re");
		assertEquals(6, tokens.lineCount());
		int[] lines = {0, 1, 2, 3, 5};
		for (int i = 0; i < lines.length; ++i) {
			assertEquals(lines[i], tokens.lineOf(tokens.start(i)));
		}
		assertEquals(3, tokens.lineStart(1));
		assertEquals(10, tokens.lineStart(5));
	}

	@Test
	public void manyLines() {
		// The old lexer reports line -25535 here.
		Tokens tokens = GlslLexer.lex("\n".repeat(40000) + "x");
		assertEquals(40001, tokens.lineCount());
		assertEquals(40000, tokens.lineOf(tokens.start(0)));
	}

	@Test
	public void indexAt() {
		Tokens tokens = GlslLexer.lex("float x;");
		assertEquals(0, tokens.indexAt(0));
		assertEquals(0, tokens.indexAt(4));
		assertEquals(-1, tokens.indexAt(5));
		assertEquals(1, tokens.indexAt(6));
		assertEquals(2, tokens.indexAt(7));
		assertEquals(-1, tokens.indexAt(8));
	}

	@Test
	public void tokensInsideDirectives() {
		String text = "#define A /* c */ b \\\n c // d\ne /* f\n */ g";
		Tokens tokens = assertWellFormed("", text);
		boolean[] expected = {true, true, true, true, true, true, true, false,
				false, false};
		assertEquals(expected.length, tokens.size());
		for (int i = 0; i < expected.length; ++i) {
			assertEquals(String.valueOf(i), expected[i], tokens.inDirective(i));
		}
	}

	@Test
	public void lineEndInsideACommentEndsTheDirective() {
		Tokens tokens = assertWellFormed("", "#define A /* c\n */ b");
		assertEquals(5, tokens.size());
		assertTrue(tokens.inDirective(3));
		assertEquals(Style.COMMENT, tokens.style(3));
		assertEquals(18, tokens.end(3));
		assertFalse(tokens.inDirective(4));
	}

	@Test
	public void punctuationIsOneTokenPerCharacter() {
		Tokens tokens = GlslLexer.lex("a<<=b");
		assertEquals(5, tokens.size());
		assertEquals(Style.OPERATOR, tokens.style(1));
		assertEquals(Style.OPERATOR, tokens.style(3));
	}

	private static void assertComment(String text, int start, int end) {
		Tokens tokens = assertWellFormed(text, text);
		int i = tokens.indexAt(start);
		assertTrue(text, i > -1);
		assertEquals(text, Style.COMMENT, tokens.style(i));
		assertEquals(text, start, tokens.start(i));
		assertEquals(text, end, tokens.end(i));
	}

	/**
	 * Tokens are non-empty, ordered and inside the text; between them is
	 * only whitespace and line continuations; lines start after each line
	 * end.
	 */
	private static Tokens assertWellFormed(String where, String text) {
		Tokens tokens = GlslLexer.lex(text);
		where += ": " + text.replace("\n", "\\n").replace("\r", "\\r");
		int end = 0;
		for (int t = 0; t < tokens.size(); ++t) {
			assertTrue(where, tokens.start(t) >= end);
			assertGap(where, text, end, tokens.start(t));
			assertTrue(where, tokens.end(t) > tokens.start(t));
			assertTrue(where, tokens.end(t) <= text.length());
			assertEquals(where, t, tokens.indexAt(tokens.start(t)));
			end = tokens.end(t);
		}
		assertGap(where, text, end, text.length());
		int lines = 1;
		for (int i = 0; i < text.length(); ++i) {
			char c = text.charAt(i);
			if (c == '\n' || (c == '\r' && !text.startsWith("\n", i + 1))) {
				assertEquals(where, i + 1, tokens.lineStart(lines++));
			}
		}
		assertEquals(where, lines, tokens.lineCount());
		assertEquals(where, 0, tokens.lineStart(0));
		return tokens;
	}

	private static void assertGap(String where, String text, int from, int to) {
		for (int i = from; i < to; ++i) {
			char c = text.charAt(i);
			boolean continuation = c == '\\' && i + 1 < text.length() &&
					(text.charAt(i + 1) == '\n' || text.charAt(i + 1) == '\r');
			assertTrue(where + " at " + i, Character.isWhitespace(c) || continuation);
		}
		assertFalse(where, from > to);
	}
}
