package de.markusfisch.android.shadereditor.activity.managers;

/**
 * How the editor's toolbar shares the room: across, between its title
 * and the quality chip, where the title comes first; down, with the code
 * while a keyboard is open, where the code comes first. Find, in the
 * toolbar's place, gives the code the same room.
 */
final class ToolbarSpace {
	/** Fewer lines of code than this make the toolbar step aside. */
	static final int MIN_LINES = 3;

	private ToolbarSpace() {
	}

	/**
	 * Return whether the toolbar steps aside for the code: only while an
	 * on-screen keyboard is open over code that is shown, and the code
	 * would show fewer than {@link #MIN_LINES} lines with the toolbar in
	 * its place.
	 *
	 * @param typing     an on-screen keyboard is open over code shown
	 * @param room       height of the code with the toolbar in its place
	 * @param lineHeight height of one line of code
	 */
	static boolean stepsAside(boolean typing, int room, int lineHeight) {
		return typing && isShort(room, lineHeight);
	}

	/**
	 * Return whether Find puts its row that replaces beside the find row
	 * instead of under it: only while it replaces, and the code would
	 * show fewer than {@link #MIN_LINES} lines with the rows one under
	 * the other.
	 *
	 * @param replacing  the row that replaces shows
	 * @param room       height of the code with the rows one under the
	 *                   other
	 * @param lineHeight height of one line of code
	 */
	static boolean findInOneRow(boolean replacing, int room,
			int lineHeight) {
		return replacing && isShort(room, lineHeight);
	}

	private static boolean isShort(int room, int lineHeight) {
		return room < MIN_LINES * lineHeight;
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
