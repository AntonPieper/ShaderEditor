package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ToolbarSpaceTest {
	@Test
	public void theFrameRateYieldsBeforeTheTitleIsCut() {
		// A short name keeps its room with the rate in the chip.
		assertTrue(ToolbarSpace.frameRateFits(200, 110, 80, 108));
		// A name of the minimum would be cut: the rate goes.
		assertFalse(ToolbarSpace.frameRateFits(200, 110, 107, 108));
		// A longer name may be cut, but never below the minimum.
		assertTrue(ToolbarSpace.frameRateFits(220, 110, 300, 108));
		assertFalse(ToolbarSpace.frameRateFits(210, 110, 300, 108));
	}
}
