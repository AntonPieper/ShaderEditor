package de.markusfisch.android.shadereditor.widget;

/**
 * The arithmetic of the gutter of a CodeView; where lines are is
 * LineGeometry's.
 */
final class CodeGeometry {
	static final int GUTTER_BLOCK = 32;

	private CodeGeometry() {
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
	 * Return the first of the lines whose numbers are recorded together
	 * with that of line: those of a block of GUTTER_BLOCK lines.
	 */
	static int numbersFrom(int line) {
		return line - line % GUTTER_BLOCK;
	}

	/**
	 * Return the last of the lines whose numbers are recorded together
	 * with that of line, at most the last of lineCount lines.
	 */
	static int numbersTo(int line, int lineCount) {
		return Math.min(lineCount - 1, numbersFrom(line) + GUTTER_BLOCK - 1);
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
