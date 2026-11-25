package de.markusfisch.android.shadereditor.tree;

/**
 * Layer 0 of the tree: reads a text into tokens, one at a time, from
 * any place where a token may start. It accepts any text and never
 * throws.
 *
 * It reads as a compiler does (each rule checked against Mesa):
 *
 * A backslash that ends its line joins the next line to it before
 * anything else is read, in every version of the language. So a name,
 * a number, an operator or a comment marker may be written across it,
 * and is one token.
 *
 * A comment is white space. A block comment does not end the line it
 * starts on: a directive goes on behind one that spans lines.
 *
 * A directive starts at a "#" that is the first token on its line and
 * ends with the line.
 *
 * A number is a number of the language: "1.0foo" is "1.0f" and "oo".
 *
 * A character a compiler cannot read (anything but a tab, a line end
 * and printable ASCII) is white space, as it is in the text the engine
 * compiles. Behind a backslash at the end of a line it is left out,
 * and the line still goes on.
 *
 * In a directive line a quote starts a path that ends at the next
 * quote or with the line. GLSL has no strings; this is what #include
 * and #pragma se add.
 */
final class Lexer {
	private static final String PUNCTUATORS = "<>+-*/%&|^=!,~;:?(){}[].#";

	private final CharSequence text;
	private final int length;
	private final int base;
	private final Symbols symbols;
	private final RawTokens out;
	private int position;
	private int reach;
	private boolean lineStart;
	private boolean directive;
	// Whether the token being read is written across a line continuation.
	private boolean joined;

	/**
	 * @param from      where to start: the start of the text or the end
	 *                  of a token that is not in a directive line
	 * @param out       receives the tokens, with offsets from there
	 */
	Lexer(CharSequence text, int from, Symbols symbols, RawTokens out) {
		this.text = text;
		this.symbols = symbols;
		this.out = out;
		length = text.length();
		base = from;
		position = from;
		reach = from - 1;
		lineStart = from == 0;
	}

	/**
	 * How many characters from the start were looked at, the end of
	 * the text counted as one. The tokens read so far stay the same
	 * whatever follows these.
	 */
	int reach() {
		return reach + 1 - base;
	}

	/** Reads one more token; false at the end of the text. */
	boolean next() {
		int start = gap(position);
		if (start >= length) {
			position = length;
			return false;
		}
		char c = text.charAt(start);
		int flags = (lineStart ? RawTokens.LINE_START : 0) |
				(directive ? RawTokens.DIRECTIVE : 0);
		byte kind = RawTokens.PUNCTUATOR;
		int end;
		joined = false;
		if (c == '#' && lineStart && !directive) {
			directive = true;
			flags |= RawTokens.DIRECTIVE;
			end = start + 1;
		} else if (isNameStart(c)) {
			kind = RawTokens.NAME;
			end = start + 1;
			while (isNamePart(after(end))) {
				end = take(end);
			}
		} else if (isDigit(c) || (c == '.' && isDigit(after(start + 1)))) {
			end = number(start, c);
			kind = end < 0 ? RawTokens.INVALID : RawTokens.NUMBER;
			end = Math.abs(end);
		} else if (c == '"' && directive) {
			kind = RawTokens.STRING;
			end = stringEnd(start + 1);
		} else if (PUNCTUATORS.indexOf(c) >= 0) {
			end = punctuator(start, c);
		} else {
			kind = RawTokens.INVALID;
			end = start + 1;
		}
		lineStart = false;
		position = end;
		CharSequence spelling = joined ? unjoined(start, end) : text;
		out.add(kind, start - base, end - base, joined
				? symbols.of(spelling, 0, spelling.length())
				: symbols.of(text, start, end), flags);
		return true;
	}

	// The character at i, 0 behind the end; every look at the text.
	private char at(int i) {
		if (i > reach) {
			reach = i;
		}
		return i < length ? text.charAt(i) : 0;
	}

	// Where the line goes on: i, or behind the backslashes that end
	// their lines at i.
	private int past(int i) {
		while (at(i) == '\\') {
			int j = i + 1;
			while (j < length && !compilerReads(at(j))) {
				++j;
			}
			char c = at(j);
			if (c == '\n') {
				i = j + 1;
			} else if (c == '\r') {
				i = at(j + 1) == '\n' ? j + 2 : j + 1;
			} else {
				break;
			}
		}
		return i;
	}

	// The character a token that ends at end would go on with.
	private char after(int end) {
		return at(past(end));
	}

	// The end of the token with that character taken.
	private int take(int end) {
		int i = past(end);
		joined |= i != end;
		return i + 1;
	}

	// Where the next token starts at or behind i, or the end of the text.
	private int gap(int i) {
		while (true) {
			char c = at(i);
			if (i >= length) {
				return length;
			} else if (c == '\n' || c == '\r') {
				lineStart = true;
				directive = false;
				++i;
			} else if (c == '\\') {
				int j = past(i);
				if (j == i) {
					return i;
				}
				i = j;
			} else if (c == '/') {
				int j = past(i + 1);
				char d = at(j);
				if (d == '/') {
					i = lineCommentEnd(j + 1);
				} else if (d == '*') {
					i = blockCommentEnd(j + 1);
				} else {
					return i;
				}
			} else if (c == ' ' || c == '\t' || !compilerReads(c)) {
				++i;
			} else {
				return i;
			}
		}
	}

	private int lineCommentEnd(int i) {
		while (true) {
			i = past(i);
			char c = at(i);
			if (i >= length || c == '\n' || c == '\r') {
				return i;
			}
			++i;
		}
	}

	private int blockCommentEnd(int i) {
		for (; i < length; ++i) {
			if (text.charAt(i) == '*') {
				int j = past(i + 1);
				if (at(j) == '/') {
					return j + 1;
				}
			}
		}
		at(length);
		return length;
	}

	private int stringEnd(int i) {
		for (; i < length; ++i) {
			char c = at(i);
			if (c == '"') {
				return i + 1;
			} else if (c == '\n' || c == '\r' || (c == '\\' && past(i) != i)) {
				return i;
			}
		}
		at(length);
		return length;
	}

	// The end of the number that starts at start with first; negated
	// if its exponent has no digits.
	private int number(int start, char first) {
		int end = start + 1;
		if (first == '0' && (after(end) | 0x20) == 'x') {
			end = take(end);
			int digits = end;
			while (Character.digit(after(end), 16) >= 0) {
				end = take(end);
			}
			return end > digits && (after(end) | 0x20) == 'u' ? take(end) : end;
		}
		boolean real = first == '.';
		end = digits(end);
		if (!real && after(end) == '.') {
			real = true;
			end = digits(take(end));
		}
		if ((after(end) | 0x20) == 'e') {
			end = take(end);
			char sign = after(end);
			if (sign == '+' || sign == '-') {
				end = take(end);
			}
			if (!isDigit(after(end))) {
				return -end;
			}
			real = true;
			end = digits(end);
		}
		char suffix = (char) (after(end) | 0x20);
		return suffix == (real ? 'f' : 'u') ? take(end) : end;
	}

	private int digits(int end) {
		while (isDigit(after(end))) {
			end = take(end);
		}
		return end;
	}

	private int punctuator(int start, char c) {
		int end = start + 1;
		char d = after(end);
		boolean twice = d == c && "+-&|^<>#".indexOf(c) >= 0;
		if (twice || (d == '=' && "+-*/%&|^<>=!".indexOf(c) >= 0)) {
			end = take(end);
			if (twice && (c == '<' || c == '>') && after(end) == '=') {
				end = take(end);
			}
		}
		return end;
	}

	// The token [start, end) as it reads with its lines joined.
	private String unjoined(int start, int end) {
		StringBuilder sb = new StringBuilder(end - start);
		for (int i = past(start); i < end; i = past(i + 1)) {
			sb.append(text.charAt(i));
		}
		return sb.toString();
	}

	private static boolean isNameStart(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
	}

	private static boolean isNamePart(char c) {
		return isNameStart(c) || isDigit(c);
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean compilerReads(char c) {
		return c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0x7e);
	}
}
