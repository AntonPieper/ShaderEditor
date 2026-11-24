package de.markusfisch.android.shadereditor.editor;

import java.util.List;
import java.util.Set;

/**
 * The one shortcut past the list of {@link Definitions}: a word in a
 * function that a local or parameter of that function declares, seen
 * from where it stands by the braces alone. Taken only where nothing
 * could change what the text says: no directive line and no name of a
 * macro from the function's name to the word, and the word no macro.
 * Pure, to be run off the main thread.
 */
public final class Locals {
	private Locals() {
	}

	/**
	 * Return where word, at offset of the text index read, is declared
	 * as a local or parameter of the function it stands in, the
	 * innermost declaration seen there; -1 when there is none or the
	 * shortcut is not taken.
	 */
	public static int of(Definitions.Index index, int offset, String word,
			Set<String> macros) {
		String s = index.text;
		List<int[]> code = index.code;
		int[] match = index.match;
		int body = -1;
		for (int[] f : index.functions) {
			if (f[0] < offset && offset < f[1]) {
				body = f[2];
			}
		}
		if (body < 1 || macros.contains(word) || match[body - 1] < 1) {
			return -1;
		}
		int params = match[body - 1];
		int from = code.get(params - 1)[0];
		for (int d : index.directives) {
			if (from <= d && d < offset) {
				return -1;
			}
		}
		int found = -1;
		for (int k = params - 1; k < code.size() && code.get(k)[0] < offset;
				++k) {
			int[] t = code.get(k);
			if (!Definitions.word(t)) {
				continue;
			}
			String w = s.substring(t[0], t[1]);
			if (macros.contains(w)) {
				return -1;
			} else if (k <= params || !w.equals(word) ||
					!declares(s, code, match, k)) {
				continue;
			}
			// A parameter is seen in the whole body; a local after its
			// declarator, up to the end of its block or statement.
			int seen = k < body ? code.get(body)[0] : end(s, code, match, k);
			int until = k < body ? code.get(match[body])[0]
					: scope(s, code, match, k);
			if (seen <= offset && offset < until) {
				found = t[0];
			}
		}
		return found;
	}

	// Whether the word at k stands where a declaration names it.
	private static boolean declares(String s, List<int[]> code, int[] match,
			int k) {
		int[] p = code.get(k - 1);
		return Definitions.names(p) || Definitions.is(s, p, "]") ||
				(Definitions.is(s, p, ",") &&
						Definitions.declarators(s, code, match, k - 1));
	}

	// Where the declarator at k ends: the next ",", ";" or ")" outside
	// parentheses.
	private static int end(String s, List<int[]> code, int[] match, int k) {
		for (int i = k + 1; i < code.size(); ++i) {
			int[] q = code.get(i);
			if (match[i] > i && Definitions.is(s, q, "(")) {
				i = match[i];
			} else if (Definitions.is(s, q, ",") || Definitions.is(s, q, ";") ||
					Definitions.is(s, q, ")")) {
				return q[0];
			}
		}
		return Integer.MAX_VALUE;
	}

	// Where what a local declared at k is seen in ends: the statement of
	// a "for" whose header declares it; the one statement behind if,
	// else, for, while or do that it is; else its block.
	private static int scope(String s, List<int[]> code, int[] match, int k) {
		for (int j = k - 1; j >= 0; --j) {
			int[] q = code.get(j);
			if (Definitions.is(s, q, ")") && match[j] > 0) {
				if (head(s, code.get(match[j] - 1))) {
					return statement(s, code, match, j + 1);
				}
				j = match[j];
			} else if (Definitions.is(s, q, "else") ||
					Definitions.is(s, q, "do")) {
				return statement(s, code, match, j + 1);
			} else if (Definitions.is(s, q, "(")) {
				return j > 0 && head(s, code.get(j - 1)) && match[j] > 0
						? statement(s, code, match, match[j] + 1)
						: -1;
			} else if (Definitions.is(s, q, ";") || Definitions.is(s, q, "{") ||
					Definitions.is(s, q, "}")) {
				break;
			}
		}
		// The innermost "{" around k, passing the blocks closed before.
		for (int j = k - 1; j >= 0; --j) {
			if (Definitions.is(s, code.get(j), "}") && match[j] >= 0) {
				j = match[j];
			} else if (Definitions.is(s, code.get(j), "{")) {
				return match[j] > 0 ? code.get(match[j])[0] : -1;
			}
		}
		return -1;
	}

	private static boolean head(String s, int[] t) {
		return Definitions.is(s, t, "if") || Definitions.is(s, t, "for") ||
				Definitions.is(s, t, "while");
	}

	// Where the statement starting at token i ends.
	private static int statement(String s, List<int[]> code, int[] match,
			int i) {
		for (int k = i; k < code.size(); ++k) {
			int[] q = code.get(k);
			if (match[k] > k) {
				if (k == i && Definitions.is(s, q, "{")) {
					return code.get(match[k])[0];
				}
				k = match[k];
			} else if (Definitions.is(s, q, ";")) {
				return q[0];
			}
		}
		return Integer.MAX_VALUE;
	}
}
