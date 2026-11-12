package de.markusfisch.android.shadereditor.widget;

/**
 * The arithmetic of the line-number gutter in {@link LineNumberEditText}:
 * how wide it is, where each number goes and which lines to draw.
 */
final class LineNumberGutter {
	/**
	 * Maps a vertical position in layout coordinates to the line that
	 * contains it, like {@link android.text.Layout#getLineForVertical}.
	 */
	@FunctionalInterface
	interface LineForVertical {
		int lineForVertical(int y);
	}

	private LineNumberGutter() {
	}

	/**
	 * @return the number of decimal digits of {@code number}; 1 for
	 * any number below 10, including 0 and negative numbers
	 */
	static int digits(int number) {
		int digits = 1;
		while (number >= 10) {
			number /= 10;
			++digits;
		}
		return digits;
	}

	/**
	 * @return the number of lines of {@code text} without word wrap:
	 * one more than the number of line breaks
	 */
	static int paragraphCount(CharSequence text) {
		int count = 1;
		for (int i = 0, length = text.length(); i < length; ++i) {
			if (text.charAt(i) == '\n') {
				++count;
			}
		}
		return count;
	}

	/**
	 * @return the left padding of the text: the padding the view was given
	 * when line numbers are hidden, otherwise room for the widest number
	 */
	static int textPaddingLeft(
			boolean showLineNumbers,
			int lineCount,
			float digitWidth,
			float numberPadding,
			float numberSpacing,
			int paddingLeftWithoutNumbers) {
		if (!showLineNumbers) {
			return paddingLeftWithoutNumbers;
		}
		int gutter = (int) (digitWidth * digits(lineCount) + numberPadding);
		return (int) (gutter + numberSpacing);
	}

	/**
	 * @return the x position of {@code lineNumber}, right-aligned to the
	 * widest number of {@code lineCount} lines
	 */
	static float numberX(
			int lineNumber,
			int lineCount,
			float digitWidth,
			float numberPadding) {
		return digitWidth * (digits(lineCount) - digits(lineNumber)) +
				numberPadding;
	}

	/**
	 * @param top    top of the area to draw, in layout coordinates
	 * @param bottom bottom of the area to draw, in layout coordinates
	 * @return the first and the last line (inclusive) that intersect
	 * the area, packed by {@link #pack}; {@code first > last} if there
	 * is nothing to draw
	 */
	static long lineRange(
			int lineCount,
			int top,
			int bottom,
			LineForVertical layout) {
		if (lineCount < 1 || bottom < top) {
			return pack(0, -1);
		}
		int first = layout.lineForVertical(Math.max(0, top));
		int last = layout.lineForVertical(Math.max(0, bottom));
		return pack(
				Math.max(0, Math.min(first, lineCount - 1)),
				Math.max(0, Math.min(last, lineCount - 1)));
	}

	static long pack(int first, int last) {
		return ((long) first << 32) | (last & 0xffffffffL);
	}

	static int first(long range) {
		return (int) (range >> 32);
	}

	static int last(long range) {
		return (int) range;
	}

	/**
	 * @return whether the drawn area still contains the visible area,
	 * so that the numbers drawn last time are still the ones on screen
	 */
	static boolean covers(
			int drawnTop,
			int drawnBottom,
			int visibleTop,
			int visibleBottom) {
		return drawnTop <= visibleTop && visibleBottom <= drawnBottom;
	}
}
