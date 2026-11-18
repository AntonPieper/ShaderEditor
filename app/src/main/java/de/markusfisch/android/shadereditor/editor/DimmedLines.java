package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

// The lines of a document that the last build did not compile. They are set
// for the very text a build read, and follow their lines through an edit that
// cannot change what is compiled: one of at most LIMIT characters, of lines
// that hold no "#", backslash or comment mark before or after it, behind a
// line that ends in no backslash. A line typed inside them is one of them.
// After any other edit the lines above it stay, as an edit changes nothing
// above itself, and the ones below stay too but are stale: stale is told to
// have the directives of the new text run, and answer puts in what that finds.
// An edit of an #include or #pragma line, or a larger one, may change which
// programs there are: it drops them all until the next build.
public final class DimmedLines {
	public interface Stale {
		void onStale();
	}

	private static final int LIMIT = 4096;
	private static final Pattern PROGRAMS = Pattern.compile(
			"#\\s*(include|pragma)\\b");

	private final Document document;
	private final Stale stale;
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onChange(Document.Change change, Text before) {
			move(change, before);
		}
	};
	// Sorted ranges of lines, from inclusive, to exclusive, apart.
	private int[] from = new int[0];
	private int[] to = new int[0];
	// Whether they are of a build, and not dropped since.
	private boolean live;
	// The first line whose state may have changed since they were set, or -1.
	private int staleFrom = -1;

	public DimmedLines(Document document, Stale stale) {
		this.document = document;
		this.stale = stale;
		document.addListener(listener);
	}

	public void close() {
		document.removeListener(listener);
	}

	// The 0-based lines in order, for the very text the document holds; false
	// and nothing changed when it holds another, as after an edit since.
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

	// What the directives run again found for text: it takes the place of the
	// stale lines, and the ones above them stay as they are. False and
	// nothing changed when the document holds another text, or none is stale.
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

	// Puts the ones of lines, in order, from start on after the ranges, which
	// end before it.
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
			// Exact as before, but an answer asked for is for another text.
			staleFrom = staleFrom > last ? staleFrom + delta
					: Math.min(staleFrom, first);
		} else {
			// Not exact any more from the edited lines on, and they are not
			// shown as off till then.
			move(first, last, delta, false);
			staleFrom = staleFrom >= 0 && staleFrom < first
					? staleFrom
					: first;
		}
		stale.onStale();
	}

	// Moves the ranges for an edit of the lines from first to last that left
	// delta lines more. A range around them takes the lines in its place if
	// whole; else it loses them, or nothing moves and false is returned.
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
				// An edge inside the edited lines is not one any more.
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

	// 0 when the lines from to to of text can change nothing compiled; 1 when
	// they may hold or make a directive, a joined line or the edge of a
	// comment; 2 when they may change which programs there are, or are too
	// long to look at.
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
