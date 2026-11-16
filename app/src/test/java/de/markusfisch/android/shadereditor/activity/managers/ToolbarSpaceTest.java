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

	@Test
	public void theToolbarStepsAsideOnlyForTooLittleCode() {
		// Landscape, docked keyboard: 8 px of code where a line is 37.
		assertTrue(ToolbarSpace.stepsAside(true, 8, 37));
		// Three whole lines are enough: the toolbar stays.
		assertTrue(ToolbarSpace.stepsAside(true, 110, 37));
		assertFalse(ToolbarSpace.stepsAside(true, 111, 37));
		// A floating keyboard leaves the room: the toolbar stays.
		assertFalse(ToolbarSpace.stepsAside(true, 700, 37));
		// No on-screen keyboard, or no code shown: never.
		assertFalse(ToolbarSpace.stepsAside(false, 8, 37));
		// Less room than nothing, as in a split screen: aside.
		assertTrue(ToolbarSpace.stepsAside(true, -40, 37));
	}

	@Test
	public void findPutsReplaceBesideItOnlyForTooLittleCode() {
		// Landscape, docked keyboard: Find and Replace one under the
		// other leave no code at all.
		assertTrue(ToolbarSpace.findInOneRow(true, -30, 37));
		assertTrue(ToolbarSpace.findInOneRow(true, 110, 37));
		// Portrait: plenty of code under both rows.
		assertFalse(ToolbarSpace.findInOneRow(true, 111, 37));
		assertFalse(ToolbarSpace.findInOneRow(true, 900, 37));
		// Without the row that replaces there is nothing to move.
		assertFalse(ToolbarSpace.findInOneRow(false, -30, 37));
	}
}
