package de.markusfisch.android.shadereditor.syntax;

import java.util.Arrays;

/**
 * The tokens of one statement of GLSL: for each what it is, its text and
 * where it stood in the source. Whoever reads a text fills it token by
 * token; {@link GlslSymbols} tells what the finished statement declares.
 *
 * It keeps the text of the statement once, with one space where the
 * source has anything between two tokens, so a part of it reads as
 * written however it was laid out. Tokens stay in the order of the
 * source, and no question asked of it changes it.
 */
final class GlslStatement {
	/** What a token is to a declaration. */
	enum Token {
		TYPE(false),
		NAME(false),
		QUALIFIER(true),
		STRUCT(true),
		PRECISION(true),
		UNIFORM(true),
		CONST(true),
		LAYOUT(true),
		NUMBER(false),
		PUNCT(false),
		OTHER(false);

		/** Whether it is a keyword that leads up to a type. */
		final boolean keyword;

		Token(boolean keyword) {
			this.keyword = keyword;
		}

		/** Return what the token from start to end of line is. */
		static Token of(Style style, CharSequence line, int start, int end) {
			switch (style) {
				case PRIMITIVE:
				case TYPE_VECTOR:
				case TYPE_MATRIX:
				case TYPE_SAMPLER:
				case TYPE_IMAGE:
				case ATOMIC_COUNTER:
					return TYPE;
				case TYPE:
				case IDENTIFIER:
				case FIELD_SELECTION:
				case BUILTIN_FUNCTION:
					return NAME;
				case KEYWORD:
					return STRUCT;
				case TYPE_QUALIFIER:
					return is(line, start, end, "uniform") ? UNIFORM
							: is(line, start, end, "const") ? CONST
							: is(line, start, end, "layout") ? LAYOUT
							: QUALIFIER;
				case PRECISION:
					return is(line, start, end, "precision") ? PRECISION
							: QUALIFIER;
				case NUMBER:
					return NUMBER;
				case OPERATOR:
					return PUNCT;
				default:
					return OTHER;
			}
		}
	}

	private static final int MAX_TOKENS = 2048;
	private static final int MAX_CHARS = 16384;

	private final StringBuilder text = new StringBuilder();
	// Of each token: where it stands in the text, where it stood in the
	// source, and what it is.
	private int[] from = new int[64];
	private int[] to = new int[64];
	private int[] start = new int[64];
	private Token[] kind = new Token[64];
	private int size;

	/** Whether text has word from start to end. */
	static boolean is(CharSequence text, int start, int end, String word) {
		int length = word.length();
		if (end - start != length) {
			return false;
		}
		for (int k = 0; k < length; ++k) {
			if (text.charAt(start + k) != word.charAt(k)) {
				return false;
			}
		}
		return true;
	}

	// ---- Tokens ---------------------------------------------------------

	/**
	 * Add the token from start to end of line, which stands at offset in
	 * the source. A statement longer than any declaration keeps what it
	 * has.
	 */
	void add(CharSequence line, int start, int end, int offset, Token token) {
		if (size >= MAX_TOKENS || text.length() + end - start >= MAX_CHARS) {
			return;
		}
		if (size == from.length) {
			int capacity = size * 2;
			from = Arrays.copyOf(from, capacity);
			to = Arrays.copyOf(to, capacity);
			this.start = Arrays.copyOf(this.start, capacity);
			kind = Arrays.copyOf(kind, capacity);
		}
		if (size > 0 && offset > end(size - 1)) {
			text.append(' ');
		}
		from[size] = text.length();
		text.append(line, start, end);
		to[size] = text.length();
		this.start[size] = offset;
		kind[size] = token;
		++size;
	}

	/** Forget the first count tokens; the others are a statement. */
	void drop(int count) {
		if (count >= size) {
			clear();
			return;
		} else if (count <= 0) {
			return;
		}
		int cut = from[count];
		text.delete(0, cut);
		size -= count;
		for (int i = 0; i < size; ++i) {
			from[i] = from[i + count] - cut;
			to[i] = to[i + count] - cut;
			start[i] = start[i + count];
			kind[i] = kind[i + count];
		}
	}

	void clear() {
		size = 0;
		text.setLength(0);
	}

	int size() {
		return size;
	}

	boolean isEmpty() {
		return size == 0;
	}

	Token kind(int i) {
		return kind[i];
	}

	/** Return the character of token i if it is punctuation, else 0. */
	char punct(int i) {
		return kind[i] == Token.PUNCT ? text.charAt(from[i]) : 0;
	}

	boolean is(int i, char c) {
		return kind[i] == Token.PUNCT && text.charAt(from[i]) == c;
	}

	boolean is(int i, String word) {
		return is(text, from[i], to[i], word);
	}

	String text(int i) {
		return text.substring(from[i], to[i]);
	}

	/** Return the text of the tokens from from to to, as written. */
	String text(int from, int to) {
		return from >= to ? "" : text.substring(this.from[from],
				this.to[to - 1]);
	}

	/** Return where token i starts in the source. */
	int start(int i) {
		return start[i];
	}

	/** Return where token i ends in the source. */
	int end(int i) {
		return start[i] + to[i] - from[i];
	}

	boolean has(Token token) {
		for (int i = 0; i < size; ++i) {
			if (kind[i] == token) {
				return true;
			}
		}
		return false;
	}

	// ---- What stands where, in the first size tokens --------------------

	/**
	 * Return the token of the name the tokens from from to to start to
	 * declare, or -1: the last of the words they start with, if it is a
	 * name and a type or another name stands before it. What stands
	 * before those two qualifies them, whether GLSL knows the word or
	 * not: "uniform PREC float speed", "static const float x",
	 * "inline float2 f".
	 */
	int name(int from, int to) {
		int last = wordBefore(afterWords(from, to), from);
		return last >= 0 && kind[last] == Token.NAME &&
				wordBefore(last, from) >= 0 ? last : -1;
	}

	/**
	 * Return the token of the name if the first size tokens are the head
	 * of a function, a type, a name and a "(", else -1.
	 */
	int functionName(int size) {
		int i = afterQualifiers(size);
		if (i < size && kind[i] == Token.PRECISION) {
			return -1;
		}
		int name = name(0, size);
		return name >= 0 && name + 1 < size && is(name + 1, '(')
				? name : -1;
	}

	/**
	 * Return the token after the words the tokens from from to to start
	 * with: qualifiers, types and names, with the brackets behind a type
	 * or a name and the parentheses behind "layout".
	 */
	int afterWords(int from, int to) {
		int i = from;
		while (i < to) {
			Token token = kind[i];
			if (token == Token.TYPE || token == Token.NAME) {
				for (++i; i < to && is(i, '['); ) {
					int closed = closing(i, to);
					if (closed < 0) {
						// A bracket nothing closes is no word.
						return i;
					}
					i = closed + 1;
				}
			} else if (token.keyword && token != Token.STRUCT) {
				++i;
				if (token == Token.LAYOUT && i < to && is(i, '(')) {
					i = afterClosing(i, to);
				}
			} else {
				break;
			}
		}
		return i;
	}

	/** Return the bracket that the closing one at i closes, or -1. */
	int opening(int i) {
		int nesting = 0;
		for (; i >= 0; --i) {
			char c = punct(i);
			if (c == ')' || c == ']' || c == '}') {
				++nesting;
			} else if ((c == '(' || c == '[' || c == '{') &&
					--nesting == 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Return the type the words from from up to the name at token name
	 * say, as GLSL writes it without qualifiers: the last type or name
	 * among them, an array with its brackets, and with the brackets
	 * behind the name if it has any ("float a[2]" is a "float[2]").
	 * Null when there is none.
	 */
	String type(int from, int name, int to) {
		int word = wordBefore(name, from);
		if (word < 0) {
			return null;
		}
		String type = joined(word, name);
		int end = name < to ? afterBrackets(name + 1, to) : name;
		return end > name + 1 ? type + joined(name + 1, end) : type;
	}

	// The text of the tokens from from to to, with nothing between.
	private String joined(int from, int to) {
		if (to - from == 1) {
			return text(from);
		}
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < to; ++i) {
			sb.append(text, this.from[i], this.to[i]);
		}
		return sb.toString();
	}

	// Returns the last type or name before token i, from from on, over
	// qualifiers and whole pairs of brackets, or -1. A bracket that
	// nothing closes hides nothing before it.
	private int wordBefore(int i, int from) {
		int nesting = 0;
		while (--i >= from) {
			char c = punct(i);
			if (c == ')' || c == ']') {
				++nesting;
			} else if (c == '(' || c == '[') {
				nesting = Math.max(0, nesting - 1);
			} else if (nesting == 0 && (kind[i] == Token.TYPE ||
					kind[i] == Token.NAME)) {
				return i;
			}
		}
		return -1;
	}

	/** Return the token after the qualifiers the statement starts with. */
	int afterQualifiers(int size) {
		int i = 0;
		while (i < size && kind[i].keyword && kind[i] != Token.STRUCT &&
				kind[i] != Token.PRECISION) {
			if (kind[i++] == Token.LAYOUT && i < size && is(i, '(')) {
				i = afterClosing(i, size);
			}
		}
		return i;
	}

	/** Return the token after the brackets that start at i, if any. */
	int afterBrackets(int i, int size) {
		while (i < size && is(i, '[')) {
			i = afterClosing(i, size);
		}
		return i;
	}

	/** Return the token after the bracket that closes the one at i. */
	int afterClosing(int i, int size) {
		int closed = closing(i, size);
		return closed < 0 ? size : closed + 1;
	}

	/** Return the bracket that closes the one at i, or -1. */
	int closing(int i, int size) {
		int nesting = 0;
		for (; i < size; ++i) {
			char c = punct(i);
			if (c == '(' || c == '[' || c == '{') {
				++nesting;
			} else if ((c == ')' || c == ']' || c == '}') &&
					--nesting == 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Return the token of the "," or ";" outside of brackets that ends
	 * the declarator at i, or to if nothing ends it.
	 */
	int afterDeclarator(int i, int to) {
		int nesting = 0;
		for (; i < to; ++i) {
			char c = punct(i);
			if (c == '(' || c == '[' || c == '{') {
				++nesting;
			} else if (c == ')' || c == ']' || c == '}') {
				--nesting;
			} else if (nesting <= 0 && (c == ',' || c == ';')) {
				break;
			}
		}
		return i;
	}

	/**
	 * Tell whether a token that is first on its line starts a statement
	 * of its own behind this one, which is not empty and has no bracket
	 * open, so this one lost its ";": a keyword unless keywords lead up
	 * to it; any word behind a type and a name, which are a declaration;
	 * a type behind a type, since a name is what a type declares; or a
	 * word behind what no declaration goes on after, a name, a number or
	 * a closing bracket.
	 */
	boolean endsBefore(Token next) {
		Token before = kind[size - 1];
		if (next.keyword) {
			return !before.keyword;
		} else if (next != Token.TYPE && next != Token.NAME) {
			return false;
		}
		char c = punct(size - 1);
		if (afterWords(0, size) == size) {
			// Nothing but words so far.
			if (name(0, size) >= 0) {
				return true;
			}
			return wordBefore(size, 0) < 0 ? c == ')' : next == Token.TYPE;
		}
		return before == Token.NAME || before == Token.NUMBER || c == ')' ||
				c == ']' || c == '}';
	}
}
