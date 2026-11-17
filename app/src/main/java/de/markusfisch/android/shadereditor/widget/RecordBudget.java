package de.markusfisch.android.shadereditor.widget;

// A frame records at most a third of the lines on screen and draws the rest
// directly, which costs less than recording them. A third is more than an
// ordinary scroll brings into view in one frame, and records a whole new screen
// in at most three frames.
final class RecordBudget {
	private RecordBudget() {
	}

	static int lines(int shown) {
		return (shown + 2) / 3;
	}
}
