package de.markusfisch.android.shadereditor.syntax;

import java.util.Arrays;

/**
 * The tokens of one line, as {@link Syntax#lexLine} leaves them, with
 * UTF-16 offsets into that line. One instance is meant to be filled again
 * for every line; it grows to the line with the most tokens.
 */
public final class LineTokens {
	private static final Style[] STYLES = Style.values();

	int[] starts = new int[16];
	int[] ends = new int[16];
	int[] kinds = new int[16];
	private int size;

	public int size() {
		return size;
	}

	public int start(int index) {
		return starts[index];
	}

	public int end(int index) {
		return ends[index];
	}

	public Style style(int index) {
		return STYLES[kinds[index] & 0xff];
	}

	/** True if the token belongs to a preprocessor directive. */
	public boolean inDirective(int index) {
		return (kinds[index] & Tokens.IN_DIRECTIVE) != 0;
	}

	/** Index of the token that contains offset, or -1 if there is none. */
	public int indexAt(int offset) {
		int low = 0;
		int high = size - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (starts[mid] <= offset) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return high >= 0 && offset < ends[high] ? high : -1;
	}

	void clear() {
		size = 0;
	}

	void add(int start, int end, Style style) {
		if (size == starts.length) {
			int capacity = size * 2;
			starts = Arrays.copyOf(starts, capacity);
			ends = Arrays.copyOf(ends, capacity);
			kinds = Arrays.copyOf(kinds, capacity);
		}
		starts[size] = start;
		ends[size] = end;
		kinds[size] = style.ordinal();
		++size;
	}

	void set(int[] starts, int[] ends, int[] kinds, int size) {
		this.starts = starts;
		this.ends = ends;
		this.kinds = kinds;
		this.size = size;
	}
}
