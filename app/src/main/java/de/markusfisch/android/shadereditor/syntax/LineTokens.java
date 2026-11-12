package de.markusfisch.android.shadereditor.syntax;

import java.util.Arrays;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). The tokens of one line, with
 * offsets into that line. A buffer that is filled again for each line.
 */
public final class LineTokens {
	private static final Style[] STYLES = Style.values();

	public int[] starts = new int[32];
	public int[] ends = new int[32];
	public int[] kinds = new int[32];
	public int size;

	void ensure(int capacity) {
		if (capacity > starts.length) {
			starts = Arrays.copyOf(starts, capacity);
			ends = Arrays.copyOf(ends, capacity);
			kinds = Arrays.copyOf(kinds, capacity);
		}
	}

	public Style style(int index) {
		return STYLES[kinds[index] & 0xff];
	}
}
