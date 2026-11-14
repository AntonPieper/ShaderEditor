package de.markusfisch.android.shadereditor.activity.managers;

/**
 * How the editor's toolbar shares the room between its title and the
 * quality chip: the title comes first.
 */
final class ToolbarSpace {
	private ToolbarSpace() {
	}

	/**
	 * Return whether the frame rate may stand in the quality chip: only
	 * when the title then keeps the width it needs, or titleMin when it
	 * needs more.
	 *
	 * @param room         width of the title and the chip together
	 * @param chipWithRate width of the chip with the frame rate in it
	 * @param titleNeeds   width of the whole title with its unsaved mark
	 * @param titleMin     the width the title always gets first
	 */
	static boolean frameRateFits(int room, int chipWithRate, int titleNeeds,
			int titleMin) {
		return room - chipWithRate >= Math.min(titleNeeds, titleMin);
	}
}
