package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.SourceText;

/**
 * PreprocessorScan and GlslLexer agree on where comments are and on
 * which lines start a directive. Both are rendered as one row per line:
 * "#name" if a directive starts on the line, then "|" and the line with
 * block comments blanked and line comments cut off.
 */
public class PreprocessorScanAgreementTest {
	@Test
	public void bundledShaders() throws IOException {
		for (Map.Entry<String, String> shader : BundledShaders.read().entrySet()) {
			assertAgree(shader.getKey(), shader.getValue());
		}
	}

	@Test
	public void directiveAfterABlockCommentOnTheSameLine() {
		assertAgree("/* a */ #define X 1");
		assertAgree("/* a\n b */ #define X 1\nfloat y;");
		assertAgree("x; /* a\n */ #define X 1");
	}

	@Test
	public void commentMarkersInsideAPath() {
		assertAgree("#include \"a//b/*c.glsl\" // note");
		assertAgree("#pragma se input \"a.png\" /* note */ x");
		assertAgree("#include \"a//b\nfloat x; // c");
		assertAgree("x = \"a//b\";");
	}

	@Test
	public void hashNotFirstOnItsLine() {
		assertAgree("float x; #define Y 1");
		assertAgree("#/**/pragma se input \"a.png\"");
		assertAgree("  #  define X 1\n\t# pragma x");
		assertAgree("x \\\n#define Y 1");
	}

	@Test
	public void lineEnds() {
		assertAgree("#version 300 es\r\n// c\r\n#define X 1\r\n" +
				"float y; /* a\r\n b */\r\n");
		assertAgree("#define X 1\rfloat y; /* a\r b */\r#endif\r");
		assertAgree("");
		assertAgree("\n\n");
	}

	@Test
	public void comments() {
		assertAgree("float a;\n/* open\nstill open");
		assertAgree("// #include \"a.glsl\"\n/* #pragma x\n#include \"b\" */ y;");
		assertAgree("/*/ x */ y // z\n#define A /* c\n */ B");
	}

	// Both follow GLSL ES 3.00 in every file: a backslash at the end of
	// a line continues a // comment or a directive onto the next line,
	// and a "#" there starts no directive. GLSL ES 1.00 has no line
	// continuation, but a file says which version it is only on its
	// #version line, and the editor and the build must read comments
	// the same way before that.
	@Test
	public void continuedMacro() {
		assertScanned("#define A(x) x \\\n\t+ 1\nfloat y;",
				"#define|#define A(x) x \\\n" +
						"|\t+ 1\n" +
						"|float y;\n");
		assertScanned("#define A \\\n#include \"a.glsl\"",
				"#define|#define A \\\n" +
						"|#include \"a.glsl\"\n");
	}

	@Test
	public void continuedLineCommentHidesADirective() {
		assertScanned("// c \\\n#pragma se input \"a.png\"",
				"|\n" +
						"|\n");
	}

	@Test
	public void quoteOnAContinuedDirectiveLine() {
		// Quoted paths are the app's extension; GLSL has no strings. A
		// continued line belongs to the directive, so its quote starts a
		// path as on the directive's first line.
		assertScanned("#define S \\\n\"a//b\"",
				"#define|#define S \\\n" +
						"|\"a//b\"\n");
	}

	private static void assertAgree(String text) {
		assertAgree(text, text);
	}

	private static void assertAgree(String where, String text) {
		assertEquals(where, scanned(text), lexed(text));
	}

	private static void assertScanned(String text, String expected) {
		assertAgree(text);
		assertEquals(expected, scanned(text));
	}

	private static List<String> lines(String text) {
		return SourceText.lines(SourceText.decode(
				text.getBytes(StandardCharsets.UTF_8)));
	}

	private static String scanned(String text) {
		List<String> lines = lines(text);
		PreprocessorScan scan = PreprocessorScan.scan(lines, null);
		String[] marks = new String[lines.size()];
		for (PreprocessorScan.Directive directive : scan.directives()) {
			marks[directive.line() - 1] = "#" + directive.name();
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); ++i) {
			sb.append(marks[i] == null ? "" : marks[i]).append('|')
					.append(scan.code().get(i)).append('\n');
		}
		return sb.toString();
	}

	private static String lexed(String text) {
		int lineCount = lines(text).size();
		Tokens tokens = GlslLexer.lex(text);
		assertTrue(tokens.lineCount() == lineCount ||
				tokens.lineCount() == lineCount + 1);
		char[] code = text.toCharArray();
		int[] cut = new int[lineCount];
		String[] marks = new String[lineCount];
		for (int i = 0; i < lineCount; ++i) {
			cut[i] = lineEnd(text, tokens, i);
		}
		for (int t = 0; t < tokens.size(); ++t) {
			int start = tokens.start(t);
			int line = tokens.lineOf(start);
			if (tokens.style(t) == Style.COMMENT) {
				boolean lineComment = text.startsWith("//", start);
				for (int i = start; i < tokens.end(t); ++i) {
					int l = tokens.lineOf(i);
					if (lineComment) {
						cut[l] = Math.min(cut[l], i);
					} else {
						code[i] = ' ';
					}
				}
			} else if (tokens.style(t) == Style.DIRECTIVE &&
					text.charAt(start) == '#') {
				marks[line] = "#" + directiveName(text, tokens, t);
			}
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lineCount; ++i) {
			sb.append(marks[i] == null ? "" : marks[i]).append('|')
					.append(code, tokens.lineStart(i),
							cut[i] - tokens.lineStart(i))
					.append('\n');
		}
		return sb.toString();
	}

	// The first token after the # that is not a comment.
	private static String directiveName(String text, Tokens tokens, int hash) {
		for (int t = hash + 1; t < tokens.size() && tokens.inDirective(t); ++t) {
			if (tokens.style(t) == Style.COMMENT) {
				continue;
			}
			if (text.charAt(tokens.start(t)) == '#') {
				break;
			}
			return text.substring(tokens.start(t), tokens.end(t));
		}
		return "";
	}

	// End of the line without its line end.
	private static int lineEnd(String text, Tokens tokens, int line) {
		int end = line + 1 < tokens.lineCount()
				? tokens.lineStart(line + 1)
				: text.length();
		while (end > tokens.lineStart(line) &&
				(text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
			--end;
		}
		return end;
	}
}
