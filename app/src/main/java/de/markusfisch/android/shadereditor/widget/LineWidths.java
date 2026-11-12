package de.markusfisch.android.shadereditor.widget;

import java.util.Arrays;

/**
 * The width of every line of a text that does not wrap, kept up to date
 * through edits so that only the lines an edit touched are measured again.
 * <p>
 * A line is a paragraph: the editor scrolls horizontally, so lines and
 * paragraphs are the same.
 */
final class LineWidths {
	/**
	 * Measures one line, like {@link android.text.Layout#getLineMax}.
	 */
	@FunctionalInterface
	interface Measure {
		float lineWidth(int line);
	}

	private static final float UNKNOWN = Float.NaN;

	private float[] widths = new float[0];
	private int count;

	/**
	 * Forgets every width; all {@code lineCount} lines are measured again.
	 */
	void reset(int lineCount) {
		count = Math.max(0, lineCount);
		if (widths.length < count) {
			widths = new float[grow(count)];
		}
		Arrays.fill(widths, 0, count, UNKNOWN);
	}

	int lineCount() {
		return count;
	}

	/**
	 * Records an edit: {@code removedBreaks + 1} lines starting at
	 * {@code firstLine} became {@code addedBreaks + 1} lines, and those
	 * are measured again. Lines after them keep their widths.
	 *
	 * @return false if the edit does not fit the known lines, which then
	 * need a {@link #reset}
	 */
	boolean replace(int firstLine, int removedBreaks, int addedBreaks) {
		int oldEnd = firstLine + removedBreaks + 1;
		if (firstLine < 0 || removedBreaks < 0 || addedBreaks < 0 ||
				oldEnd > count) {
			return false;
		}
		int newEnd = firstLine + addedBreaks + 1;
		int newCount = count - (oldEnd - firstLine) + (newEnd - firstLine);
		if (widths.length < newCount) {
			widths = Arrays.copyOf(widths, grow(newCount));
		}
		System.arraycopy(widths, oldEnd, widths, newEnd, count - oldEnd);
		Arrays.fill(widths, firstLine, newEnd, UNKNOWN);
		count = newCount;
		return true;
	}

	/**
	 * Marks the lines {@code [from, to]} to be measured again.
	 */
	void invalidate(int from, int to) {
		from = Math.max(0, from);
		to = Math.min(count - 1, to);
		if (from <= to) {
			Arrays.fill(widths, from, to + 1, UNKNOWN);
		}
	}

	/**
	 * Measures the lines whose width is unknown.
	 *
	 * @return the widest line, 0 without lines
	 */
	float max(Measure measure) {
		float max = 0;
		for (int i = 0; i < count; ++i) {
			float w = widths[i];
			if (Float.isNaN(w)) {
				w = measure.lineWidth(i);
				widths[i] = w;
			}
			if (w > max) {
				max = w;
			}
		}
		return max;
	}

	/**
	 * @return the number of line breaks in {@code [start, end)}
	 */
	static int breaks(CharSequence text, int start, int end) {
		int n = 0;
		for (int i = Math.max(0, start), e = Math.min(end, text.length());
				i < e; ++i) {
			if (text.charAt(i) == '\n') {
				++n;
			}
		}
		return n;
	}

	private static int grow(int needed) {
		return Math.max(16, needed + (needed >> 1));
	}
}
