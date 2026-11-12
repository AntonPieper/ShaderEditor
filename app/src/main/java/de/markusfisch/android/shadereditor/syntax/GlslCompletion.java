package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the editor completes: the word that ends at the cursor, nothing
 * inside a comment.
 */
public final class GlslCompletion {
	/** What the editor offers where there is no word to complete. */
	public static final List<String> KEYS = Collections.unmodifiableList(
			Arrays.asList("{", "}", "(", ")", "[", "]"));

	/**
	 * Words to offer and how many characters of the word at the cursor
	 * each of them repeats: the position the insertion starts at.
	 */
	public record Completions(List<String> words, int position) {
	}

	private static final Completions NO_WORD = new Completions(KEYS, 0);
	private static final Completions IN_COMMENT = new Completions(
			Collections.emptyList(), 0);

	private GlslCompletion() {
	}

	/**
	 * Completions for the cursor at offset cursor of text: nothing inside
	 * a comment, the keys where there is no word before the cursor or no
	 * other word that starts with it, else the words of
	 * {@link #words}.
	 *
	 * @param tokens the tokens of text
	 */
	public static Completions atOffset(Tokens tokens, CharSequence text,
			int cursor, Outline outline, List<String> extraWords) {
		int index = tokenBefore(tokens, cursor);
		if (index < 0) {
			return NO_WORD;
		} else if (isInComment(tokens, text, index, cursor)) {
			return IN_COMMENT;
		}
		int start = tokens.start(index);
		return of(text.subSequence(start, cursor).toString(),
				tokens.inDirective(index), outline, start, extraWords);
	}

	/**
	 * What {@link #atOffset} gives for the same cursor, from the cursor's
	 * line alone. The outline may be of an older text; its declarations
	 * then complete what they declared then.
	 *
	 * @param line         the line, without its line end
	 * @param column       the cursor's offset in line
	 * @param breakFollows whether a line break follows the line
	 * @param state        the state the line starts in, see
	 *                     {@link GlslLexer#lexLine}
	 * @param lineStart    the offset of the line in the text
	 */
	public static Completions atColumn(CharSequence line, int column,
			boolean breakFollows, int state, int lineStart, Outline outline,
			List<String> extraWords) {
		boolean continued = (state & (GlslLexer.BLOCK_COMMENT |
				GlslLexer.LINE_COMMENT)) != 0;
		if (column == 0) {
			// The character before is the line break, which only a
			// comment can hold.
			return continued ? IN_COMMENT : NO_WORD;
		}
		LineTokens tokens = new LineTokens();
		GlslLexer.lexLine(line, breakFollows, state, tokens);
		int index = tokens.indexAt(column - 1);
		if (index < 0) {
			return NO_WORD;
		}
		int start = tokens.start(index);
		if (tokens.style(index) == Style.COMMENT) {
			// The first token continues a comment from the line before.
			continued &= index == 0 && start == 0;
			int end = tokens.end(index);
			if ((continued
					? (state & GlslLexer.LINE_COMMENT) != 0
					: line.charAt(start + 1) == '/') ||
					column < end || end - start < (continued ? 2 : 4) ||
					line.charAt(end - 2) != '*' ||
					line.charAt(end - 1) != '/') {
				return IN_COMMENT;
			}
		}
		return of(line.subSequence(start, column).toString(),
				tokens.inDirective(index), outline, lineStart + start,
				extraWords);
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
	 * names, then the names the outline declares, then the language words,
	 * then the extra words in their order. A declaration whose name starts
	 * at wordStart is the word being typed and is left out.
	 */
	public static List<String> words(String prefix, boolean inDirective,
			Outline outline, int wordStart, List<String> extraWords) {
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
		for (String word : extraWords) {
			if (word.startsWith(prefix)) {
				words.add(word);
			}
		}
		return new ArrayList<>(words);
	}

	private static Completions of(String prefix, boolean inDirective,
			Outline outline, int wordStart, List<String> extraWords) {
		List<String> words = words(prefix, inDirective, outline, wordStart,
				extraWords);
		if (words.isEmpty() ||
				(words.size() == 1 && words.get(0).equals(prefix))) {
			return NO_WORD;
		}
		return new Completions(words, prefix.length());
	}
}
