package de.markusfisch.android.shadereditor.navigation;

/**
 * How a file's name is shortened where it does not fit: its ending
 * stays, and its stem is cut where it does not fit, as in
 * "A very long sketch na….frag", so that what the file is can still be
 * seen. A name without an ending, as a folder's, is cut at its end.
 * Rows, the toolbar's title and the image sheet cut names by this one
 * rule.
 */
public final class NameCut {
	/** Whether a text fits where the name is shown. */
	public interface Fits {
		boolean fits(String text);
	}

	public static final String ELLIPSIS = "…";
	// An ending longer than this is part of the name, not its type.
	private static final int LONGEST_ENDING = 8;

	private NameCut() {
	}

	/** name as it fits: whole, else cut as the class says. */
	public static String cut(String name, Fits fits) {
		if (name == null || name.isEmpty() || fits.fits(name)) {
			return name;
		}
		String ending = ending(name);
		if (!ending.isEmpty()) {
			String stem = name.substring(0, name.length() - ending.length());
			String cut = longest(stem, ending, fits);
			if (cut != null) {
				return cut;
			}
		}
		String cut = longest(name, "", fits);
		return cut != null ? cut : ELLIPSIS;
	}

	/**
	 * The ending of name that says what it is, ".frag", or "" for none:
	 * a name that starts with its only dot, as ".gitignore", has none,
	 * nor has one whose ending is long or holds a blank.
	 */
	public static String ending(String name) {
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || name.length() - dot > LONGEST_ENDING) {
			return "";
		}
		String ending = name.substring(dot);
		for (int i = 0; i < ending.length(); ++i) {
			if (Character.isWhitespace(ending.charAt(i))) {
				return "";
			}
		}
		return ending;
	}

	// The longest start of text, at least one character, that fits
	// with the ellipsis and after, or null when none does.
	private static String longest(String text, String after, Fits fits) {
		int fitting = 0;
		int low = 1;
		int high = text.length() - 1;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			if (fits.fits(cutAt(text, middle) + ELLIPSIS + after)) {
				fitting = middle;
				low = middle + 1;
			} else {
				high = middle - 1;
			}
		}
		return fitting > 0
				? cutAt(text, fitting) + ELLIPSIS + after
				: null;
	}

	// The first length chars of text, never half a character and
	// without blanks before the ellipsis.
	private static String cutAt(String text, int length) {
		if (length < text.length() &&
				Character.isLowSurrogate(text.charAt(length))) {
			--length;
		}
		String start = text.substring(0, Math.max(0, length));
		int end = start.length();
		while (end > 1 && Character.isWhitespace(start.charAt(end - 1))) {
			--end;
		}
		return start.substring(0, end);
	}
}
