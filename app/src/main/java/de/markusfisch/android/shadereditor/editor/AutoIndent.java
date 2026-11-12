package de.markusfisch.android.shadereditor.editor;

/**
 * The indentation of a new line, as the editor has always made it. A
 * line break typed in place of [from, to) is followed by the white
 * space that starts the line of from, up to to; inside a line that
 * starts with "//", by that too; and by one more level if the line
 * before from ends in '{' or an operator or leaves a parenthesis open.
 * Only the lines after the first copy their indentation.
 */
public final class AutoIndent {
	private AutoIndent() {
	}

	/** Return what follows a line break typed in place of [from, to). */
	public static String indentAfterBreak(Text text, int from, int to,
			String level) {
		int line = text.lineOf(from);
		int start = text.lineStart(line);
		String s = text.line(line);
		int caret = from - start;
		StringBuilder indent = new StringBuilder();
		if (line > 0) {
			indent.append(s, 0, copiedEnd(s, caret, to - start));
		}
		if (opens(s, caret)) {
			indent.append(level);
		}
		return indent.toString();
	}

	// Returns the end of the start of s that the new line repeats.
	private static int copiedEnd(String s, int caret, int limit) {
		int end = Math.min(limit, s.length());
		int i = 0;
		for (; i < end; ++i) {
			char c = s.charAt(i);
			// Only the first character is compared, so any line that
			// starts with a slash continues with its first two.
			if (caret < s.length() && c == '/' && i + 1 < limit) {
				return Math.min(i + 2, s.length());
			}
			if (c != ' ' && c != '\t') {
				break;
			}
		}
		return i;
	}

	private static boolean opens(String s, int caret) {
		int open = 0;
		boolean last = true;
		for (int i = caret; i-- > 0; ) {
			char c = s.charAt(i);
			if (c == ' ' || c == '\t') {
				continue;
			}
			if (last && "{+-*/%^=".indexOf(c) > -1) {
				++open;
			}
			last = false;
			if (c == '(') {
				++open;
			} else if (c == ')') {
				--open;
			}
		}
		return open > 0;
	}
}
