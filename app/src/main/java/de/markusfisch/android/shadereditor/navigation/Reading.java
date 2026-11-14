package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

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

	/** A file or folder as the listing of its folder tells it. */
	public record Entry(String name, boolean folder) {
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
		List<Entry> visible = visible();
		List<Row> rows = new ArrayList<>();
		String path = level.path();
		for (Entry entry : visible.subList(0, Math.min(shown,
				visible.size()))) {
			String name = entry.name;
			Row.Kind kind = entry.folder
					? Row.Kind.FOLDER
					: isImage(name) ? Row.Kind.IMAGE : Row.Kind.TEXT;
			rows.add(new Row(path != null ? path + "/" + name : name,
					kind,
					Row.Picture.of(switch (kind) {
						case FOLDER -> Row.Picture.Icon.FOLDER;
						case IMAGE -> Row.Picture.Icon.IMAGE;
						default -> Row.Picture.Icon.SHADER;
					}),
					name,
					null,
					0,
					entry.folder ? level.enter(name) : null,
					EnumSet.noneOf(Row.Mark.class),
					EnumSet.noneOf(Row.Action.class)));
		}
		if (visible.size() > shown) {
			rows.add(new Row("", Row.Kind.MORE, Row.Picture.of(
					Row.Picture.Icon.SHADER), more,
					String.format(Locale.getDefault(), shownOf, shown,
							visible.size()), 0, null,
					EnumSet.noneOf(Row.Mark.class),
					EnumSet.noneOf(Row.Action.class)));
		}
		return rows;
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

	private static boolean isImage(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".png") || lower.endsWith(".jpg") ||
				lower.endsWith(".jpeg");
	}
}
