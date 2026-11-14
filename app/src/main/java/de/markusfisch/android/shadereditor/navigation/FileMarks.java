package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Marks the rows of a folder with what is known of their files. */
public final class FileMarks {
	private FileMarks() {
	}

	/**
	 * The rows, a file marked unsaved when it has unsaved text and a
	 * folder when a file under it has.
	 *
	 * @param unsaved the paths of the files with unsaved text, from the
	 *                root of the folder opened, as the rows' keys are
	 */
	public static List<Row> unsaved(List<Row> rows, Set<String> unsaved) {
		List<Row> marked = new ArrayList<>(rows.size());
		for (Row row : rows) {
			marked.add(holdsUnsaved(row, unsaved)
					? withMark(row, Row.Mark.UNSAVED)
					: row);
		}
		return marked;
	}

	private static boolean holdsUnsaved(Row row, Set<String> unsaved) {
		if (row.kind() != Row.Kind.FOLDER) {
			return row.kind() != Row.Kind.MORE && unsaved.contains(row.key());
		}
		String prefix = row.key() + "/";
		for (String path : unsaved) {
			if (path.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static Row withMark(Row row, Row.Mark mark) {
		Set<Row.Mark> marks = EnumSet.of(mark);
		marks.addAll(row.marks());
		return new Row(row.key(), row.kind(), row.picture(), row.name(),
				row.detail(), row.changed(), row.enters(), marks,
				row.actions());
	}
}
