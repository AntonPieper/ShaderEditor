package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;

/**
 * Where a query stands in a text: literally, with or without regard to
 * case, anywhere or only as a whole word. A query holds no line break,
 * so every match is inside one line. Matches do not overlap: after a
 * match the next one is looked for behind it. Everything here only
 * reads, and a text never changes, so it runs on any thread.
 */
public final class Search {
	/**
	 * The most matches {@link #all} keeps; a text with more is told as
	 * having more.
	 */
	public static final int MAX = 10_000;
	/** What {@link #next} returns when it stopped before it was done. */
	public static final int UNKNOWN = -2;

	// The lines all reads between two looks at whether it is cancelled.
	private static final int SLICE = 512;

	/** What to look for, and how. */
	public record Query(String text, boolean matchCase, boolean wholeWord) {
		public static final Query NONE = new Query("", false, false);

		/** Return whether the query matches nothing at all. */
		public boolean isEmpty() {
			return text.isEmpty() || text.indexOf('\n') > -1;
		}
	}

	/** Tells a search that its result is not wanted any more. */
	public interface Cancel {
		boolean cancelled();
	}

	/**
	 * The matches of a query in a text, in order: at most {@link #MAX}
	 * from the start of the text, and whether there are more.
	 */
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

		/** Return the text that was searched. */
		public Text text() {
			return text;
		}

		public Query query() {
			return query;
		}

		/** Return how many matches are kept. */
		public int count() {
			return starts.length;
		}

		/** Return whether the text has more matches than are kept. */
		public boolean more() {
			return more;
		}

		/** Return the offset match i starts at. */
		public int start(int i) {
			return starts[i];
		}

		/**
		 * Return the index of the match that starts at offset, or -1
		 * when none kept does.
		 */
		public int indexOf(int offset) {
			int i = Arrays.binarySearch(starts, offset);
			return i < 0 ? -1 : i;
		}

		/**
		 * Return the index of the first match kept that starts at or
		 * after offset, or count() when none does.
		 */
		public int after(int offset) {
			int i = Arrays.binarySearch(starts, offset);
			return i < 0 ? -i - 1 : i;
		}
	}

	private Search() {
	}

	/**
	 * Return every match of query in text, at most {@link #MAX}, or null
	 * when cancel told to stop before it was done.
	 */
	public static Matches all(Text text, Query query, Cancel cancel) {
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
				if (count == MAX) {
					return new Matches(text, query, starts, true);
				} else if (count == starts.length) {
					starts = Arrays.copyOf(starts, Math.min(MAX, count * 2));
				}
				starts[count++] = offset + at;
			}
			offset += line.length() + 1;
		}
		return new Matches(text, query, Arrays.copyOf(starts, count), false);
	}

	/**
	 * Return the column of the first match of query in line at or after
	 * column from, or -1 when there is none.
	 */
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

	/**
	 * Return the offset of the first match of query at or after offset,
	 * from the start of the text after its end; -1 when the text has
	 * none, {@link #UNKNOWN} when that is not found in lines lines.
	 */
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

	/**
	 * Return the offset of the last match of query that starts before
	 * offset, from the end of the text before its start; -1 when the
	 * text has none.
	 */
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

	// Whether [from, to) of line is a whole word: no letter, digit or _
	// right before or after it.
	private static boolean isWhole(String line, int from, int to) {
		return (from == 0 || !isWordChar(line.charAt(from - 1))) &&
				(to == line.length() || !isWordChar(line.charAt(to)));
	}

	private static boolean isWordChar(char c) {
		return c == '_' || Character.isLetterOrDigit(c);
	}
}
