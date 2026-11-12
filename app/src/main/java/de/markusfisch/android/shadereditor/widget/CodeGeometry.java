package de.markusfisch.android.shadereditor.widget;

/**
 * The arithmetic of where things are in a CodeView: every line has the
 * same height, so line n is at n times that height and the line at a
 * place is a division.
 */
final class CodeGeometry {
	private CodeGeometry() {
	}

	/**
	 * Return the line at y, where line 0 starts at top: the first line
	 * above all lines, the last below them.
	 */
	static int lineAt(float y, float top, int lineHeight, int lineCount) {
		int line = (int) Math.floor((y - top) / lineHeight);
		return Math.max(0, Math.min(lineCount - 1, line));
	}

	/** Return the number of decimal digits of number, at least 1. */
	static int digits(int number) {
		int digits = 1;
		while (number >= 10) {
			number /= 10;
			++digits;
		}
		return digits;
	}

	/**
	 * Return the width of a gutter for the numbers of lineCount lines
	 * and the space between them and the text.
	 */
	static float gutterWidth(int lineCount, float digitWidth,
			float spacing) {
		return digits(lineCount) * digitWidth + spacing;
	}
}
