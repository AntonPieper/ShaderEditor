package de.markusfisch.android.shadereditor.syntax;

import java.util.Arrays;

/**
 * The tokens of one text, in order and without overlap. Offsets are
 * UTF-16 offsets into exactly the text that was tokenized. Lines are
 * counted from 0; "\n", "\r\n" and "\r" each end one line.
 */
public final class Tokens {
	static final int IN_DIRECTIVE = 1 << 8;

	private static final Style[] STYLES = Style.values();

	private final int[] starts;
	private final int[] ends;
	private final int[] kinds;
	private final int[] lineStarts;

	Tokens(int[] starts, int[] ends, int[] kinds, int size, int[] lineStarts,
			int lines) {
		this.starts = Arrays.copyOf(starts, size);
		this.ends = Arrays.copyOf(ends, size);
		this.kinds = Arrays.copyOf(kinds, size);
		this.lineStarts = Arrays.copyOf(lineStarts, lines);
	}

	public int size() {
		return starts.length;
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
		return (kinds[index] & IN_DIRECTIVE) != 0;
	}

	/** Index of the token that contains offset, or -1 if there is none. */
	public int indexAt(int offset) {
		int i = floor(starts, offset);
		return i >= 0 && offset < ends[i] ? i : -1;
	}

	public int lineCount() {
		return lineStarts.length;
	}

	public int lineStart(int line) {
		return lineStarts[line];
	}

	public int lineOf(int offset) {
		return Math.max(0, floor(lineStarts, offset));
	}

	// Index of the last value not greater than key, or -1.
	private static int floor(int[] values, int key) {
		int low = 0;
		int high = values.length - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (values[mid] <= key) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return high;
	}
}
