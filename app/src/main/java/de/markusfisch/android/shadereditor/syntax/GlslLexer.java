package de.markusfisch.android.shadereditor.syntax;

import java.util.Arrays;

/**
 * Tokenizer for GLSL. It accepts any text and never throws. Every token
 * is non-empty and lies inside the text; what lies between tokens is
 * whitespace or a line continuation.
 *
 * A directive starts at a "#" that is the first token on its physical
 * line and ends at the end of the logical line. As in GLSL, a comment
 * does not remove the line ends inside it. In a directive, a quote starts a
 * string that ends at the next quote or the end of the line. A token
 * never spans a line continuation, except a comment.
 */
public final class GlslLexer {
	private static final String OPERATORS = "<>+-*/%&|^=!,~;:?(){}[].";
	// What the last token outside directives and comments was, for the
	// guess of type names and field selections.
	private static final int OTHER = 0;
	private static final int DOT = 1;
	private static final int STRUCT = 2;
	private static final int NAME = 3;

	private final CharSequence text;
	private final int length;
	private int[] starts;
	private int[] ends;
	private int[] kinds;
	private int size;
	private boolean directive;
	private boolean lineStart = true;
	private boolean nameExpected;
	private int last = -1;
	private int lastKind = OTHER;

	private GlslLexer(CharSequence text) {
		this.text = text;
		length = text.length();
		int capacity = length / 3 + 16;
		starts = new int[capacity];
		ends = new int[capacity];
		kinds = new int[capacity];
	}

	public static Tokens lex(CharSequence text) {
		GlslLexer lexer = new GlslLexer(text);
		lexer.run();
		int[] lineStarts = new int[16];
		int lines = 1;
		for (int i = 0, n = lexer.length; i < n; ++i) {
			char c = text.charAt(i);
			if (c == '\n' || (c == '\r' && (i + 1 == n ||
					text.charAt(i + 1) != '\n'))) {
				if (lines == lineStarts.length) {
					lineStarts = Arrays.copyOf(lineStarts, lines * 2);
				}
				lineStarts[lines++] = i + 1;
			}
		}
		return new Tokens(lexer.starts, lexer.ends, lexer.kinds, lexer.size,
				lineStarts, lines);
	}

	// PROTOTYPE (proto/editor-core): restart at the start of a line.
	// The state of the lexer between two lines fits in six bits.
	public static final int LINE_START = 0;
	private static final int MODE_MASK = 3;
	private static final int IN_BLOCK_COMMENT = 1;
	private static final int IN_LINE_COMMENT = 2;
	private static final int STATE_DIRECTIVE = 4;
	private static final int STATE_NAME_EXPECTED = 8;
	private static final int KIND_SHIFT = 4;

	private boolean blockOpen;
	private boolean commentRunsOn;

	/**
	 * Tokenizes one line that starts in state and returns the state the
	 * next line starts in. The tokens lie inside the line; a comment that
	 * goes on is cut at the end of the line.
	 *
	 * @param breakFollows whether a line break follows the line
	 */
	public static int lexLine(CharSequence line, boolean breakFollows,
			int state, LineTokens out) {
		int lineLength = line.length();
		GlslLexer lexer = new GlslLexer(breakFollows
				? new WithBreak(line)
				: line);
		lexer.directive = (state & STATE_DIRECTIVE) != 0;
		lexer.nameExpected = (state & STATE_NAME_EXPECTED) != 0;
		lexer.lastKind = (state >> KIND_SHIFT) & 3;
		int i = 0;
		int mode = state & MODE_MASK;
		if (mode == IN_BLOCK_COMMENT) {
			lexer.add(0, 0, Style.COMMENT);
			i = lexer.ends[lexer.size - 1] = lexer.blockCommentEnd(0);
		} else if (mode == IN_LINE_COMMENT) {
			i = lexer.add(0, lexer.lineCommentEnd(0), Style.COMMENT);
		}
		lexer.run(i);
		out.size = 0;
		out.ensure(lexer.size);
		for (int t = 0; t < lexer.size; ++t) {
			int end = Math.min(lexer.ends[t], lineLength);
			if (lexer.starts[t] < end) {
				out.starts[out.size] = lexer.starts[t];
				out.ends[out.size] = end;
				out.kinds[out.size] = lexer.kinds[t];
				++out.size;
			}
		}
		if (!breakFollows) {
			return LINE_START;
		}
		return (lexer.blockOpen
				? IN_BLOCK_COMMENT
				: lexer.commentRunsOn ? IN_LINE_COMMENT : 0) |
				(lexer.directive ? STATE_DIRECTIVE : 0) |
				(lexer.nameExpected ? STATE_NAME_EXPECTED : 0) |
				(lexer.lastKind << KIND_SHIFT);
	}

	private static final class WithBreak implements CharSequence {
		private final CharSequence line;
		private final int length;

		private WithBreak(CharSequence line) {
			this.line = line;
			length = line.length();
		}

		@Override
		public int length() {
			return length + 1;
		}

		@Override
		public char charAt(int index) {
			return index < length ? line.charAt(index) : '\n';
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return toString().subSequence(start, end);
		}

		@Override
		public String toString() {
			return line + "\n";
		}
	}

	private void run() {
		run(0);
	}

	private void run(int i) {
		while (i < length) {
			char c = text.charAt(i);
			if (c == '\n' || c == '\r') {
				i = afterLineEnd(i);
				directive = false;
				lineStart = true;
			} else if (c == '\\' && isLineEnd(i + 1)) {
				i = afterLineEnd(i + 1);
				lineStart = true;
			} else if (Character.isWhitespace(c)) {
				++i;
			} else if (c == '/' && charAt(i + 1) == '/') {
				commentRunsOn = false;
				i = add(i, lineCommentEnd(i + 2), Style.COMMENT);
			} else if (c == '/' && charAt(i + 1) == '*') {
				add(i, i + 2, Style.COMMENT);
				i = ends[size - 1] = blockCommentEnd(i + 2);
			} else {
				i = token(i, c);
			}
		}
	}

	private int token(int i, char c) {
		boolean first = lineStart;
		boolean name = nameExpected;
		lineStart = false;
		nameExpected = false;
		if (c == '#' && first && !directive) {
			directive = true;
			nameExpected = true;
			return add(i, i + 1, Style.DIRECTIVE);
		} else if (c == '"' && directive) {
			return add(i, stringEnd(i + 1), Style.STRING);
		} else if (Character.isDigit(c) ||
				(c == '.' && Character.isDigit(charAt(i + 1)))) {
			return number(i);
		} else if (Character.isLetter(c) || c == '_') {
			return word(i, name);
		} else if (OPERATORS.indexOf(c) > -1) {
			return code(i, i + 1, Style.OPERATOR, c == '.' ? DOT : OTHER);
		}
		int end = i + 1;
		if (Character.isHighSurrogate(c) &&
				Character.isLowSurrogate(charAt(end))) {
			++end;
		}
		return code(i, end, Style.INVALID, OTHER);
	}

	private int word(int start, boolean name) {
		int end = start + 1;
		for (char c; end < length && ((c = text.charAt(end)) == '_' ||
				Character.isLetterOrDigit(c)); ) {
			++end;
		}
		Style style = GlslWords.style(text, start, end);
		if (directive) {
			if (name && GlslWords.isDirective(text, start, end)) {
				style = Style.DIRECTIVE;
			}
			return add(start, end, style);
		}
		int kind = OTHER;
		if (style == Style.KEYWORD && end - start == 6 &&
				text.charAt(start) == 's') {
			kind = STRUCT;
		} else if (style == Style.IDENTIFIER) {
			if (lastKind == DOT) {
				style = Style.FIELD_SELECTION;
			} else if (lastKind == STRUCT) {
				style = Style.TYPE;
			} else {
				if (lastKind == NAME && last >= 0) {
					kinds[last] = Style.TYPE.ordinal();
				}
				kind = NAME;
			}
		}
		return code(start, end, style, kind);
	}

	private int number(int start) {
		int end = start;
		boolean octal = false;
		boolean notOctal = false;
		boolean real = false;
		if (text.charAt(end) == '0') {
			octal = true;
			char c = charAt(++end);
			if (c == 'x' || c == 'X') {
				do {
					++end;
				} while (end < length &&
						Character.digit(text.charAt(end), 16) != -1);
				return code(start, end, Style.NUMBER, OTHER);
			}
		}
		for (char c; end < length && Character.isDigit(c = text.charAt(end));
				++end) {
			notOctal |= c > '7';
		}
		if (charAt(end) == '.') {
			real = true;
			end = digits(end + 1);
		}
		char c = charAt(end);
		if (c == 'e' || c == 'E') {
			real = true;
			c = charAt(++end);
			if (c == '+' || c == '-') {
				++end;
			}
			if (!Character.isDigit(charAt(end))) {
				return code(start, end, Style.INVALID, OTHER);
			}
			end = digits(end);
			c = charAt(end);
		}
		if (real) {
			if (c == 'f' || c == 'F') {
				++end;
			}
			return code(start, end, Style.NUMBER, OTHER);
		}
		if (c == 'u' || c == 'U') {
			++end;
		}
		return code(start, end, octal && notOctal
				? Style.INVALID
				: Style.NUMBER, OTHER);
	}

	private int digits(int i) {
		while (i < length && Character.isDigit(text.charAt(i))) {
			++i;
		}
		return i;
	}

	private int lineCommentEnd(int i) {
		while (i < length) {
			char c = text.charAt(i);
			if (c == '\\' && isLineEnd(i + 1)) {
				i = afterLineEnd(i + 1);
			} else if (c == '\n' || c == '\r') {
				return i;
			} else {
				++i;
			}
		}
		commentRunsOn = true;
		return length;
	}

	private int blockCommentEnd(int i) {
		blockOpen = true;
		for (; i < length; ++i) {
			char c = text.charAt(i);
			if (c == '*' && charAt(i + 1) == '/') {
				blockOpen = false;
				return i + 2;
			} else if (c == '\n' || c == '\r') {
				directive = false;
				lineStart = true;
			}
		}
		return length;
	}

	private int stringEnd(int i) {
		for (; i < length; ++i) {
			char c = text.charAt(i);
			if (c == '"') {
				return i + 1;
			} else if (c == '\n' || c == '\r' ||
					(c == '\\' && isLineEnd(i + 1))) {
				return i;
			}
		}
		return length;
	}

	private int code(int start, int end, Style style, int kind) {
		if (!directive) {
			last = size;
			lastKind = kind;
		}
		return add(start, end, style);
	}

	private int add(int start, int end, Style style) {
		if (size == starts.length) {
			int capacity = size * 2;
			starts = Arrays.copyOf(starts, capacity);
			ends = Arrays.copyOf(ends, capacity);
			kinds = Arrays.copyOf(kinds, capacity);
		}
		starts[size] = start;
		ends[size] = end;
		kinds[size] = style.ordinal() | (directive ? Tokens.IN_DIRECTIVE : 0);
		++size;
		return end;
	}

	private boolean isLineEnd(int i) {
		char c = charAt(i);
		return c == '\n' || c == '\r';
	}

	private int afterLineEnd(int i) {
		return text.charAt(i) == '\r' && charAt(i + 1) == '\n' ? i + 2 : i + 1;
	}

	private char charAt(int i) {
		return i < length ? text.charAt(i) : 0;
	}
}
