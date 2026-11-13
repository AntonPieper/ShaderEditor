package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordBudgetTest {
	@Test
	public void recordsAWholeNewScreenInAtMostThreeFrames() {
		for (int shown = 1; shown <= 500; ++shown) {
			int frames = 0;
			for (int left = shown; left > 0; ) {
				int budget = RecordBudget.lines(shown);
				assertTrue(budget > 0);
				left -= Math.min(left, budget);
				++frames;
			}
			assertTrue(shown + " lines took " + frames + " frames",
					frames <= 3);
		}
	}

	@Test
	public void recordsAThirdOfTheScreenPerFrame() {
		assertEquals(25, RecordBudget.lines(75));
		assertEquals(14, RecordBudget.lines(40));
		assertEquals(1, RecordBudget.lines(1));
	}
}
