package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.List;

/**
 * What the editor completes: the word that ends at the cursor, nothing
 * inside a comment.
 */
public final class GlslCompletion {
	private GlslCompletion() {
	}

	/**
	 * Index of the token that holds the character before the cursor, or
	 * -1 if there is none. The text to complete runs from its start to the
	 * cursor.
	 */
	public static int tokenBefore(Tokens tokens, int cursor) {
		return cursor > 0 ? tokens.indexAt(cursor - 1) : -1;
	}

	/**
	 * True if the cursor is inside the comment at index, the token that
	 * holds the character before the cursor.
	 */
	public static boolean isInComment(Tokens tokens, CharSequence text,
			int index, int cursor) {
		if (tokens.style(index) != Style.COMMENT) {
			return false;
		}
		int start = tokens.start(index);
		int end = tokens.end(index);
		return text.charAt(start + 1) == '/' ||
				cursor < end ||
				end - start < 4 ||
				text.charAt(end - 2) != '*' ||
				text.charAt(end - 1) != '/';
	}

	/**
	 * Words that start with prefix; in a directive, the directive names
	 * come first.
	 */
	public static List<String> words(String prefix, boolean inDirective) {
		if (!inDirective) {
			return GlslWords.matching(prefix);
		}
		List<String> words = new ArrayList<>(
				GlslWords.matchingDirectives(prefix));
		words.addAll(GlslWords.matching(prefix));
		return words;
	}
}
