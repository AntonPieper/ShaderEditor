package de.markusfisch.android.shadereditor.project.legacy;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.SourceText;

/**
 * The converted text of a database shader with everything the user
 * wrote still in it. The old renderer dropped every character outside
 * ASCII before it read a shader, and so does the conversion; a file is
 * the user's text, so what the conversion did not rewrite is taken
 * from the original, comments and all, and only line ends become line
 * feeds. Where that cannot be done without losing a character or
 * changing what the shader draws, it says why instead.
 */
public final class KeptText {
	/**
	 * The text for the file, or null and why the shader cannot be
	 * written as a file that says the same.
	 */
	public record Kept(String shader, String loss) {
	}

	// The most cells of a table that aligns two lines.
	private static final long MOST_CELLS = 1_000_000;

	private KeptText() {
	}

	/**
	 * Return the shader of conversion, which was made of original,
	 * with the characters of original that the conversion dropped.
	 */
	public static Kept of(String original,
			LegacyShaderConverter.Result conversion) {
		String text = original.replace("\r\n", "\n").replace('\r', '\n');
		List<String> lines = SourceText.lines(text);
		String shader = conversion.shader();
		List<String> converted = SourceText.lines(shader);
		List<Integer> origins = conversion.lines();
		if (converted.size() != origins.size()) {
			return lost("its lines cannot be told apart after conversion");
		}
		String inCode = insideCode(lines);
		if (inCode != null) {
			return lost(inCode);
		}
		String[] out = converted.toArray(new String[0]);
		boolean[] placed = new boolean[lines.size() + 1];
		for (int k = 0; k < out.length; ++k) {
			int line = origins.get(k);
			if (line < 1 || line > lines.size() || placed[line]) {
				continue;
			}
			placed[line] = true;
			List<Integer> pieces = new ArrayList<>();
			for (int j = k; j < out.length; ++j) {
				if (origins.get(j) == line) {
					pieces.add(j);
				}
			}
			String lost = keep(lines.get(line - 1), out, pieces);
			if (lost != null) {
				return lost("line " + line + " has the character " + lost +
						" in text the conversion rewrites");
			}
		}
		for (int line = 1; line <= lines.size(); ++line) {
			if (!placed[line] && !ascii(lines.get(line - 1))) {
				return lost("line " + line + " has the character " +
						first(lines.get(line - 1)) + " and is not part " +
						"of the converted shader");
			}
		}
		// A comment the conversion takes away must not leave what it
		// held behind as code.
		int[] before = outsideComments(lines, null, lines.size());
		int[] after = outsideComments(List.of(out), origins, lines.size());
		for (int line = 1; line <= lines.size(); ++line) {
			if (before[line] != after[line]) {
				return lost("line " + line + " has the character " +
						first(lines.get(line - 1)) + " in a comment the " +
						"conversion takes away");
			}
		}
		StringBuilder sb = new StringBuilder();
		for (String line : out) {
			sb.append(line).append('\n');
		}
		if (!shader.endsWith("\n") && sb.length() > 0) {
			sb.setLength(sb.length() - 1);
		}
		return new Kept(sb.toString(), null);
	}

	private static Kept lost(String why) {
		return new Kept(null, why);
	}

	// Put the original line in place of the pieces made of it. Return
	// a character of it that would be lost, or null.
	private static String keep(String original, String[] out,
			List<Integer> pieces) {
		if (ascii(original)) {
			return null;
		}
		// Where each character the conversion saw is in the original.
		int[] at = new int[original.length() + 1];
		StringBuilder seen = new StringBuilder();
		for (int i = 0; i < original.length(); ++i) {
			if (kept(original.charAt(i))) {
				at[seen.length()] = i;
				seen.append(original.charAt(i));
			}
		}
		int count = seen.length();
		at[count] = original.length();
		// The pieces are one line cut around inserted lines.
		StringBuilder joined = new StringBuilder();
		for (int piece : pieces) {
			joined.append(out[piece]);
		}
		String now = joined.toString();
		int[] match = align(seen, now);
		StringBuilder merged = new StringBuilder();
		// Where each character of the converted line is in the merged.
		int[] pos = new int[now.length() + 1];
		int i = 0;
		int j = 0;
		int o = 0;
		while (i < count || j < now.length()) {
			int ni = i;
			while (ni < count && match[ni] < 0) {
				++ni;
			}
			int nj = ni < count ? match[ni] : now.length();
			int end = at[ni];
			int first = end;
			int last = end;
			if (ni > i) {
				// What the conversion took away here: a character it
				// did not see in the middle of that would go with it.
				first = at[i];
				last = at[ni - 1] + 1;
				if (!ascii(original.substring(first, last))) {
					return first(original.substring(first, last));
				}
			}
			// What it did not see around a rewrite stays around it.
			merged.append(original, o, first);
			for (; j < nj; ++j) {
				pos[j] = merged.length();
				merged.append(now.charAt(j));
			}
			merged.append(original, last, end);
			o = end;
			i = ni;
			if (ni < count) {
				pos[nj] = merged.length();
				merged.append(original.charAt(end));
				o = end + 1;
				i = ni + 1;
				j = nj + 1;
			}
		}
		// What it did not see behind the last character it saw.
		merged.append(original, o, original.length());
		pos[now.length()] = merged.length();
		// Each piece gets its share of the line; a dropped character
		// goes with what is before it.
		int from = 0;
		int start = 0;
		for (int k = 0; k < pieces.size(); ++k) {
			from += out[pieces.get(k)].length();
			int end = k == pieces.size() - 1 ? merged.length() : pos[from];
			out[pieces.get(k)] = merged.substring(start, end);
			start = end;
		}
		return null;
	}

	// For every character of seen, the character of now it stayed as,
	// or -1 where the conversion rewrote it: what is alike at both
	// ends, and the longest run of alike characters in between, so
	// that two rewrites on a line do not make one of what lies
	// between them.
	private static int[] align(CharSequence seen, String now) {
		int n = seen.length();
		int m = now.length();
		int[] match = new int[n];
		java.util.Arrays.fill(match, -1);
		int prefix = 0;
		int most = Math.min(n, m);
		while (prefix < most && seen.charAt(prefix) == now.charAt(prefix)) {
			match[prefix] = prefix;
			++prefix;
		}
		int suffix = 0;
		while (suffix < most - prefix && seen.charAt(n - 1 - suffix) ==
				now.charAt(m - 1 - suffix)) {
			match[n - 1 - suffix] = m - 1 - suffix;
			++suffix;
		}
		int a = n - prefix - suffix;
		int b = m - prefix - suffix;
		if (a == 0 || b == 0 || (long) a * b > MOST_CELLS) {
			return match;
		}
		short[][] longest = new short[a + 1][b + 1];
		for (int i = a - 1; i >= 0; --i) {
			for (int j = b - 1; j >= 0; --j) {
				longest[i][j] = seen.charAt(prefix + i) ==
						now.charAt(prefix + j)
						? (short) (longest[i + 1][j + 1] + 1)
						: (short) Math.max(longest[i + 1][j],
								longest[i][j + 1]);
			}
		}
		for (int i = 0, j = 0; i < a && j < b; ) {
			if (seen.charAt(prefix + i) == now.charAt(prefix + j)) {
				match[prefix + i++] = prefix + j++;
			} else if (longest[i + 1][j] >= longest[i][j + 1]) {
				++i;
			} else {
				++j;
			}
		}
		return match;
	}

	// The first character of text outside ASCII, to name it.
	private static String first(String text) {
		for (int i = 0; i < text.length(); ++i) {
			if (!kept(text.charAt(i))) {
				return new String(Character.toChars(text.codePointAt(i)));
			}
		}
		return "";
	}

	// The engine reads a character outside ASCII in code as a space,
	// where the old renderer read nothing: the same unless it stands
	// between two characters of code. Return where one does, or null.
	private static String insideCode(List<String> lines) {
		List<String> code = PreprocessorScan.scan(lines, null).code();
		for (int n = 0; n < code.size(); ++n) {
			String line = code.get(n);
			// A // comment that ends in a backslash went on in the
			// next line for the old renderer. With a character it
			// dropped behind the backslash, it does not in a file.
			String raw = lines.get(n);
			int tail = raw.length();
			while (tail > 0 && !kept(raw.charAt(tail - 1))) {
				--tail;
			}
			if (tail < raw.length() && tail > line.length() &&
					raw.charAt(tail - 1) == '\\') {
				return "line " + (n + 1) + " ends a comment in a " +
						"backslash and the character " +
						first(raw.substring(tail)) + ", which the old " +
						"renderer dropped, so that the comment went on " +
						"in the next line";
			}
			for (int i = 0; i < line.length(); ++i) {
				if (kept(line.charAt(i))) {
					continue;
				}
				int end = i;
				while (end < line.length() && !kept(line.charAt(end))) {
					++end;
				}
				if (i > 0 && end < line.length() &&
						!blank(line.charAt(i - 1)) &&
						!blank(line.charAt(end))) {
					return "line " + (n + 1) + " has the character " +
							new String(Character.toChars(
									line.codePointAt(i))) +
							" inside code, where the old renderer " +
							"dropped it and a file reads a space";
				}
				i = end;
			}
		}
		return null;
	}

	// How many characters outside ASCII each original line has
	// outside comments, by its 1-based number; origins tell for lines
	// of the converted shader which original line each is of.
	private static int[] outsideComments(List<String> lines,
			List<Integer> origins, int originals) {
		int[] counts = new int[originals + 1];
		List<String> code = PreprocessorScan.scan(lines, null).code();
		for (int n = 0; n < code.size(); ++n) {
			int line = origins != null ? origins.get(n) : n + 1;
			String text = code.get(n);
			for (int i = 0; line > 0 && line <= originals &&
					i < text.length(); ++i) {
				counts[line] += kept(text.charAt(i)) ? 0 : 1;
			}
		}
		return counts;
	}

	private static boolean blank(char c) {
		return c == ' ' || c == '\t';
	}

	// What the old renderer, and so the conversion, read of a text.
	private static boolean kept(char c) {
		return c == '\t' || (c >= 0x20 && c <= 0x7e);
	}

	private static boolean ascii(String text) {
		for (int i = 0; i < text.length(); ++i) {
			if (!kept(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}
}
