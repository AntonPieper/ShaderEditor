package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

// The lines of a document the last build did not compile, set for the very
// text it read. They follow an edit that cannot change what is compiled; a
// line typed inside them is one of them. After any other edit the lines above
// it stay and the ones below are stale until answer replaces them. An edit
// that may change which programs there are drops them all.
public final class DimmedLines {
	private static final int LIMIT = 4096;
	private static final Pattern PROGRAMS = Pattern.compile(
			"#\\s*(include|pragma)\\b");

	private final Document document;
	private final Runnable stale;
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onChange(Document.Change change, Text before) {
			move(change, before);
		}
	};
	// Sorted ranges of lines, from inclusive, to exclusive, apart.
	private int[] from = new int[0];
	private int[] to = new int[0];
	private boolean live;
	// The first line that may have changed since they were set, or -1.
	private int staleFrom = -1;

	public DimmedLines(Document document, Runnable stale) {
		this.document = document;
		this.stale = stale;
		document.addListener(listener);
	}

	public void close() {
		document.removeListener(listener);
	}

	// The 0-based lines in order; false when the document holds another text.
	public boolean set(Text text, List<Integer> lines) {
		if (text != document.text()) {
			return false;
		}
		from = to = new int[0];
		add(text, lines, 0);
		live = true;
		staleFrom = -1;
		return true;
	}

	// Replaces the stale lines with what a run for text found; false when the
	// document holds another text or none is stale.
	public boolean answer(Text text, List<Integer> lines) {
		if (text != document.text() || staleFrom < 0) {
			return false;
		}
		int start = staleFrom;
		clearStale();
		add(text, lines, start);
		staleFrom = -1;
		return true;
	}

	public void clear() {
		from = to = new int[0];
		live = false;
		staleFrom = -1;
	}

	// Drops the stale lines and keeps the ones above them.
	public void clearStale() {
		if (staleFrom >= 0) {
			move(staleFrom, Integer.MAX_VALUE - 1, 0, false);
		}
	}

	// Appends the lines from start on to the ranges, which end before it.
	private void add(Text text, List<Integer> lines, int start) {
		int[] f = Arrays.copyOf(from, from.length + lines.size());
		int[] t = Arrays.copyOf(to, to.length + lines.size());
		int count = from.length;
		for (int line : lines) {
			if (line < start) {
				continue;
			} else if (line >= text.lineCount()) {
				break;
			} else if (count > 0 && t[count - 1] == line) {
				++t[count - 1];
			} else {
				f[count] = line;
				t[count++] = line + 1;
			}
		}
		from = Arrays.copyOf(f, count);
		to = Arrays.copyOf(t, count);
	}

	public boolean isOff(int line) {
		int i = Arrays.binarySearch(from, line);
		i = i >= 0 ? i : -i - 2;
		return i >= 0 && line < to[i];
	}

	private void move(Document.Change change, Text before) {
		if (!live) {
			return;
		}
		Text after = document.text();
		int first = before.lineOf(change.start());
		int last = first + change.removed().lineCount() - 1;
		int delta = change.inserted().lineCount() -
				change.removed().lineCount();
		int risk = change.removed().length() + change.inserted().length() >
				LIMIT ? 2 : Math.max(risk(before, first, last),
				risk(after, first, last + delta));
		if (risk == 2) {
			clear();
			return;
		} else if (risk == 0 && (first == 0 ||
				!before.line(first - 1).endsWith("\\")) &&
				move(first, last, delta, true)) {
			if (staleFrom < 0) {
				return;
			}
			staleFrom = staleFrom > last ? staleFrom + delta
					: Math.min(staleFrom, first);
		} else {
			move(first, last, delta, false);
			staleFrom = staleFrom >= 0 && staleFrom < first
					? staleFrom
					: first;
		}
		stale.run();
	}

	// Moves the ranges for an edit of the lines first to last that left delta
	// lines more; whole keeps a range around them, or returns false.
	private boolean move(int first, int last, int delta, boolean whole) {
		int[] f = new int[from.length * 2];
		int[] t = new int[from.length * 2];
		int n = 0;
		for (int i = 0; i < from.length; ++i) {
			if (whole && from[i] <= first && to[i] > last) {
				f[n] = from[i];
				t[n++] = to[i] + delta;
				continue;
			} else if (whole && to[i] > first && from[i] <= last) {
				return false;
			}
			if (from[i] < first) {
				f[n] = from[i];
				t[n++] = Math.min(to[i], first);
			}
			if (to[i] > last + 1) {
				f[n] = Math.max(from[i], last + 1) + delta;
				t[n++] = to[i] + delta;
			}
		}
		from = Arrays.copyOf(f, n);
		to = Arrays.copyOf(t, n);
		return true;
	}

	// 0 when the lines can change nothing compiled, 1 when they may, 2 when
	// they may change which programs there are or are too long to look at.
	private static int risk(Text text, int from, int to) {
		int risk = 0;
		int length = 0;
		for (int n = from; n <= to; ++n) {
			String line = text.line(n);
			length += line.length();
			if (length > LIMIT || PROGRAMS.matcher(line).find()) {
				return 2;
			} else if (line.indexOf('#') >= 0 || line.indexOf('\\') >= 0 ||
					line.contains("/*") || line.contains("*/")) {
				risk = 1;
			}
		}
		return risk;
	}
}
