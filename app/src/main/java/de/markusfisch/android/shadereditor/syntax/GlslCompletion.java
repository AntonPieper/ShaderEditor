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

	/** The most words {@link #atColumn} offers. */
	public static final int MAX_WORDS = 50;

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
	 * line alone, but at most {@link #MAX_WORDS} words. The names may be
	 * of an older text; its declarations then complete what they
	 * declared then, the one being typed included.
	 *
	 * @param line      the cursor's line as it was tokenized
	 * @param column    the cursor's offset in line
	 * @param lineStart the offset of the line in the text
	 * @param current   whether names are of the current text
	 */
	public static Completions atColumn(LexedLine line, int column,
			int lineStart, Names names, boolean current,
			List<String> extraWords) {
		int state = line.state();
		boolean continued = (state & (GlslLexer.BLOCK_COMMENT |
				GlslLexer.LINE_COMMENT)) != 0;
		if (column == 0) {
			// The character before is the line break, which only a
			// comment can hold.
			return continued ? IN_COMMENT : NO_WORD;
		}
		int index = line.indexAt(column - 1);
		if (index < 0) {
			return NO_WORD;
		}
		String text = line.text();
		int start = line.start(index);
		if (line.style(index) == Style.COMMENT) {
			// The first token continues a comment from the line before.
			continued &= index == 0 && start == 0;
			int end = line.end(index);
			if ((continued
					? (state & GlslLexer.LINE_COMMENT) != 0
					: text.charAt(start + 1) == '/') ||
					column < end || end - start < (continued ? 2 : 4) ||
					text.charAt(end - 2) != '*' ||
					text.charAt(end - 1) != '/') {
				return IN_COMMENT;
			}
		}
		String prefix = text.substring(start, column);
		return of(prefix, names.words(prefix, line.inDirective(index),
				current ? lineStart + start : -1, extraWords));
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
		return of(prefix, words(prefix, inDirective, outline, wordStart,
				extraWords));
	}

	private static Completions of(String prefix, List<String> words) {
		if (words.isEmpty() ||
				(words.size() == 1 && words.get(0).equals(prefix))) {
			return NO_WORD;
		}
		return new Completions(words, prefix.length());
	}

	/**
	 * The names an outline declares, sorted, for completing them without
	 * looking at every declaration. Immutable; the outline sorted them.
	 */
	public static final class Names {
		private final Outline outline;
		// Each name once, sorted, and how many declarations have it.
		private final String[] names;
		private final int[] counts;

		private Names(Outline outline, String[] names, int[] counts) {
			this.outline = outline;
			this.names = names;
			this.counts = counts;
		}

		public static Names of(Outline outline) {
			return new Names(outline, outline.names, outline.counts);
		}

		// Returns what words returns, but at most MAX_WORDS of them.
		private List<String> words(String prefix, boolean inDirective,
				int wordStart, List<String> extraWords) {
			if (prefix.isEmpty()) {
				return new ArrayList<>();
			}
			Set<String> words = new LinkedHashSet<>();
			if (inDirective) {
				add(words, GlslWords.matchingDirectives(prefix), prefix);
			}
			String typed = wordStart < 0 ? null : declaredAt(wordStart);
			int i = Arrays.binarySearch(names, prefix);
			for (i = i < 0 ? -i - 1 : i; i < names.length &&
					words.size() < MAX_WORDS &&
					names[i].startsWith(prefix); ++i) {
				if (counts[i] > 1 || !names[i].equals(typed)) {
					words.add(names[i]);
				}
			}
			add(words, GlslWords.matching(prefix), prefix);
			add(words, extraWords, prefix);
			return new ArrayList<>(words);
		}

		// Returns the name declared at start, or null.
		private String declaredAt(int start) {
			List<Outline.Declaration> declarations = outline.declarations();
			int low = 0;
			int high = declarations.size() - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				Outline.Declaration declaration = declarations.get(mid);
				if (declaration.start() < start) {
					low = mid + 1;
				} else if (declaration.start() > start) {
					high = mid - 1;
				} else {
					return declaration.name();
				}
			}
			return null;
		}

		private static void add(Set<String> words, List<String> more,
				String prefix) {
			for (int i = 0, n = more.size(); i < n &&
					words.size() < MAX_WORDS; ++i) {
				if (more.get(i).startsWith(prefix)) {
					words.add(more.get(i));
				}
			}
		}
	}
}
