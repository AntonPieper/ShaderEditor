package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;

import de.markusfisch.android.shadereditor.editor.Document.Selection;

/**
 * Where the caret goes, as offsets into a text. A character is what a
 * user sees as one: a code point with the marks, variation selectors
 * and skin tones after it, emoji joined by a zero width joiner, or two
 * regional indicators (a flag). The caret never stops inside one. A
 * line break is one character. A word is a run of letters, digits and
 * underscores, or a run of other characters that are not white space.
 * A column counts characters, and a tab advances it to the next
 * multiple of the tab width.
 */
public final class Motion {
	private static final int SPACE = 0;
	private static final int WORD = 1;
	private static final int OTHER = 2;
	private static final int ZERO_WIDTH_JOINER = 0x200d;

	private Motion() {
	}

	/** Return the offset behind the character at offset. */
	public static int nextChar(Text text, int offset) {
		int line = text.lineOf(offset);
		int start = text.lineStart(line);
		String s = text.line(line);
		int i = offset - start;
		if (i < s.length()) {
			return start + charEnd(s, i);
		}
		return Math.min(offset + 1, text.length());
	}

	/** Return the offset of the character before offset. */
	public static int previousChar(Text text, int offset) {
		int line = text.lineOf(offset);
		int start = text.lineStart(line);
		int i = offset - start;
		if (i > 0) {
			int[] starts = starts(text.line(line), i);
			return start + starts[starts.length - 1];
		}
		return Math.max(offset - 1, 0);
	}

	/**
	 * Return the offset behind the next word: white space is skipped,
	 * then one word. At the end of a line, the start of the next.
	 */
	public static int nextWord(Text text, int offset) {
		int line = text.lineOf(offset);
		int start = text.lineStart(line);
		String s = text.line(line);
		int length = s.length();
		int i = offset - start;
		if (i == length) {
			return Math.min(offset + 1, text.length());
		}
		while (i < length && kindOf(s, i) == SPACE) {
			i = charEnd(s, i);
		}
		if (i < length) {
			int kind = kindOf(s, i);
			do {
				i = charEnd(s, i);
			} while (i < length && kindOf(s, i) == kind);
		}
		return start + i;
	}

	/**
	 * Return the offset of the previous word: white space is skipped,
	 * then one word. At the start of a line, the end of the previous.
	 */
	public static int previousWord(Text text, int offset) {
		int line = text.lineOf(offset);
		int start = text.lineStart(line);
		String s = text.line(line);
		int i = offset - start;
		if (i == 0) {
			return Math.max(offset - 1, 0);
		}
		int[] starts = starts(s, i);
		int k = starts.length - 1;
		while (k > 0 && kindOf(s, starts[k]) == SPACE) {
			--k;
		}
		int kind = kindOf(s, starts[k]);
		if (kind != SPACE) {
			while (k > 0 && kindOf(s, starts[k - 1]) == kind) {
				--k;
			}
		}
		return start + starts[k];
	}

	/** Return the offset of the start of the line of offset. */
	public static int lineStart(Text text, int offset) {
		return text.lineStart(text.lineOf(offset));
	}

	/** Return the offset of the end of the line of offset. */
	public static int lineEnd(Text text, int offset) {
		int line = text.lineOf(offset);
		return text.lineStart(line) + text.line(line).length();
	}

	/**
	 * Return the word at offset, or the run of white space if there is
	 * no word; a word that ends at offset wins over what follows.
	 */
	public static Selection wordAt(Text text, int offset) {
		int line = text.lineOf(offset);
		int start = text.lineStart(line);
		String s = text.line(line);
		int[] starts = starts(s, s.length());
		if (starts.length == 0) {
			return new Selection(offset, offset);
		}
		int i = offset - start;
		int k = Arrays.binarySearch(starts, i);
		if (k < 0) {
			k = -k - 2;
		} else if (k > 0 && kindOf(s, starts[k]) != WORD &&
				kindOf(s, starts[k - 1]) == WORD) {
			--k;
		}
		int kind = kindOf(s, starts[k]);
		int first = k;
		while (first > 0 && kindOf(s, starts[first - 1]) == kind) {
			--first;
		}
		int last = k + 1;
		while (last < starts.length && kindOf(s, starts[last]) == kind) {
			++last;
		}
		return new Selection(start + starts[first],
				start + (last < starts.length ? starts[last] : s.length()));
	}

	/** Return the column of offset in its line. */
	public static int column(Text text, int offset, int tabWidth) {
		int line = text.lineOf(offset);
		String s = text.line(line);
		int to = offset - text.lineStart(line);
		int column = 0;
		for (int i = 0; i < to; i = charEnd(s, i)) {
			column = advance(s, i, column, tabWidth);
		}
		return column;
	}

	/**
	 * Return the offset in line closest to column, the earlier one of
	 * two as close, and the end of the line if it is shorter.
	 */
	public static int offsetAt(Text text, int line, int column,
			int tabWidth) {
		String s = text.line(line);
		int i = 0;
		for (int at = 0; i < s.length(); ) {
			int next = advance(s, i, at, tabWidth);
			if (next > column) {
				if (next - column < column - at) {
					i = charEnd(s, i);
				}
				break;
			}
			at = next;
			i = charEnd(s, i);
		}
		return text.lineStart(line) + i;
	}

	private static int advance(String s, int i, int column, int tabWidth) {
		if (s.charAt(i) != '\t') {
			return column + 1;
		}
		int width = Math.max(1, tabWidth);
		return (column / width + 1) * width;
	}

	// Returns the starts of the characters of s before end.
	private static int[] starts(String s, int end) {
		int[] starts = new int[end];
		int n = 0;
		for (int i = 0; i < end; i = charEnd(s, i)) {
			starts[n++] = i;
		}
		return Arrays.copyOf(starts, n);
	}

	// Returns the end of the character that starts at i.
	private static int charEnd(String s, int i) {
		int length = s.length();
		int previous = s.codePointAt(i);
		int end = i + Character.charCount(previous);
		boolean flag = isRegionalIndicator(previous);
		while (end < length) {
			int cp = s.codePointAt(end);
			if (!isExtension(cp) && previous != ZERO_WIDTH_JOINER &&
					!(flag && isRegionalIndicator(cp))) {
				break;
			}
			flag = false;
			previous = cp;
			end += Character.charCount(cp);
		}
		return end;
	}

	private static boolean isExtension(int cp) {
		switch (Character.getType(cp)) {
			case Character.NON_SPACING_MARK:
			case Character.ENCLOSING_MARK:
			case Character.COMBINING_SPACING_MARK:
				return true;
			default:
				return cp == ZERO_WIDTH_JOINER ||
						// Skin tones.
						(cp >= 0x1f3fb && cp <= 0x1f3ff) ||
						// Tags, as in the flags of regions.
						(cp >= 0xe0020 && cp <= 0xe007f);
		}
	}

	private static boolean isRegionalIndicator(int cp) {
		return cp >= 0x1f1e6 && cp <= 0x1f1ff;
	}

	private static int kindOf(String s, int i) {
		int cp = s.codePointAt(i);
		if (Character.isWhitespace(cp)) {
			return SPACE;
		}
		return Character.isLetterOrDigit(cp) || cp == '_' ? WORD : OTHER;
	}
}
