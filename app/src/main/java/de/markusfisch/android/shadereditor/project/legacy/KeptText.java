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
			if (!keep(lines.get(line - 1), out, pieces)) {
				return lost("line " + line + " has characters outside " +
						"ASCII in text the conversion rewrites");
			}
		}
		for (int line = 1; line <= lines.size(); ++line) {
			if (!placed[line] && !ascii(lines.get(line - 1))) {
				return lost("line " + line + " has characters outside " +
						"ASCII and is not part of the converted shader");
			}
		}
		// A comment the conversion takes away must not leave what it
		// held behind as code.
		if (outsideComments(List.of(out)) != outsideComments(lines)) {
			return lost("a comment the conversion rewrites holds " +
					"characters outside ASCII");
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

	// Put the original line in place of the pieces made of it; false
	// when a character of it would be lost.
	private static boolean keep(String original, String[] out,
			List<Integer> pieces) {
		if (ascii(original)) {
			return true;
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
		int prefix = 0;
		int most = Math.min(count, now.length());
		while (prefix < most && seen.charAt(prefix) == now.charAt(prefix)) {
			++prefix;
		}
		int suffix = 0;
		while (suffix < most - prefix && seen.charAt(count - 1 - suffix) ==
				now.charAt(now.length() - 1 - suffix)) {
			++suffix;
		}
		// What was rewritten of the original: from the first to the
		// last character the conversion saw there.
		int first = at[prefix];
		int last = count - suffix > prefix
				? at[count - suffix - 1] + 1
				: first;
		if (!ascii(original.substring(first, last))) {
			return false;
		}
		int middle = now.length() - suffix - prefix;
		String merged = original.substring(0, first) +
				now.substring(prefix, prefix + middle) +
				original.substring(last);
		// Each piece gets its share of the line; a dropped character
		// goes with what is before it.
		int from = 0;
		int start = 0;
		for (int i = 0; i < pieces.size(); ++i) {
			from += out[pieces.get(i)].length();
			int end;
			if (i == pieces.size() - 1) {
				end = merged.length();
			} else if (from <= prefix) {
				end = at[from];
			} else if (from >= prefix + middle) {
				end = first + middle +
						at[count - (now.length() - from)] - last;
			} else {
				end = first + from - prefix;
			}
			out[pieces.get(i)] = merged.substring(start, end);
			start = end;
		}
		return true;
	}

	// The engine reads a character outside ASCII in code as a space,
	// where the old renderer read nothing: the same unless it stands
	// between two characters of code. Return where one does, or null.
	private static String insideCode(List<String> lines) {
		List<String> code = PreprocessorScan.scan(lines, null).code();
		for (int n = 0; n < code.size(); ++n) {
			String line = code.get(n);
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

	// How many characters outside ASCII lines have outside comments.
	private static int outsideComments(List<String> lines) {
		int count = 0;
		for (String line : PreprocessorScan.scan(lines, null).code()) {
			for (int i = 0; i < line.length(); ++i) {
				count += kept(line.charAt(i)) ? 0 : 1;
			}
		}
		return count;
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
