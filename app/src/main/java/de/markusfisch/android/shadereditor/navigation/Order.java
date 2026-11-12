package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** How the rows of a sortable level are ordered. Folders come first. */
public enum Order {
	CHANGED,
	BY_NAME;

	public List<Row> sort(List<Row> rows) {
		List<Row> sorted = new ArrayList<>(rows);
		Collections.sort(sorted, comparator());
		return sorted;
	}

	private Comparator<Row> comparator() {
		return (a, b) -> {
			boolean aFolder = a.kind() == Row.Kind.FOLDER;
			if (aFolder != (b.kind() == Row.Kind.FOLDER)) {
				return aFolder ? -1 : 1;
			}
			int order = this == CHANGED
					? Long.compare(b.changed(), a.changed())
					: String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
			if (order == 0) {
				order = this == CHANGED
						? String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name())
						: Long.compare(b.changed(), a.changed());
			}
			return order != 0 ? order : a.key().compareTo(b.key());
		};
	}
}
