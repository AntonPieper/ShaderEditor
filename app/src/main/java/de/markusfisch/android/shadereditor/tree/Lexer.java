package de.markusfisch.android.shadereditor.tree;

/**
 * Layer 0 of the tree: reads a text into tokens, one at a time. It
 * accepts any text and never throws.
 *
 * It reads as a compiler does (each rule checked against Mesa):
 *
 * A backslash that ends its line joins the next line to it before
 * anything else is read, in every version of the language. So a name,
 * a number, an operator or a comment marker may be written across it,
 * and is one token. A backslash that ends the text ends a line too:
 * the engine compiles the text with a line end behind it.
 *
 * A comment is white space. A block comment does not end the line it
 * starts on: a directive goes on behind one that spans lines.
 *
 * A directive starts at a "#" that is the first token on its line and
 * ends with the line.
 *
 * The tokens are those of the language: "1.0foo" is "1.0f" and "oo",
 * "09" is "0" and "9". But the preprocessor takes a number and every
 * letter, digit, dot and signed exponent that follows it without a
 * gap for one number, and expands no macro there: such a name is a
 * GLUED_NAME.
 *
 * A character a compiler cannot read (anything but a tab, a line end
 * and printable ASCII) is white space, as it is in the text the engine
 * compiles. Behind a backslash that ends a line of code it is left
 * out, and the line still goes on; in a comment it is a space there,
 * and the comment ends with its line.
 *
 * In #include and #pragma se a quote starts a path that ends at the
 * next quote or with the line. GLSL has no strings; in any other line
 * a quote is a character no token starts with.
 */
final class Lexer {
	private static final String PUNCTUATORS = "<>+-*/%&|^=!,~;:?(){}[].#";
	// Those of them another character may make a longer operator of.
	private static final String GROWING = "<>+-*/%&|^=!#";

	private final CharSequence text;
	private final int length;
	private final int base;
	private final Symbols symbols;
	private final RawTokens out;
	private final StringBuilder joinedSpelling = new StringBuilder();
	private int position;
	private int reach;
	private boolean lineStart;
	private boolean spaced;
	// In a directive line: how many tokens it has so far, and whether
	// a quote starts a path in it.
	private boolean directive;
	private int directiveTokens;
	private boolean paths;
	// Whether the token being read is written across a line continuation.
	private boolean joined;
	// Where the number ends that the preprocessor sees at this place.
	private int numberEnd;

	/**
	 * @param from where to start: the start of the text, or behind a
	 *             ";" or "}" that is not in a directive line
	 * @param out  receives the tokens, with offsets from there
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
		spaced = false;
		int start = gap(position);
		if (start >= length) {
			position = length;
			return false;
		}
		char c = text.charAt(start);
		int flags = (lineStart ? RawTokens.LINE_START : 0) |
				(directive ? RawTokens.DIRECTIVE : 0) |
				(spaced ? RawTokens.SPACED : 0);
		byte kind = RawTokens.PUNCTUATOR;
		int end = start + 1;
		joined = false;
		if (c == '#' && lineStart && !directive && after(end) != '#') {
			directive = true;
			directiveTokens = 0;
			paths = false;
			flags |= RawTokens.DIRECTIVE;
		} else if (isNameStart(c)) {
			kind = start < numberEnd ? RawTokens.GLUED_NAME : RawTokens.NAME;
			while (isNamePart(after(end))) {
				end = take(end);
			}
		} else if (isDigit(c) || (c == '.' && isDigit(after(end)))) {
			kind = RawTokens.NUMBER;
			end = number(start, c);
			numberEnd = Math.max(numberEnd, preprocessingNumberEnd(end));
		} else if (c == '"' && paths) {
			kind = RawTokens.STRING;
			end = stringEnd(end);
		} else if (GROWING.indexOf(c) >= 0) {
			end = operator(end, c);
		} else if (PUNCTUATORS.indexOf(c) < 0) {
			kind = RawTokens.INVALID;
		}
		lineStart = false;
		position = end;
		int symbol = joined ? symbols.of(unjoined(start, end), 0,
				joinedSpelling.length()) : symbols.of(text, start, end);
		out.add(kind, start - base, end - base, symbol, flags);
		if (directive && ++directiveTokens <= 3) {
			// "# include", or "# pragma se".
			paths |= directiveTokens == 2 ? symbol == Words.INCLUDE
					: directiveTokens == 3 && symbol == Words.SE &&
							out.symbol[out.size - 2] == Words.PRAGMA;
		}
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
	// their lines at i. In code what a compiler cannot read is left
	// out between such a backslash and its line end.
	private int past(int i, boolean code) {
		while (at(i) == '\\') {
			int j = i + 1;
			while (code && j < length && !compilerReads(at(j))) {
				++j;
			}
			char c = at(j);
			if (j >= length) {
				return length;
			} else if (c == '\n') {
				i = at(j + 1) == '\r' ? j + 2 : j + 1;
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
		return at(past(end, true));
	}

	// The end of the token with that character taken.
	private int take(int end) {
		int i = past(end, true);
		joined |= i != end;
		return i + 1;
	}

	// Where the next token starts at or behind i, or the end of the text.
	private int gap(int i) {
		while (true) {
			char c = at(i);
			if (i >= length) {
				return length;
			} else if (c == '\\') {
				int j = past(i, true);
				if (j == i) {
					return i;
				}
				i = j;
				continue;
			} else if (c == '/') {
				int j = past(i + 1, true);
				char d = at(j);
				if (d == '/') {
					i = lineCommentEnd(j + 1);
				} else if (d == '*') {
					i = blockCommentEnd(j + 1);
				} else {
					return i;
				}
			} else if (c == '\n' || c == '\r') {
				lineStart = true;
				directive = false;
				paths = false;
				++i;
			} else if (c == ' ' || c == '\t' || !compilerReads(c)) {
				++i;
			} else {
				return i;
			}
			spaced = true;
		}
	}

	private int lineCommentEnd(int i) {
		while (true) {
			i = past(i, false);
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
				int j = past(i + 1, false);
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
			} else if (c == '\n' || c == '\r' ||
					(c == '\\' && past(i, true) != i)) {
				return i;
			}
		}
		at(length);
		return length;
	}

	// The end of the number of the language that starts at start with
	// first: no longer than it is one.
	private int number(int start, char first) {
		int end = start + 1;
		if (first == '0' && (after(end) | 0x20) == 'x') {
			int digits = take(end);
			if (!isHexDigit(after(digits))) {
				return end;
			}
			end = digits;
			while (isHexDigit(after(end))) {
				end = take(end);
			}
			return (after(end) | 0x20) == 'u' ? take(end) : end;
		}
		boolean real = first == '.';
		end = digits(end);
		if (!real && after(end) == '.') {
			real = true;
			end = digits(take(end));
		}
		if ((after(end) | 0x20) == 'e') {
			int exponent = take(end);
			char sign = after(exponent);
			if (sign == '+' || sign == '-') {
				exponent = take(exponent);
			}
			if (isDigit(after(exponent))) {
				real = true;
				end = digits(exponent);
			}
		}
		if (!real && first == '0') {
			// Octal: it ends before an 8 or a 9.
			end = start + 1;
			for (char c; (c = after(end)) >= '0' && c <= '7'; ) {
				end = take(end);
			}
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

	// Where the preprocessor's number ends that has a number of the
	// language up to end: behind every letter, digit, underscore and
	// dot that follows, and every sign behind an e or a p.
	private int preprocessingNumberEnd(int end) {
		boolean wasJoined = joined;
		while (true) {
			char c = after(end);
			if (isNamePart(c) || c == '.') {
				end = take(end);
			} else if ((c == '+' || c == '-') && isExponent(end)) {
				end = take(end);
			} else {
				joined = wasJoined;
				return end;
			}
		}
	}

	// Whether the character before end, which belongs to a number, is
	// an e or a p.
	private boolean isExponent(int end) {
		int c = text.charAt(end - 1) | 0x20;
		return c == 'e' || c == 'p';
	}

	// The end of the operator that starts with c before end.
	private int operator(int end, char c) {
		char d = after(end);
		boolean twice = d == c && "+-&|^<>#".indexOf(c) >= 0;
		if (twice || (d == '=' && c != '#')) {
			end = take(end);
			if (twice && (c == '<' || c == '>') && after(end) == '=') {
				end = take(end);
			}
		}
		return end;
	}

	// The token [start, end) as it reads with its lines joined.
	private CharSequence unjoined(int start, int end) {
		joinedSpelling.setLength(0);
		for (int i = past(start, true); i < end; i = past(i + 1, true)) {
			joinedSpelling.append(text.charAt(i));
		}
		return joinedSpelling;
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

	private static boolean isHexDigit(char c) {
		return isDigit(c) || ((c | 0x20) >= 'a' && (c | 0x20) <= 'f');
	}

	private static boolean compilerReads(char c) {
		return c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0x7e);
	}
}
