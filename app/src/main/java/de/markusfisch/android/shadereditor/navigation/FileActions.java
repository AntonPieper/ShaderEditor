package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What the menus of the rows of a folder offer. */
public final class FileActions {
	private FileActions() {
	}

	/**
	 * The rows with their menus: a shader file that is not the one
	 * that runs can be run; a file can be renamed, duplicated and
	 * deleted, a folder renamed, deleted and created in, but only where
	 * the folder they are in can be changed.
	 *
	 * @param running  the path of the file that runs, as the rows' keys
	 *                 are, or null
	 * @param writable whether the folder of the rows can be changed
	 */
	public static List<Row> of(List<Row> rows, String running,
			boolean writable) {
		List<Row> offered = new ArrayList<>(rows.size());
		for (Row row : rows) {
			Set<Row.Action> actions = EnumSet.noneOf(Row.Action.class);
			switch (row.kind()) {
				case FOLDER -> {
					if (writable) {
						actions.add(Row.Action.RENAME);
						actions.add(Row.Action.NEW_HERE);
						actions.add(Row.Action.DELETE);
					}
				}
				case SHADER, TEXT, IMAGE -> {
					if (!row.key().equals(running) && row.name()
							.toLowerCase(Locale.ROOT).endsWith(".glsl")) {
						actions.add(Row.Action.RUN);
					}
					if (writable) {
						actions.add(Row.Action.RENAME);
						actions.add(Row.Action.DUPLICATE);
						actions.add(Row.Action.DELETE);
					}
				}
				default -> {
				}
			}
			offered.add(new Row(row.key(), row.kind(), row.picture(),
					row.name(), row.detail(), row.changed(), row.enters(),
					row.marks(), actions));
		}
		return offered;
	}
}
