package de.markusfisch.android.shadereditor.editor;

import de.markusfisch.android.shadereditor.syntax.Knowledge;

/**
 * Which words of the code are no name of their own to look up. Pure.
 */
public final class NameFilter {
	private NameFilter() {
	}

	/**
	 * Return whether the word at offset of text is no name of its own
	 * to look up: a field, behind a "." across spaces, line breaks, the
	 * comments that end lines and the backslashes that continue them,
	 * or where its struct declares it; or a qualifier in "layout(...)".
	 */
	public static boolean noName(Knowledge known, Text text, int offset) {
		if (inLayout(text, offset)) {
			return true;
		}
		int n = text.lineOf(offset);
		String line = text.line(n);
		int i = offset - text.lineStart(n);
		for (int lines = 0; lines < 8; ++lines) {
			while (i > 0 && (Character.isWhitespace(line.charAt(i - 1)) ||
					line.charAt(i - 1) == '\\')) {
				--i;
			}
			if (i > 0 || n == 0) {
				if (i > 0 && line.charAt(i - 1) == '.') {
					return true;
				}
				break;
			}
			line = text.line(--n);
			int comment = line.indexOf("//");
			i = comment > -1 ? comment : line.length();
		}
		for (Knowledge.Symbol symbol : known.symbols()) {
			if (symbol.from() > offset) {
				break;
			} else if (symbol.kind() == Knowledge.Kind.TYPE &&
					offset < symbol.to()) {
				for (Knowledge.Symbol part : symbol.parts()) {
					if (part.start() == offset) {
						return true;
					}
				}
			}
		}
		return false;
	}

	// Whether offset stands in the parentheses of a "layout" on its
	// line.
	private static boolean inLayout(Text text, int offset) {
		int lineStart = text.lineStart(text.lineOf(offset));
		String line = text.substring(lineStart, offset);
		for (int i = line.length(), depth = 0; i-- > 0; ) {
			char c = line.charAt(i);
			if (c == ')') {
				++depth;
			} else if (c == '(' && depth-- == 0) {
				return line.substring(0, i).trim().endsWith("layout");
			}
		}
		return false;
	}
}
