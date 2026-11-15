package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.FileKind;

/**
 * How far the files of one folder are read: being read, read, or not,
 * and how many of its rows are shown. A result that comes after
 * reading was stopped changes nothing.
 */
public record Reading(Phase phase, List<Entry> entries, String reason,
		int shown) {
	/** Rows come in pages of this many. */
	public static final int PAGE = 200;
	/** After this many milliseconds, reading may be stopped. */
	public static final long SLOW_MILLIS = 2000;

	public enum Phase {
		READING,
		SLOW,
		STOPPED,
		LISTED,
		FAILED,
		LOST_FOLDER,
		LOST_FILE
	}

	/**
	 * A file or folder as the listing of its folder tells it: its
	 * name, when it was changed and how many bytes it has, 0 when not
	 * told.
	 */
	public record Entry(String name, boolean folder, long modified,
			long size) {
		/** An entry of which the listing told no time. */
		public Entry(String name, boolean folder) {
			this(name, folder, 0, 0);
		}

		/** An entry of which the listing told no size. */
		public Entry(String name, boolean folder, long modified) {
			this(name, folder, modified, 0);
		}
	}

	public static Reading started() {
		return new Reading(Phase.READING, List.of(), null, PAGE);
	}

	public Reading slow() {
		return phase == Phase.READING ? with(Phase.SLOW) : this;
	}

	public Reading stopped() {
		return isReading() ? with(Phase.STOPPED) : this;
	}

	/**
	 * Reads the folder again while what was read stays shown, with as
	 * many rows as were shown, until the new entries come or reading
	 * fails: the list does not empty and jump to its top.
	 */
	public Reading again() {
		return new Reading(Phase.READING, entries, null, shown);
	}

	/** Takes the entries of the folder, folders first. */
	public Reading listed(List<Entry> entries) {
		return isReading()
				? new Reading(Phase.LISTED, entries, null, shown)
				: this;
	}

	public Reading failed(String reason) {
		return failed(reason, true, true, false);
	}

	/**
	 * Reading ends without entries, for reason. When the files of what
	 * was opened cannot be reached or the grant to them is gone, it is
	 * lost and has to be chosen again; else reading can be tried again.
	 *
	 * @param file whether what was opened is a single file
	 */
	public Reading failed(String reason, boolean reachable, boolean granted,
			boolean file) {
		Phase phase = reachable && granted
				? Phase.FAILED
				: file ? Phase.LOST_FILE : Phase.LOST_FOLDER;
		return isReading()
				? new Reading(phase, List.of(), reason, shown)
				: this;
	}

	/** Whether what was opened has to be chosen again. */
	public boolean isLost() {
		return phase == Phase.LOST_FOLDER || phase == Phase.LOST_FILE;
	}

	/** Shows the next page of rows. */
	public Reading more() {
		return new Reading(phase, entries, reason, shown + PAGE);
	}

	/**
	 * The rows of the folder at level, without names that start with
	 * a dot; past the rows shown, one more row offers the rest.
	 *
	 * @param more     what that row is called
	 * @param shownOf  how it says how many of all rows are shown, like
	 *                 "%1$d of %2$d"
	 */
	public List<Row> rows(Level level, String more, String shownOf) {
		return rows(level, more, shownOf, null);
	}

	/**
	 * The rows as {@link #rows(Level, String, String)}, in order when
	 * it is not null: all of them are ordered before the first are
	 * shown, so that the newest file is never behind Show more.
	 */
	public List<Row> rows(Level level, String more, String shownOf,
			Order order) {
		return rows(level, more, shownOf, order, Set.of());
	}

	/**
	 * The rows as {@link #rows(Level, String, String, Order)} without
	 * the entries named taken, which are shown elsewhere, as a README's
	 * sections show them: pages count only the rest.
	 */
	public List<Row> rows(Level level, String more, String shownOf,
			Order order, Set<String> taken) {
		List<Entry> visible = new ArrayList<>();
		for (Entry entry : visible()) {
			if (!taken.contains(entry.name)) {
				visible.add(entry);
			}
		}
		// Ordered whole, then only the rows shown are made.
		List<Entry> ordered = order != null
				? order.sortEntries(visible)
				: visible;
		List<Row> rows = new ArrayList<>();
		for (Entry entry : ordered.subList(0, Math.min(shown,
				ordered.size()))) {
			rows.add(row(level, entry));
		}
		if (visible.size() > shown) {
			rows.add(new Row("", Row.Kind.MORE, Face.of(Row.Kind.MORE, more,
					null), more,
					String.format(Locale.getDefault(), shownOf, shown,
							visible.size()), 0, null,
					EnumSet.noneOf(Row.Mark.class),
					EnumSet.noneOf(Row.Action.class)));
		}
		return rows;
	}

	/**
	 * The rows of the entries named names that are in the folder, in
	 * the order of names, wherever they would sort.
	 */
	public List<Row> rowsNamed(Level level, List<String> names) {
		Map<String, Entry> byName = new HashMap<>();
		for (Entry entry : visible()) {
			byName.put(entry.name, entry);
		}
		List<Row> rows = new ArrayList<>();
		for (String name : names) {
			Entry entry = byName.get(name);
			if (entry != null) {
				rows.add(row(level, entry));
			}
		}
		return rows;
	}

	// The row of an entry of the folder at level, before its face and
	// line are known.
	private static Row row(Level level, Entry entry) {
		String name = entry.name;
		String path = level.path();
		// A hint from the name, for the face: what the file is, is read
		// from its bytes when it is opened or used. A file larger than
		// the editor opens cannot be opened here.
		Row.Kind kind = entry.folder
				? Row.Kind.FOLDER
				: entry.size > Face.LARGEST
				? Row.Kind.OTHER
				: FileKind.hint(name, null) == FileKind.PICTURE
				? Row.Kind.IMAGE
				: Row.Kind.TEXT;
		return new Row(path != null ? path + "/" + name : name,
				kind,
				Face.of(kind, name, null),
				name,
				null,
				Math.max(0, entry.modified),
				entry.folder ? level.enter(name) : null,
				EnumSet.noneOf(Row.Mark.class),
				EnumSet.noneOf(Row.Action.class));
	}

	/** What stands under the rows, or null. */
	public Notice notice() {
		return switch (phase) {
			case READING -> new Notice(Notice.Kind.READING, null);
			case SLOW -> new Notice(Notice.Kind.SLOW, null);
			case STOPPED -> new Notice(Notice.Kind.STOPPED, null);
			case FAILED -> new Notice(Notice.Kind.FAILED, reason);
			case LOST_FOLDER -> new Notice(Notice.Kind.LOST_FOLDER, reason);
			case LOST_FILE -> new Notice(Notice.Kind.LOST_FILE, reason);
			case LISTED -> visible().isEmpty()
					? new Notice(Notice.Kind.EMPTY, null)
					: null;
		};
	}

	private boolean isReading() {
		return phase == Phase.READING || phase == Phase.SLOW;
	}

	private Reading with(Phase phase) {
		return new Reading(phase, entries, reason, shown);
	}

	private List<Entry> visible() {
		List<Entry> visible = new ArrayList<>();
		for (Entry entry : entries) {
			if (!entry.name.startsWith(".")) {
				visible.add(entry);
			}
		}
		return visible;
	}

}
