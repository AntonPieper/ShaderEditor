package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * A folder or a single file opened before, as the top level lists it
 * under Recent, a level to go into: the key its source knows it by, its name and where it
 * is, when it was opened last in milliseconds since the epoch,
 * whether it has unsaved text, and whether it was found to be lost
 * when it was last gone into.
 */
public record Recent(String key, boolean file, String name,
		String location, long opened, boolean unsaved, boolean lost) {
	private static final String DOT = " \u00b7 ";

	/**
	 * The rows of the folders and files opened before, the one opened
	 * last first. A single file says so after where it is, and one
	 * that was lost says so first. Each can be removed from the list.
	 *
	 * @param singleFile what a single file is called
	 * @param lost       how to say that it cannot be opened
	 */
	public static List<Row> rows(List<Recent> recent, String singleFile,
			String lost) {
		List<Recent> sorted = new ArrayList<>(recent);
		Collections.sort(sorted, (a, b) -> a.opened != b.opened
				? Long.compare(b.opened, a.opened)
				: a.key.compareTo(b.key));
		List<Row> rows = new ArrayList<>();
		for (Recent opened : sorted) {
			rows.add(new Row(opened.key,
					Row.Kind.FOLDER,
					Row.Picture.of(opened.file
							? Row.Picture.Icon.SHADER
							: Row.Picture.Icon.FOLDER),
					opened.name,
					opened.detail(singleFile, lost),
					opened.opened,
					Level.root(opened.key),
					opened.unsaved
							? EnumSet.of(Row.Mark.UNSAVED)
							: EnumSet.noneOf(Row.Mark.class),
					EnumSet.of(Row.Action.REMOVE)));
		}
		return rows;
	}

	private String detail(String singleFile, String cannot) {
		String place = location != null && !location.isEmpty()
				? location
				: null;
		if (file) {
			place = place != null ? place + DOT + singleFile : singleFile;
		}
		if (!lost) {
			return place;
		}
		return place != null ? cannot + DOT + place : cannot;
	}
}
