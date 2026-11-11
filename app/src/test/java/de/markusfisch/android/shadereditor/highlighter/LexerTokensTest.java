package de.markusfisch.android.shadereditor.highlighter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class LexerTokensTest {
	@Test
	public void bundledShadersHaveOrderedTokensInsideTheText() throws IOException {
		File[] shaders = findRawDirectory().listFiles((dir, name) -> name.endsWith(".glsl"));
		assertTrue(shaders != null && shaders.length > 0);
		Arrays.sort(shaders);
		for (File shader : shaders) {
			String source = new String(Files.readAllBytes(shader.toPath()),
					StandardCharsets.UTF_8);
			int previousEnd = 0;
			for (Token token : Styles.tokens(source)) {
				String where = shader.getName() + ": " + token;
				assertTrue(where, token.startOffset() >= previousEnd);
				assertTrue(where, token.endOffset() >= token.startOffset());
				assertTrue(where, token.endOffset() <= source.length());
				previousEnd = token.endOffset();
			}
		}
	}

	@Test
	public void trailingUnpairedSurrogate() {
		// Wrong: the last token ends at 3, the end of the text.
		List<Token> tokens = Styles.tokens("a \uD83D");
		Token surrogate = tokens.get(1);
		assertEquals(TokenType.INVALID, surrogate.type());
		assertEquals(2, surrogate.startOffset());
		assertEquals(4, surrogate.endOffset());
	}

	@Test
	public void startAndEndCountEmojiDifferentlyInCodeAndComments() {
		// Wrong: start and end count the emoji as one in code but as two in
		// a comment.
		List<Token> code = Styles.tokens("x = 😀;");
		assertEquals(4, code.get(2).start());
		assertEquals(5, code.get(2).end());
		assertEquals(6, code.get(2).endOffset());
		List<Token> comment = Styles.tokens("// 😀");
		assertEquals(0, comment.get(0).start());
		assertEquals(5, comment.get(0).end());
		assertEquals(5, comment.get(0).endOffset());
	}

	@Test
	public void crlfCountsAsTwoLines() {
		// Wrong: the second float is on line 2.
		Token secondFloat = Styles.tokens("float a;\r\nfloat b;").get(3);
		assertEquals(TokenType.FLOAT, secondFloat.type());
		assertEquals(3, secondFloat.line());
		assertEquals(0, secondFloat.column());
	}

	@Test
	public void lineNumberOverflows() {
		// Wrong: x is on line 40001.
		Token x = Styles.tokens("\n".repeat(40000) + "x").get(0);
		assertEquals(TokenType.IDENTIFIER, x.type());
		assertEquals(-25535, x.line());
	}

	@Test
	public void lastTokenIsAnEmptyEof() {
		List<Token> tokens = Styles.tokens("x");
		Token eof = tokens.get(tokens.size() - 1);
		assertEquals(TokenType.EOF, eof.type());
		assertEquals(1, eof.startOffset());
		assertEquals(1, eof.endOffset());
	}

	@Test
	public void emptyTextHasOnlyEof() {
		List<Token> tokens = Styles.tokens("");
		assertEquals(1, tokens.size());
		assertEquals(TokenType.EOF, tokens.get(0).type());
		assertEquals(0, tokens.get(0).endOffset());
	}

	private static File findRawDirectory() {
		String relative = "src/main/res/raw";
		for (String base : new String[]{".", "app"}) {
			File dir = new File(base, relative);
			if (dir.isDirectory()) {
				return dir;
			}
		}
		throw new AssertionError("Cannot find " + relative + " from working directory " +
				new File(".").getAbsolutePath());
	}
}
