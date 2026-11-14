package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** How the rows of a sortable level are ordered. Folders come first. */
public enum Order {
	CHANGED,
	BY_NAME;

	public List<Row> sort(List<Row> rows) {
		List<Row> sorted = new ArrayList<>(rows);
		Collections.sort(sorted, (a, b) -> compare(
				a.kind() == Row.Kind.FOLDER, a.changed(), a.name(), a.key(),
				b.kind() == Row.Kind.FOLDER, b.changed(), b.name(), b.key()));
		return sorted;
	}

	/**
	 * Return entries of one folder in the order their rows have, so
	 * that rows need to be made only for those shown.
	 */
	List<Reading.Entry> sortEntries(List<Reading.Entry> entries) {
		List<Reading.Entry> sorted = new ArrayList<>(entries);
		// In one folder a row's key is its name after the same prefix.
		Collections.sort(sorted, (a, b) -> compare(
				a.folder(), Math.max(0, a.modified()), a.name(), a.name(),
				b.folder(), Math.max(0, b.modified()), b.name(), b.name()));
		return sorted;
	}

	private int compare(boolean aFolder, long aChanged, String aName,
			String aKey, boolean bFolder, long bChanged, String bName,
			String bKey) {
		if (aFolder != bFolder) {
			return aFolder ? -1 : 1;
		}
		int order = this == CHANGED
				? Long.compare(bChanged, aChanged)
				: String.CASE_INSENSITIVE_ORDER.compare(aName, bName);
		if (order == 0) {
			order = this == CHANGED
					? String.CASE_INSENSITIVE_ORDER.compare(aName, bName)
					: Long.compare(bChanged, aChanged);
		}
		return order != 0 ? order : aKey.compareTo(bKey);
	}
}
