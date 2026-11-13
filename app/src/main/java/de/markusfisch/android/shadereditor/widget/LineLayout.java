package de.markusfisch.android.shadereditor.widget;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * One line of text, laid out with its colours: where each offset of it
 * is, which offset is nearest a place, how wide it is, and its drawing.
 * Offsets count UTF-16 units into the line; x is measured from the
 * start of the line and y from its top.
 */
interface LineLayout extends TouchSelection.Line {
	/**
	 * Lines longer than this are drawn without colours, one column per
	 * character, and only where they are visible.
	 */
	int LONG = 10_000;

	float xOf(int offset);

	/** Return the offset between two characters nearest x. */
	int offsetAt(float x);

	float width();

	/**
	 * Return whether draw draws the whole line whatever left and right
	 * are, so a drawing of it may be kept and replayed.
	 */
	boolean isWhole();

	/** Draw the text, at least the part from left to right. */
	void draw(Canvas canvas, float left, float right);

	/** Fill the shape of the range [from, to) of the line with paint. */
	void drawSelection(Canvas canvas, int from, int to, Paint paint);
}
