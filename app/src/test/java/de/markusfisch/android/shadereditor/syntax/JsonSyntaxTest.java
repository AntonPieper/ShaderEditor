package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.AutoIndent;
import de.markusfisch.android.shadereditor.editor.Text;

public class JsonSyntaxTest {
	@Test
	public void coloursWhatJsonIsMadeOf() {
		assertStyles("{\"a\": [1, -2.5e3, true, null]}",
				"ossso.ono.nnnnnno.bbbbo.kkkkoo");
		assertStyles("\"t\\\"ab\\u00e9\" 0 01 1. -",
				"sssssssssssss.n.xx.xx.x");
	}

	@Test
	public void marksWhatCannotBeJsonOnTheLine() {
		assertStyles("\"open", "xxxxx");
		assertStyles("\"\\q\" x // c", "xxxx.x.xx.x");
		assertStyles("\"\\u12\"", "xxxxxx");
	}

	@Test
	public void everyLineStartsTheSame() {
		LineTokens tokens = new LineTokens();
		assertEquals(Syntax.TEXT_START, JsonSyntax.INSTANCE.lexLine("\"a",
				true, Syntax.TEXT_START, tokens));
	}

	@Test
	public void bracesAndBracketsIndent() {
		Indentation json = JsonSyntax.INSTANCE.indentation();
		assertEquals("\t", indent("{", json));
		assertEquals("\t\t", indent("x\n\t\"a\": [", json));
		assertEquals("\t", indent("x\n\t\"a\": [1, {}],", json));
		assertEquals("\t", indent("x\n\t\"a\": 1 -", json));
		assertEquals("\t", indent("x\n\t// c", json));
	}

	@Test
	public void plainTextIsNotColouredNorCompleted() {
		LineTokens tokens = new LineTokens();
		PlainSyntax.INSTANCE.lexLine("void main() {", false,
				Syntax.TEXT_START, tokens);
		assertEquals(0, tokens.size());
		List<Object> offered = new ArrayList<>();
		PlainSyntax.INSTANCE.complete(LexedLine.of(PlainSyntax.INSTANCE,
						"vo", false, Syntax.TEXT_START, tokens), 2, 0,
				Outline.NONE, true, List.of("void"),
				(words, position) -> offered.add(words));
		assertEquals(List.of(List.of()), offered);
		assertEquals("\t", indent("x\n\tf({", PlainSyntax.INSTANCE
				.indentation()));
	}

	private static String indent(String text, Indentation rules) {
		return AutoIndent.indentAfterBreak(Text.of(text), text.length(),
				text.length(), "\t", rules);
	}

	private static void assertStyles(String line, String expected) {
		LineTokens tokens = new LineTokens();
		JsonSyntax.INSTANCE.lexLine(line, false, Syntax.TEXT_START, tokens);
		char[] styles = new char[line.length()];
		java.util.Arrays.fill(styles, '.');
		for (int i = 0; i < tokens.size(); ++i) {
			char c = switch (tokens.style(i)) {
				case STRING -> 's';
				case NUMBER -> 'n';
				case BOOL -> 'b';
				case KEYWORD -> 'k';
				case OPERATOR -> 'o';
				case INVALID -> 'x';
				default -> '?';
			};
			for (int k = tokens.start(i); k < tokens.end(i); ++k) {
				styles[k] = c;
			}
		}
		assertEquals(line, expected, new String(styles));
	}
}
