package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.OpenProject;

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
	 * @param runs     what is known of whether each file can run, by its
	 *                 path; a file that is not in it is not known yet
	 * @param writable whether the folder of the rows can be changed
	 */
	public static List<Row> of(List<Row> rows, String running,
			Map<String, OpenProject.Runs> runs, boolean writable) {
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
					// Any file that may be text; a picture's name hints
					// that it is not one.
					OpenProject.Runs known = runs.get(row.key());
					if (row.kind() != Row.Kind.IMAGE && offersRun(
							row.key().equals(running), known != null
									? known
									: OpenProject.Runs.UNKNOWN)) {
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

	/**
	 * Whether Run this file is offered for a file, in its row's menu and
	 * in the toolbar's alike: not while it runs, nor when its text is
	 * known to define no main; while that is not known, it is offered,
	 * and running it tells.
	 */
	public static boolean offersRun(boolean running, OpenProject.Runs runs) {
		return !running && runs != OpenProject.Runs.NO;
	}
}
