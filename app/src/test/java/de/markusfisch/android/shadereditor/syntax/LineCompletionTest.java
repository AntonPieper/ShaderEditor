package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@link GlslCompletion#atColumn} against {@link GlslCompletion#atOffset}:
 * the line of the cursor and its start state complete as the whole text
 * does.
 */
public class LineCompletionTest {
	private static final List<String> EXTRA = List.of("resolution", "time");
	private static final String[] TRICKY = {
			"/* a\n*/ fl",
			"/* a\n*/",
			"/* a\nx*/",
			"/* a\n/*/ b */ ve",
			"/*/ x */ ti",
			"/**/fl",
			"// a \\\nb */ fl",
			"// a \\\n\nfl",
			"#define A /* c\n */ fl\n#if de",
			"#def",
			"float time2;\nvoid main() { tim",
			"x \\\nfl",
			"a 😀b",
	};

	@Test
	public void completesLikeTheWholeTextOnEveryBundledShader()
			throws IOException {
		for (Map.Entry<String, String> shader :
				BundledShaders.read().entrySet()) {
			assertEveryOffset(shader.getKey(), shader.getValue());
		}
	}

	@Test
	public void completesLikeTheWholeTextAroundCommentsAndContinuations() {
		for (String text : TRICKY) {
			assertEveryOffset(text, text);
		}
	}

	@Test
	public void leavesOutTheDeclarationBeingTyped() {
		String text = "float tim";
		Outline outline = Outline.of(GlslLexer.lex(text), text);
		assertEquals(new GlslCompletion.Completions(List.of("time"), 3),
				GlslCompletion.atColumn(text, 9, false,
						GlslLexer.TEXT_START, 0, outline, EXTRA));
	}

	private static void assertEveryOffset(String name, String source) {
		String text = source.replace("\r\n", "\n").replace('\r', '\n');
		Tokens tokens = GlslLexer.lex(text);
		Outline outline = Outline.of(tokens, text);
		String[] lines = text.split("\n", -1);
		LineTokens lineTokens = new LineTokens();
		int state = GlslLexer.TEXT_START;
		int lineStart = 0;
		for (int n = 0; n < lines.length; ++n) {
			String line = lines[n];
			boolean breakFollows = n + 1 < lines.length;
			for (int column = 0; column <= line.length(); ++column) {
				int offset = lineStart + column;
				assertEquals(name + " at " + offset,
						GlslCompletion.atOffset(tokens, text, offset, outline,
								EXTRA),
						GlslCompletion.atColumn(line, column, breakFollows,
								state, lineStart, outline, EXTRA));
			}
			state = GlslLexer.lexLine(line, breakFollows, state, lineTokens);
			lineStart += line.length() + 1;
		}
	}
}
