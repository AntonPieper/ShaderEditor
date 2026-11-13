package de.markusfisch.android.shadereditor.widget;

/**
 * How many lines without a recording one frame of a CodeView records.
 * It draws the others directly, which costs less than recording them,
 * and records them in the next frames. A third of the
 * lines on screen is more than an ordinary scroll brings into view in
 * one frame, and records a whole new screen in at most three frames.
 */
final class RecordBudget {
	private RecordBudget() {
	}

	/** Return how many lines one frame that shows shown lines records. */
	static int lines(int shown) {
		return (shown + 2) / 3;
	}
}
