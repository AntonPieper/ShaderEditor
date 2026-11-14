package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * A folder or a single file opened before, as the top level lists it
 * under Recent: the key its source knows it by, its name and where it
 * is, when it was opened last in milliseconds since the epoch, and
 * whether it has unsaved text.
 */
public record Recent(String key, boolean file, String name,
		String location, long opened, boolean unsaved) {
	/**
	 * The rows of the folders and files opened before, the one opened
	 * last first. A single file says so after where it is.
	 *
	 * @param singleFile what a single file is called
	 */
	public static List<Row> rows(List<Recent> recent, String singleFile) {
		List<Recent> sorted = new ArrayList<>(recent);
		Collections.sort(sorted, (a, b) -> a.opened != b.opened
				? Long.compare(b.opened, a.opened)
				: a.key.compareTo(b.key));
		List<Row> rows = new ArrayList<>();
		for (Recent opened : sorted) {
			rows.add(new Row(opened.key,
					Row.Kind.OTHER,
					Row.Picture.of(opened.file
							? Row.Picture.Icon.SHADER
							: Row.Picture.Icon.FOLDER),
					opened.name,
					opened.detail(singleFile),
					opened.opened,
					null,
					opened.unsaved
							? EnumSet.of(Row.Mark.UNSAVED)
							: EnumSet.noneOf(Row.Mark.class),
					EnumSet.noneOf(Row.Action.class)));
		}
		return rows;
	}

	private String detail(String singleFile) {
		boolean placed = location != null && !location.isEmpty();
		if (!file) {
			return placed ? location : null;
		}
		return placed ? location + " \u00b7 " + singleFile : singleFile;
	}
}
