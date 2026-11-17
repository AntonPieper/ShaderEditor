package de.markusfisch.android.shadereditor.widget;

// Where the lines of a CodeView are, from the top of line 0: every line has the
// same height, and at most one row of another height may follow a line and move
// the lines after it down. So the top of a line and the line at a place stay
// one multiplication and one division.
final class LineGeometry {
	private int lineHeight = 1;
	private int rowLine = -1;
	private int rowHeight;

	void setLineHeight(int lineHeight) {
		this.lineHeight = Math.max(1, lineHeight);
	}

	// None for line -1.
	void setRow(int line, int height) {
		rowLine = line;
		rowHeight = line < 0 ? 0 : height;
	}

	// -1 for none.
	int rowLine() {
		return rowLine;
	}

	int rowHeight() {
		return rowHeight;
	}

	float top(int n) {
		return n * (float) lineHeight + offset(n, rowLine, rowHeight);
	}

	float rowTop() {
		return (rowLine + 1) * (float) lineHeight;
	}

	// The row's line on the row; the first line above all lines, the last below
	// them.
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

	float height(int lineCount) {
		return lineCount * (float) lineHeight +
				(rowLine >= 0 && rowLine < lineCount ? rowHeight : 0);
	}

	// Keeps line anchor where it was on screen at scrollY after the row changed
	// from one of oldHeight under oldLine to the one now.
	int keep(int scrollY, int anchor, int oldLine, int oldHeight) {
		return scrollY + offset(anchor, rowLine, rowHeight) -
				offset(anchor, oldLine, oldHeight);
	}

	// Nearest scrollY that shows line n, with its row, between visibleTop and
	// visibleBottom from the view's top; the line itself wins when both do not
	// fit.
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

	// above and below: padding and what covers the view's bottom. The last
	// line, or the row under it, rests at the bottom; 0 when everything fits.
	int maxScroll(int lineCount, float above, float below, int viewHeight) {
		return Math.max(0, (int) Math.ceil(above + height(lineCount) +
				below - viewHeight));
	}

	private static int offset(int n, int rowLine, int rowHeight) {
		return rowLine >= 0 && n > rowLine ? rowHeight : 0;
	}
}
