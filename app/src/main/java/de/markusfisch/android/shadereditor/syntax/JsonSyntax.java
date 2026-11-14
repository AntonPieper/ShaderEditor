package de.markusfisch.android.shadereditor.syntax;

import java.util.Collections;
import java.util.List;

/**
 * JSON as the editor treats it. A JSON string cannot hold a line break,
 * so every line starts in the same state and is coloured by itself:
 * strings, numbers, true, false and null, and punctuation; a token that
 * cannot be JSON, an unterminated string or a bad escape among them, is
 * invalid. Nothing is completed; braces and brackets indent.
 */
public final class JsonSyntax implements Syntax {
	public static final JsonSyntax INSTANCE = new JsonSyntax();

	private static final Indentation INDENTATION = new Indentation(null, "",
			"{[", "}]");

	private JsonSyntax() {
	}

	@Override
	public int lexLine(CharSequence line, boolean breakFollows, int state,
			LineTokens out) {
		out.clear();
		for (int i = 0, n = line.length(); i < n; ) {
			char c = line.charAt(i);
			int start = i;
			Style style;
			if (c == ' ' || c == '\t' || c == '\r' || c == '\uFEFF') {
				++i;
				continue;
			} else if (c == '"') {
				i = stringEnd(line, i + 1, n);
				style = i < 0 ? Style.INVALID : Style.STRING;
				i = i < 0 ? -i - 1 : i;
			} else if (c == '-' || (c >= '0' && c <= '9')) {
				while (i < n && "+-.eE0123456789".indexOf(
						line.charAt(i)) > -1) {
					++i;
				}
				style = isNumber(line, start, i)
						? Style.NUMBER
						: Style.INVALID;
			} else if (Character.isLetter(c)) {
				while (i < n && Character.isLetterOrDigit(line.charAt(i))) {
					++i;
				}
				style = is(line, start, i, "true") ||
						is(line, start, i, "false")
						? Style.BOOL
						: is(line, start, i, "null")
								? Style.KEYWORD
								: Style.INVALID;
			} else {
				i += Character.isHighSurrogate(c) && i + 1 < n &&
						Character.isLowSurrogate(line.charAt(i + 1)) ? 2 : 1;
				style = "{}[]:,".indexOf(c) > -1
						? Style.OPERATOR
						: Style.INVALID;
			}
			out.add(start, i, style);
		}
		return TEXT_START;
	}

	@Override
	public Outline.Builder outline(int maxDeclarations, int maxNameChars) {
		return null;
	}

	@Override
	public void complete(LexedLine line, int column, int lineStart,
			Outline declared, boolean current, List<String> extraWords,
			Offer offer) {
		offer.offer(Collections.emptyList(), 0);
	}

	@Override
	public Indentation indentation() {
		return INDENTATION;
	}

	// Returns the end of the string whose content starts at i, or minus
	// one less than the end when the string is not valid JSON.
	static int stringEnd(CharSequence line, int i, int n) {
		boolean valid = true;
		while (i < n) {
			char c = line.charAt(i++);
			if (c == '"') {
				return valid ? i : -i - 1;
			} else if (c == '\\') {
				char e = i < n ? line.charAt(i) : 0;
				int hex = e == 'u' ? 4 : 0;
				valid &= e != 0 && "\"\\/bfnrtu".indexOf(e) > -1;
				for (++i; hex-- > 0; ++i) {
					valid &= i < n && Character.digit(line.charAt(i), 16) > -1;
				}
				i = Math.min(i, n);
			} else {
				valid &= c >= ' ';
			}
		}
		return -n - 1;
	}

	private static boolean isNumber(CharSequence s, int i, int end) {
		if (s.charAt(i) == '-') {
			++i;
		}
		int digits = digits(s, i, end);
		if (digits == i || (s.charAt(i) == '0' && digits > i + 1)) {
			return false;
		}
		i = digits;
		if (i < end && s.charAt(i) == '.') {
			if ((i = digits(s, i + 1, end)) == digits + 1) {
				return false;
			}
		}
		if (i < end && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
			int sign = i + 1 < end && (s.charAt(i + 1) == '+' ||
					s.charAt(i + 1) == '-') ? i + 2 : i + 1;
			if ((i = digits(s, sign, end)) == sign) {
				return false;
			}
		}
		return i == end;
	}

	private static boolean is(CharSequence s, int start, int end,
			String word) {
		if (end - start != word.length()) {
			return false;
		}
		for (int k = 0; k < word.length(); ++k) {
			if (s.charAt(start + k) != word.charAt(k)) {
				return false;
			}
		}
		return true;
	}

	private static int digits(CharSequence s, int i, int end) {
		while (i < end && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
			++i;
		}
		return i;
	}
}
