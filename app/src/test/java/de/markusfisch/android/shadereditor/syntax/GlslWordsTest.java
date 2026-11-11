package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.highlighter.Highlight;
import de.markusfisch.android.shadereditor.highlighter.Lexer;
import de.markusfisch.android.shadereditor.highlighter.Token;

/**
 * Pins the word list against the tries of the old lexer: same words,
 * same styles, same completions.
 */
public class GlslWordsTest {
	private static final String FIRST_LETTERS =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_";

	@Test
	public void sameWordsAsTheOldLexer() {
		List<String> words = allWords();
		assertEquals(352, words.size());
		for (char c : FIRST_LETTERS.toCharArray()) {
			String prefix = String.valueOf(c);
			assertEquals(prefix, Lexer.completeKeyword(prefix,
					Token.Category.NORMAL), GlslWords.matching(prefix));
		}
	}

	@Test
	public void sameCompletionsForEveryPrefix() {
		TreeSet<String> prefixes = new TreeSet<>();
		for (String word : allWords()) {
			for (int i = 1; i <= word.length() + 1; ++i) {
				prefixes.add((word + "x").substring(0, i));
			}
		}
		for (String prefix : prefixes) {
			assertEquals(prefix, Lexer.completeKeyword(prefix,
					Token.Category.NORMAL), GlslWords.matching(prefix));
			List<String> inDirective = new ArrayList<>(
					GlslWords.matchingDirectives(prefix));
			inDirective.addAll(GlslWords.matching(prefix));
			assertEquals(prefix, Lexer.completeKeyword(prefix,
					Token.Category.PREPROC), inDirective);
		}
	}

	@Test
	public void sameStylesAsTheOldLexer() {
		for (String word : allWords()) {
			Token token = new Lexer(word).iterator().next();
			assertEquals(word, token.endOffset(), word.length());
			assertEquals(word, styleOf(Highlight.from(token.type())),
					GlslWords.style(word, 0, word.length()));
		}
	}

	@Test
	public void styleOfARangeInsideText() {
		String text = "x float floatx floa";
		assertEquals(Style.PRIMITIVE, GlslWords.style(text, 2, 7));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 8, 14));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 15, 19));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 0, 1));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 2, 2));
		assertEquals(Style.CONTROL_FLOW, GlslWords.style("else", 0, 4));
	}

	@Test
	public void directives() {
		for (String name : new String[]{"define", "elif", "else", "endif",
				"error", "if", "ifdef", "ifndef", "include", "line", "pragma",
				"undef", "version"}) {
			assertTrue(name, GlslWords.isDirective("#" + name + " x", 1,
					1 + name.length()));
		}
		// Wrong, kept from the old lexer: extension is a directive.
		assertFalse(GlslWords.isDirective("extension", 0, 9));
		assertFalse(GlslWords.isDirective("def", 0, 3));
		assertFalse(GlslWords.isDirective("defines", 0, 7));
	}

	@Test
	public void completeNothing() {
		assertEquals(Collections.emptyList(), GlslWords.matching(""));
		assertEquals(Collections.emptyList(), GlslWords.matchingDirectives(""));
		assertEquals(Collections.emptyList(), GlslWords.matching("xyz"));
		assertEquals(Collections.emptyList(), GlslWords.matching("é"));
	}

	@Test
	public void wordsOfTheOldLexerThatAreWrong() {
		// Kept so that switching lexers changes nothing; corrected later.
		assertEquals(Style.TYPE_SAMPLER, style("samplerbuffer"));
		assertEquals(Style.IDENTIFIER, style("samplerBuffer"));
		assertEquals(Style.IDENTIFIER, style("uimage1DArray"));
		assertEquals(Style.IDENTIFIER, style("gl_FragColor"));
		assertEquals(Style.IDENTIFIER, style("texture2D"));
		assertEquals(Style.IDENTIFIER, style("true"));
	}

	private static Style style(String word) {
		return GlslWords.style(word, 0, word.length());
	}

	private static List<String> allWords() {
		List<String> words = new ArrayList<>();
		for (char c : FIRST_LETTERS.toCharArray()) {
			words.addAll(GlslWords.matching(String.valueOf(c)));
		}
		return words;
	}

	private static Style styleOf(Highlight highlight) {
		switch (highlight) {
			case HIGHLIGHT_HIGHLIGHT_ATOMIC_COUNTER:
				return Style.ATOMIC_COUNTER;
			case HIGHLIGHT_NUMBER_LITERAL:
				return Style.NUMBER;
			case HIGHLIGHT_BOOL_LITERAL:
				return Style.BOOL;
			default:
				return Style.valueOf(highlight.name()
						.substring("HIGHLIGHT_".length()));
		}
	}
}
