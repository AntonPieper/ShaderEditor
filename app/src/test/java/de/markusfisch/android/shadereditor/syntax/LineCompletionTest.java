package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * {@link GlslCompletion#atColumn} against {@link GlslCompletion#atOffset}:
 * the line of the cursor and its start state complete as the whole text
 * does.
 */
public class LineCompletionTest {
	private static final Syntax GLSL = GlslSyntax.INSTANCE;
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
				GlslCompletion.atColumn(LexedLine.of(GLSL, text, false,
						GlslLexer.TEXT_START, new LineTokens()), 9, 0,
						GlslCompletion.Names.of(outline), true, EXTRA));
	}

	@Test
	public void anOlderOutlineLeavesOutNoDeclaration() {
		String text = "float target;";
		GlslCompletion.Names names = GlslCompletion.Names.of(
				Outline.of(GlslLexer.lex(text), text));
		// The declaration started at 6, where "ta" starts now.
		LexedLine line = LexedLine.of(GLSL, "      ta", false,
				GlslLexer.TEXT_START, new LineTokens());
		assertEquals(List.of("target", "tan", "tanh"),
				GlslCompletion.atColumn(line, 8, 0, names, false,
						List.of()).words());
		assertEquals(List.of("tan", "tanh"),
				GlslCompletion.atColumn(line, 8, 0, names, true,
						List.of()).words());
	}

	@Test
	public void offersAtMostFiftyWords() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 1000; ++i) {
			sb.append("float v").append(i).append(";\n");
		}
		String text = sb.toString();
		GlslCompletion.Names names = GlslCompletion.Names.of(
				Outline.of(GlslLexer.lex(text), text));
		List<String> words = GlslCompletion.atColumn(LexedLine.of(GLSL, "v",
				false, GlslLexer.TEXT_START, new LineTokens()), 1, 0, names,
				true, List.of()).words();
		assertEquals(50, words.size());
		assertEquals("v0", words.get(0));
		assertEquals("v1", words.get(1));
		assertEquals("v10", words.get(2));
	}

	@Test
	public void completesFromTheLinesOfADocument() {
		String text = "/* a\nb */ fl";
		Tokens tokens = GlslLexer.lex(text);
		Outline outline = Outline.of(tokens, text);
		LexedLine line = new LineStyles(new Document(Text.of(text)), GLSL, 10)
				.runs(1).line();
		assertEquals(GlslLexer.BLOCK_COMMENT,
				line.state() & GlslLexer.BLOCK_COMMENT);
		assertEquals(GlslCompletion.atOffset(tokens, text, 12, outline,
						EXTRA),
				GlslCompletion.atColumn(line, 7, 5,
						GlslCompletion.Names.of(outline), true, EXTRA));
	}

	private static void assertEveryOffset(String name, String source) {
		String text = source.replace("\r\n", "\n").replace('\r', '\n');
		Tokens tokens = GlslLexer.lex(text);
		Outline outline = Outline.of(tokens, text);
		GlslCompletion.Names names = GlslCompletion.Names.of(outline);
		String[] lines = text.split("\n", -1);
		LineTokens lineTokens = new LineTokens();
		int state = GlslLexer.TEXT_START;
		int lineStart = 0;
		for (int n = 0; n < lines.length; ++n) {
			String line = lines[n];
			boolean breakFollows = n + 1 < lines.length;
			LexedLine lexed = LexedLine.of(GLSL, line, breakFollows, state,
					lineTokens);
			for (int column = 0; column <= line.length(); ++column) {
				int offset = lineStart + column;
				assertEquals(name + " at " + offset,
						GlslCompletion.atOffset(tokens, text, offset, outline,
								EXTRA),
						GlslCompletion.atColumn(lexed, column, lineStart,
								names, true, EXTRA));
			}
			state = lexed.endState();
			lineStart += line.length() + 1;
		}
	}
}
