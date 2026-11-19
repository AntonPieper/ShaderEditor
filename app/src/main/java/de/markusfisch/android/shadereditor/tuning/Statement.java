package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * The statement a tuned number is in, on one line without comments, cut
 * before and after the number (EDITOR 15), found within a
 * {@link Window}.
 */
public record Statement(@NonNull String before, @NonNull String after,
		@NonNull List<Mark> marks) {
	/** Another number: start to end of before or after; offset in the text. */
	public record Mark(boolean inAfter, int start, int end, int offset,
			boolean changed) {
	}

	/** Whether the session changed the number whose literal starts at. */
	interface Changed {
		boolean at(int offset);
	}

	@NonNull
	static Statement of(@NonNull Window window, int from, int to,
			@NonNull Changed changed) {
		Tokens tokens = window.tokens;
		int size = tokens.size();
		int first = 0;
		while (first < size - 1 && tokens.end(first) <= from) {
			++first;
		}
		int last = first;
		while (last + 1 < size && tokens.start(last + 1) < to) {
			++last;
		}
		int start = first;
		int end = last;
		if (tokens.inDirective(first)) {
			while (start > 0 && tokens.inDirective(start - 1) &&
					!broken(window, tokens, start - 1)) {
				--start;
			}
			while (end + 1 < size && tokens.inDirective(end + 1) &&
					!broken(window, tokens, end)) {
				++end;
			}
		} else {
			// Forward from the window's first line, so that the ";" of a
			// for loop's header ends nothing.
			int depth = 0;
			start = 0;
			for (int i = 0; i < first; ++i) {
				char c = code(window, tokens, i);
				depth = Math.max(0, depth + depth(c));
				if (tokens.inDirective(i) || c == '{' || c == '}' ||
						(c == ';' && depth == 0)) {
					start = i + 1;
					depth = 0;
				}
			}
			for (int i = last + 1; i < size && !tokens.inDirective(i); ++i) {
				char c = code(window, tokens, i);
				end = i;
				depth += depth(c);
				if (c == '{' || (c == ';' && depth <= 0)) {
					break;
				}
			}
		}
		List<Mark> marks = new ArrayList<>();
		return new Statement(join(window, tokens, start, first, false, marks,
				changed), join(window, tokens, last + 1, end + 1, true, marks,
				changed), marks);
	}

	// The first character of token i, or 0 for a comment.
	private static char code(@NonNull Window window, @NonNull Tokens tokens,
			int i) {
		return tokens.style(i) == Style.COMMENT
				? 0
				: window.charAt(tokens.start(i));
	}

	private static int depth(char c) {
		return c == '(' || c == '[' ? 1 : c == ')' || c == ']' ? -1 : 0;
	}

	// Whether a line ends after token i, not continued by a backslash.
	private static boolean broken(@NonNull Window window,
			@NonNull Tokens tokens, int i) {
		String gap = window.subSequence(tokens.end(i), tokens.start(i + 1))
				.toString();
		int at = gap.indexOf('\n');
		return at > -1 && !gap.substring(0, at).trim().endsWith("\\");
	}

	// The tokens from from to to, but comments, on one line; with the
	// space the number had beside it.
	@NonNull
	private static String join(@NonNull Window window, @NonNull Tokens tokens,
			int from, int to, boolean inAfter, @NonNull List<Mark> marks,
			@NonNull Changed changed) {
		StringBuilder out = new StringBuilder();
		int previous = inAfter ? from - 1 : -1;
		String last = "";
		for (int i = from; i < to; ++i) {
			if (tokens.style(i) == Style.COMMENT) {
				continue;
			}
			String token = window.subSequence(tokens.start(i), tokens.end(i))
					.toString();
			if (previous > -1 && tokens.end(previous) < tokens.start(i) &&
					!last.equals("(") && !last.equals("[") &&
					!token.equals(")") && !token.equals("]") &&
					!token.equals(",") && !token.equals(";")) {
				out.append(' ');
			}
			if (tokens.style(i) == Style.NUMBER) {
				marks.add(new Mark(inAfter, out.length(),
						out.length() + token.length(), tokens.start(i),
						changed.at(tokens.start(i))));
			}
			out.append(token);
			previous = i;
			last = token;
		}
		if (!inAfter && previous > -1 && to < tokens.size() &&
				tokens.end(previous) < tokens.start(to) &&
				!last.equals("(") && !last.equals("[")) {
			out.append(' ');
		}
		return out.toString();
	}
}
