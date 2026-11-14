package de.markusfisch.android.shadereditor.widget;

/**
 * Where the lines of a CodeView are, measured from the top of line 0:
 * every line has the same height, and at most one row of another
 * height may follow one line and move the lines after it down by its
 * height. So the top of a line and the line at a place stay one
 * multiplication and one division. Every place of a line in the view
 * comes from here.
 */
final class LineGeometry {
	private int lineHeight = 1;
	private int rowLine = -1;
	private int rowHeight;

	void setLineHeight(int lineHeight) {
		this.lineHeight = Math.max(1, lineHeight);
	}

	int lineHeight() {
		return lineHeight;
	}

	/** Put a row of height under line, or none for line -1. */
	void setRow(int line, int height) {
		rowLine = line;
		rowHeight = line < 0 ? 0 : height;
	}

	/** Return the line the row follows, or -1 for none. */
	int rowLine() {
		return rowLine;
	}

	int rowHeight() {
		return rowHeight;
	}

	/** Return the top of line n. */
	float top(int n) {
		return n * (float) lineHeight + offset(n, rowLine, rowHeight);
	}

	/** Return the top of the row, under its line. */
	float rowTop() {
		return (rowLine + 1) * (float) lineHeight;
	}

	/**
	 * Return the line at y, the row's line on the row: the first line
	 * above all lines, the last below them.
	 */
	int lineAt(float y, int lineCount) {
		if (rowLine >= 0) {
			float rowTop = rowTop();
			if (y >= rowTop + rowHeight) {
				y -= rowHeight;
			} else if (y >= rowTop) {
				return Math.min(rowLine, lineCount - 1);
			}
		}
		int line = (int) Math.floor(y / lineHeight);
		return Math.max(0, Math.min(lineCount - 1, line));
	}

	/** Return the height of lineCount lines, with the row. */
	float height(int lineCount) {
		return lineCount * (float) lineHeight +
				(rowLine >= 0 && rowLine < lineCount ? rowHeight : 0);
	}

	/**
	 * Return the scroll position that keeps line anchor where it was on
	 * screen at scrollY, after the row changed from one of oldHeight
	 * under oldLine to the one now.
	 */
	int keep(int scrollY, int anchor, int oldLine, int oldHeight) {
		return scrollY + offset(anchor, rowLine, rowHeight) -
				offset(anchor, oldLine, oldHeight);
	}

	/**
	 * Return the scroll position nearest scrollY that shows line n with
	 * its row, if the row is under it, between visibleTop and
	 * visibleBottom from the view's top, measured like scrollY from the
	 * top of line 0; the line itself wins when both do not fit.
	 */
	int reveal(int scrollY, int n, float visibleTop, float visibleBottom) {
		float top = top(n);
		float bottom = top + lineHeight + (n == rowLine ? rowHeight : 0);
		if (bottom > scrollY + visibleBottom) {
			scrollY = (int) Math.ceil(bottom - visibleBottom);
		}
		if (top < scrollY + visibleTop) {
			scrollY = (int) Math.floor(top - visibleTop);
		}
		return scrollY;
	}

	/**
	 * Return the largest scroll position for lineCount lines in a view
	 * of viewHeight, with above pixels before line 0 and below pixels
	 * after the last line (padding and what covers the view's bottom):
	 * the last line, or the row under it, rests at the bottom; 0 when
	 * everything fits.
	 */
	int maxScroll(int lineCount, float above, float below, int viewHeight) {
		return Math.max(0, (int) Math.ceil(above + height(lineCount) +
				below - viewHeight));
	}

	private static int offset(int n, int rowLine, int rowHeight) {
		return rowLine >= 0 && n > rowLine ? rowHeight : 0;
	}
}
