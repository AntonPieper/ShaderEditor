package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One line of the drawer's list: a section heading, or a row as it is
 * seen with what the editor has open and what runs.
 */
public record Item(String heading, Row row, boolean selected,
		Set<Row.Mark> marks, Tap tap) {
	/** A file as the drawer finds it: the level listing it and its key. */
	public record Place(Level level, String key) {
	}

	/**
	 * The lines of a listing of the level: the file in the editor, or
	 * the folder it is in, is selected, the running file is marked,
	 * and a tap on it or a file it uses only opens.
	 *
	 * @param open    the file in the editor, or null
	 * @param running the file that runs, or null
	 * @param used    the files the running file uses, like its passes
	 *                and includes; a tap on them only opens too
	 */
	public static List<Item> of(Listing listing, Level level, Place open,
			Place running, Set<Place> used) {
		List<Item> items = new ArrayList<>();
		for (Listing.Section section : listing.sections()) {
			if (section.heading() != null) {
				items.add(new Item(section.heading(), null, false,
						EnumSet.noneOf(Row.Mark.class), Tap.NOTHING));
			}
			for (Row row : section.rows()) {
				Place place = new Place(level, row.key());
				boolean runs = place.equals(running);
				Set<Row.Mark> marks = row.marks().isEmpty()
						? EnumSet.noneOf(Row.Mark.class)
						: EnumSet.copyOf(row.marks());
				if (runs) {
					marks.add(Row.Mark.RUNNING);
				}
				items.add(new Item(null, row, place.equals(open) ||
						holds(row, open), marks,
						Tap.of(row.kind(), runs || used.contains(place))));
			}
		}
		return items;
	}

	private static boolean holds(Row row, Place place) {
		Level folder = row.enters();
		return folder != null && place != null &&
				(place.level().equals(folder) || place.level().isBelow(folder));
	}
}
