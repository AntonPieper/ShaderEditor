package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a source knows of its level: the header's title and second
 * line, whether the level is read-only and can be sorted, the action
 * of its bar, its rows in sections, and what stands under them, or
 * null.
 */
public record Listing(String title, String subtitle, boolean readOnly,
		boolean sortable, Bar bar, List<Section> sections, Notice notice) {
	/** The action at the bottom of the drawer. */
	public enum Bar {
		NONE,
		NEW,
		NEW_SHADER,
		/** Open folder, and Open file beside it. */
		OPEN
	}

	/** Rows under a heading; a section without heading shows none. */
	public record Section(String heading, List<Row> rows) {
	}

	public int size() {
		int size = 0;
		for (Section section : sections) {
			size += section.rows.size();
		}
		return size;
	}

	/**
	 * The rows whose name contains the query, ignoring case. A section
	 * left without rows is left out with its heading.
	 */
	public Listing filter(String query) {
		String needle = query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return this;
		}
		List<Section> kept = new ArrayList<>();
		for (Section section : sections) {
			List<Row> rows = new ArrayList<>();
			for (Row row : section.rows) {
				if (row.name().toLowerCase(Locale.ROOT).contains(needle)) {
					rows.add(row);
				}
			}
			if (!rows.isEmpty()) {
				kept.add(new Section(section.heading, rows));
			}
		}
		return new Listing(title, subtitle, readOnly, sortable, bar, kept,
				notice);
	}
}
