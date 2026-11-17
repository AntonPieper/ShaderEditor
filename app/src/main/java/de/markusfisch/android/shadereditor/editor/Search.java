package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;

// A query holds no line break, so every match is inside one line. Matches do
// not overlap. It only reads immutable texts, so it runs on any thread.
public final class Search {
	// A text with more is told as having more.
	public static final int MAX = 10_000;
	// next stopped before it was done.
	public static final int UNKNOWN = -2;

	// The lines all reads between two looks at whether it is cancelled.
	private static final int SLICE = 512;

	public record Query(String text, boolean matchCase, boolean wholeWord) {
		public static final Query NONE = new Query("", false, false);

		public boolean isEmpty() {
			return text.isEmpty() || text.indexOf('\n') > -1;
		}
	}

	public record Replace(int from, int to, Text text) {
	}

	public interface Cancel {
		boolean cancelled();
	}

	// At most MAX from the start of the text, and whether there are more.
	public static final class Matches {
		public static final Matches NONE = new Matches(Text.EMPTY,
				Query.NONE, new int[0], false);

		private final Text text;
		private final Query query;
		private final int[] starts;
		private final boolean more;

		private Matches(Text text, Query query, int[] starts, boolean more) {
			this.text = text;
			this.query = query;
			this.starts = starts;
			this.more = more;
		}

		public Text text() {
			return text;
		}

		public Query query() {
			return query;
		}

		public int count() {
			return starts.length;
		}

		public boolean more() {
			return more;
		}

		public int start(int i) {
			return starts[i];
		}

		// -1 when none kept does.
		public int indexOf(int offset) {
			int i = Arrays.binarySearch(starts, offset);
			return i < 0 ? -1 : i;
		}

		// The first kept at or after offset, or count() when none is.
		public int after(int offset) {
			int i = Arrays.binarySearch(starts, offset);
			return i < 0 ? -i - 1 : i;
		}
	}

	private Search() {
	}

	// At most MAX; null when cancel stopped it.
	public static Matches all(Text text, Query query, Cancel cancel) {
		return all(text, query, MAX, cancel);
	}

	public static Matches all(Text text, Query query, int max,
			Cancel cancel) {
		if (query.isEmpty()) {
			return new Matches(text, query, new int[0], false);
		}
		int[] starts = new int[16];
		int count = 0;
		int length = query.text().length();
		for (int n = 0, lines = text.lineCount(), offset = 0; n < lines;
				++n) {
			if (n % SLICE == 0 && cancel.cancelled()) {
				return null;
			}
			String line = text.line(n);
			for (int at = inLine(line, 0, query); at > -1;
					at = inLine(line, at + length, query)) {
				if (count == max) {
					return new Matches(text, query, starts, true);
				} else if (count == starts.length) {
					starts = Arrays.copyOf(starts,
							(int) Math.min(max, count * 2L));
				}
				starts[count++] = offset + at;
			}
			offset += line.length() + 1;
		}
		return new Matches(text, query, Arrays.copyOf(starts, count), false);
	}

	// One change from the first match to the end of the last, the text between
	// them kept, so it is one step of undo; null when there is no match.
	public static Replace replaceAll(Matches matches, CharSequence with) {
		int n = matches.count();
		if (n == 0) {
			return null;
		}
		Text text = matches.text();
		int length = matches.query().text().length();
		String replacement = Text.of(with).toString();
		int first = matches.start(0);
		StringBuilder sb = new StringBuilder();
		for (int i = 0, at = first; i < n; ++i) {
			int start = matches.start(i);
			sb.append(text.substring(at, start)).append(replacement);
			at = start + length;
		}
		return new Replace(first, matches.start(n - 1) + length,
				Text.of(sb));
	}

	// -1 when there is none.
	public static int inLine(String line, int from, Query query) {
		if (query.isEmpty()) {
			return -1;
		}
		String q = query.text();
		int length = q.length();
		char lower = Character.toLowerCase(q.charAt(0));
		char upper = Character.toUpperCase(q.charAt(0));
		for (int at = Math.max(0, from), last = line.length() - length;
				at <= last; ++at) {
			if (query.matchCase()) {
				at = line.indexOf(q, at);
				if (at < 0) {
					return -1;
				}
			} else {
				char c = line.charAt(at);
				if ((c != lower && c != upper) ||
						!line.regionMatches(true, at, q, 0, length)) {
					continue;
				}
			}
			if (!query.wholeWord() || isWhole(line, at, at + length)) {
				return at;
			}
		}
		return -1;
	}

	// From the start after the end; -1 when the text has none, UNKNOWN when
	// none is found within lines lines.
	public static int next(Text text, Query query, int offset, int lines) {
		if (query.isEmpty()) {
			return -1;
		}
		int count = text.lineCount();
		int first = text.lineOf(offset);
		int column = offset - text.lineStart(first);
		for (int i = 0; i <= count; ++i) {
			if (i == lines) {
				return UNKNOWN;
			}
			int n = (first + i) % count;
			int at = inLine(text.line(n), i == 0 ? column : 0, query);
			if (at > -1 && (i < count || at < column)) {
				return text.lineStart(n) + at;
			}
		}
		return -1;
	}

	// From the end before the start; -1 when the text has none.
	public static int previous(Text text, Query query, int offset) {
		if (query.isEmpty()) {
			return -1;
		}
		int count = text.lineCount();
		int first = text.lineOf(offset);
		int column = offset - text.lineStart(first);
		int length = query.text().length();
		for (int i = 0; i <= count; ++i) {
			int n = ((first - i) % count + count) % count;
			String line = text.line(n);
			int last = -1;
			for (int at = inLine(line, 0, query);
					at > -1 && (i > 0 || at < column);
					at = inLine(line, at + length, query)) {
				if (i < count || at >= column) {
					last = at;
				}
			}
			if (last > -1) {
				return text.lineStart(n) + last;
			}
		}
		return -1;
	}

	// No letter, digit or _ right before or after it.
	private static boolean isWhole(String line, int from, int to) {
		return (from == 0 || !isWordChar(line.charAt(from - 1))) &&
				(to == line.length() || !isWordChar(line.charAt(to)));
	}

	private static boolean isWordChar(char c) {
		return c == '_' || Character.isLetterOrDigit(c);
	}
}
