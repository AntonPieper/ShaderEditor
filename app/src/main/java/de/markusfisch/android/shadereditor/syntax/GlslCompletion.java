package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
	 * Words that start with prefix, each once: in a directive the directive
	 * names, then the names the outline declares, then the language words.
	 * A declaration whose name starts at wordStart is the word being typed
	 * and is left out.
	 */
	public static List<String> words(String prefix, boolean inDirective,
			Outline outline, int wordStart) {
		if (prefix.isEmpty()) {
			return new ArrayList<>();
		}
		Set<String> words = new LinkedHashSet<>();
		if (inDirective) {
			words.addAll(GlslWords.matchingDirectives(prefix));
		}
		List<String> names = new ArrayList<>();
		for (Outline.Declaration declaration : outline.declarations()) {
			if (declaration.start() != wordStart &&
					declaration.name().startsWith(prefix)) {
				names.add(declaration.name());
			}
		}
		Collections.sort(names);
		words.addAll(names);
		words.addAll(GlslWords.matching(prefix));
		return new ArrayList<>(words);
	}
}
