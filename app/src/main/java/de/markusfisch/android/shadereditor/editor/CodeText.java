package de.markusfisch.android.shadereditor.editor;

/**
 * Shader text as code sees it: its comments, and where asked the
 * contents of its strings, blanked, with every line and offset where
 * it was, so that what is found in it is found at the same place in
 * the text. A string is never taken for a comment, nor a comment for
 * a string.
 */
public final class CodeText {
	private CodeText() {
	}

	/**
	 * text with its comments blanked, and with strings the contents of
	 * its strings too.
	 */
	public static String blank(String text, boolean strings) {
		StringBuilder code = new StringBuilder(text);
		int length = text.length();
		int i = 0;
		while (i < length) {
			char c = text.charAt(i);
			char next = i + 1 < length ? text.charAt(i + 1) : 0;
			int from;
			int end;
			if (c == '/' && next == '/') {
				from = i;
				end = text.indexOf('\n', i);
				end = end < 0 ? length : end;
			} else if (c == '/' && next == '*') {
				from = i;
				end = text.indexOf("*/", i + 2);
				end = end < 0 ? length : end + 2;
			} else if (c == '"') {
				// A string ends at its quote or its line.
				from = i + 1;
				end = from;
				while (end < length && text.charAt(end) != '"' &&
						text.charAt(end) != '\n') {
					++end;
				}
				if (strings) {
					blank(code, from, end);
				}
				i = end < length && text.charAt(end) == '"' ? end + 1 : end;
				continue;
			} else {
				++i;
				continue;
			}
			blank(code, from, end);
			i = end;
		}
		return code.toString();
	}

	private static void blank(StringBuilder code, int from, int end) {
		for (int j = from; j < end; ++j) {
			if (code.charAt(j) != '\n') {
				code.setCharAt(j, ' ');
			}
		}
	}
}
