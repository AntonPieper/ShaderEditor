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
			int cursor, GlslKnowledge known, List<String> extraWords) {
		int index = tokenBefore(tokens, cursor);
		if (index < 0) {
			return NO_WORD;
		} else if (isInComment(tokens, text, index, cursor)) {
			return IN_COMMENT;
		}
		int start = tokens.start(index);
		return of(text.subSequence(start, cursor).toString(),
				tokens.inDirective(index), known, start, extraWords);
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
		return atColumn(line, column, lineStart, names, current, extraWords,
				null);
	}

	/**
	 * As {@link #atColumn}, with what is declared outside of the text,
	 * if not null: behind a "." it then offers what the value before it
	 * has, the fields of a struct or the components of a vector, when
	 * its type can be told.
	 */
	public static Completions atColumn(LexedLine line, int column,
			int lineStart, Names names, boolean current,
			List<String> extraWords, Knowledge.Scope outside) {
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
		// Behind a ".", or in a word behind one.
		int dot = isDot(line, index) ? index : index > 0 &&
				isDot(line, index - 1) && line.end(index - 1) == start &&
				line.style(index) != Style.NUMBER ? index - 1 : -1;
		if (outside != null && dot >= 0 && !line.inDirective(dot)) {
			List<String> members = members(line, dot, lineStart, names.known,
					current, outside);
			if (members != null) {
				return ofMembers(dot == index ? "" : prefix, members);
			}
		}
		return of(prefix, names.words(prefix, line.inDirective(index),
				current ? lineStart + start : -1, extraWords));
	}

	/** The most tokens before a "." that are read for its value. */
	private static final int MEMBER_TOKENS = 200;

	private static boolean isDot(LexedLine line, int index) {
		return line.style(index) == Style.OPERATOR &&
				line.text().charAt(line.start(index)) == '.';
	}

	// What the value before the "." at token dot has: the names of the
	// fields of a struct, the components of a vector; null when its type
	// cannot be told or has none. It reads the tokens of the line before
	// the ".", no more than MEMBER_TOKENS of them.
	private static List<String> members(LexedLine line, int dot,
			int lineStart, GlslKnowledge known, boolean current,
			Knowledge.Scope outside) {
		GlslStatement statement = new GlslStatement();
		String text = line.text();
		int first = Math.max(0, dot - MEMBER_TOKENS);
		for (int i = first; i < dot; ++i) {
			Style style = line.style(i);
			if (style != Style.COMMENT) {
				statement.add(text, line.start(i), line.end(i),
						lineStart + line.start(i), GlslStatement.Token.of(
								style, text, line.start(i), line.end(i)));
			}
		}
		int size = statement.size();
		int start = GlslExpression.valueStart(statement, size);
		if (start >= size || (first > 0 && start == 0)) {
			// No value; or one that begins before what is read, whose
			// rest would be told as the type of another value.
			return null;
		}
		GlslExpression.Names names = known.names(place(lineStart,
				line.start(dot), current), outside);
		String type = GlslExpression.type(statement, start, size, names);
		if (type == null) {
			return null;
		}
		Knowledge.Symbol struct = names.struct(type);
		List<String> members;
		if (struct != null) {
			members = new ArrayList<>(struct.parts().size());
			for (Knowledge.Symbol field : struct.parts()) {
				members.add(field.name());
			}
		} else {
			members = GlslTypes.swizzles(type);
		}
		return members.isEmpty() ? null : members;
	}

	/**
	 * Return the offset to ask a knowledge at for what stands at column
	 * of the line that starts at lineStart. One of the current text is
	 * asked there. One of the text a moment ago is asked at the start of
	 * the line: what was typed in the line since moved everything behind
	 * it, and at the end of a body the place itself would lie behind it.
	 */
	static int place(int lineStart, int column, boolean current) {
		return current ? lineStart + column : lineStart;
	}

	// The members that start with prefix; the keys when it is one of
	// them and nothing else starts with it.
	private static Completions ofMembers(String prefix,
			List<String> members) {
		List<String> words = new ArrayList<>();
		for (String member : members) {
			if (member.startsWith(prefix) && !words.contains(member)) {
				words.add(member);
			}
		}
		return of(prefix, words);
	}

	/** How many lines above the caret's a call may begin. */
	static final int CALL_LINES = 20;
	private static final int CALL_TOKENS = 400;

	/**
	 * The call the caret at column of line is an argument of, or null:
	 * the nearest "(" before it that is still open and follows a name,
	 * and how many commas of that call stand before the caret. It looks
	 * back no further than the statement, {@link #CALL_LINES} lines and
	 * 400 tokens, so its work does not grow with the text.
	 */
	public static Syntax.Call call(Syntax.Lines lines, int line,
			int column) {
		int nesting = 0;
		int commas = 0;
		int budget = CALL_TOKENS;
		for (int n = line; n >= 0 && n > line - CALL_LINES; --n) {
			LexedLine tokens = lines.line(n);
			String text = tokens.text();
			int i = tokens.size() - 1;
			if (n == line) {
				// The last token that starts before the caret, however
				// long the line is behind it.
				int low = 0;
				while (low <= i) {
					int mid = (low + i) >>> 1;
					if (tokens.start(mid) < column) {
						low = mid + 1;
					} else {
						i = mid - 1;
					}
				}
			}
			for (; i >= 0; --i) {
				if (--budget < 0) {
					return null;
				} else if (tokens.style(i) != Style.OPERATOR) {
					continue;
				}
				char c = text.charAt(tokens.start(i));
				if (c == ')' || c == ']') {
					++nesting;
				} else if (c == '(' || c == '[') {
					if (nesting > 0) {
						--nesting;
						continue;
					}
					int name = i - 1;
					while (name >= 0 &&
							tokens.style(name) == Style.COMMENT) {
						--name;
					}
					if (c == '(' && name >= 0 && isName(tokens.style(name))) {
						return new Syntax.Call(text.substring(
								tokens.start(name), tokens.end(name)), n,
								tokens.start(name), commas);
					}
					// A parenthesis that groups, a constructor, an
					// index: the caret is in an argument of a call
					// further out, if any.
					commas = 0;
				} else if (nesting == 0) {
					if (c == ',') {
						++commas;
					} else if (c == ';' || c == '{' || c == '}') {
						return null;
					}
				}
			}
		}
		return null;
	}

	private static boolean isName(Style style) {
		return style == Style.IDENTIFIER || style == Style.TYPE ||
				style == Style.BUILTIN_FUNCTION;
	}

	/**
	 * The words with a parenthesis after each that names a function,
	 * built-in or declared as known tells, but main: what a tap inserts.
	 */
	public static List<String> withParentheses(List<String> words,
			Knowledge known) {
		GlslKnowledge declared = known instanceof GlslKnowledge glsl
				? glsl
				: GlslKnowledge.NONE;
		List<String> result = new ArrayList<>(words.size());
		for (String word : words) {
			// GlslWords styles the gl_ variables as functions.
			boolean function = !word.startsWith("gl_") &&
					(GlslWords.style(word, 0, word.length()) ==
							Style.BUILTIN_FUNCTION ||
							(declared.isFunction(word) &&
									!"main".equals(word)));
			result.add(function ? word + "(" : word);
		}
		return result;
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
	 * names, then the names the text declares, then the language words,
	 * then the extra words in their order. A declaration whose name starts
	 * at wordStart is the word being typed and is left out.
	 */
	public static List<String> words(String prefix, boolean inDirective,
			GlslKnowledge known, int wordStart, List<String> extraWords) {
		if (prefix.isEmpty()) {
			return new ArrayList<>();
		}
		Set<String> words = new LinkedHashSet<>();
		if (inDirective) {
			words.addAll(GlslWords.matchingDirectives(prefix));
		}
		List<String> names = new ArrayList<>();
		for (Knowledge.Symbol declaration : known.symbols()) {
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
			GlslKnowledge known, int wordStart, List<String> extraWords) {
		return of(prefix, words(prefix, inDirective, known, wordStart,
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
	 * The names a text declares, sorted, for completing them without
	 * looking at every declaration. Immutable; its knowledge sorted them.
	 */
	public static final class Names {
		private final GlslKnowledge known;
		// Each name once, sorted, and how many declarations have it.
		private final String[] names;
		private final int[] counts;

		private Names(GlslKnowledge known, String[] names, int[] counts) {
			this.known = known;
			this.names = names;
			this.counts = counts;
		}

		public static Names of(GlslKnowledge known) {
			return new Names(known, known.names, known.counts);
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
			List<Knowledge.Symbol> declarations = known.symbols();
			int low = 0;
			int high = declarations.size() - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				Knowledge.Symbol declaration = declarations.get(mid);
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
