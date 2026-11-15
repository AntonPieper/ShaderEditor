package de.markusfisch.android.shadereditor.navigation;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * How a name is shortened where it does not fit, by the width it has,
 * as Finder does: its start, an ellipsis, and its end, which is the
 * last part of the stem with the whole ending, as in
 * "A very long sket…at sunset.frag", about half and half, as a text
 * view's own middle ellipsis splits a name. Two names that differ only
 * at their end stay apart, and what the file is can still be seen.
 * The ending is never cut while one character of the start fits
 * beside it. A folder in front, as in "textures/noise.png" under
 * Running, or "Sketches / textures", shrinks first. Nothing is cut
 * between the parts of one character, an emoji or a letter with its
 * marks. Rows, the toolbar's title, the image sheet and the New sheet
 * cut names by this one rule.
 */
public final class NameCut {
	/** Whether a text fits where the name is shown. */
	public interface Fits {
		boolean fits(String text);
	}

	public static final String ELLIPSIS = "\u2026";
	// The end keeps about half of what is shown, the ending in it, as
	// a text view's own middle ellipsis does.
	private static final float END_SHARE = 0.5f;
	// The end keeps this many characters of the stem at least, while
	// one of its start is left.
	private static final int STEM_END = 3;
	// A part of an ending longer than this is part of the name.
	private static final int LONGEST_PART = 8;

	private NameCut() {
	}

	/** name as it fits: whole, else cut as the class says. */
	public static String cut(String name, Fits fits) {
		if (name == null || name.isEmpty() || fits.fits(name)) {
			return name;
		}
		int slash = name.lastIndexOf('/');
		if (slash > 0 && slash < name.length() - 1) {
			// "textures/" or "Sketches / ": the folder goes first.
			String folder = name.substring(0, slash).trim();
			int last = slash + 1;
			while (last < name.length() - 1 && name.charAt(last) == ' ') {
				++last;
			}
			String between = name.substring(folder.length(), last);
			String file = name.substring(last);
			if (!folder.isEmpty()) {
				String shown = middle(folder, "",
						text -> fits.fits(text + between + file));
				if (shown != null) {
					return shown + between + file;
				}
			}
			String gone = ELLIPSIS + between;
			if (fits.fits(gone + file)) {
				return gone + file;
			}
			String cut = file(file, text -> fits.fits(gone + text));
			if (cut != null) {
				return gone + cut;
			}
		}
		String cut = file(name, fits);
		return cut != null ? cut : end(name, fits);
	}

	/**
	 * The ending of name that says what it is, ".frag", or for two,
	 * ".frag.glsl", or "" for none: a name that starts with its only
	 * dot, as ".gitignore", has none, nor has one whose ending is long,
	 * holds a blank or, before the last, is no word.
	 */
	public static String ending(String name) {
		String last = part(name, name.length());
		if (last.isEmpty()) {
			return "";
		}
		String before = part(name, name.length() - last.length());
		boolean word = false;
		for (int i = 1; i < before.length(); ++i) {
			word |= Character.isLetter(before.charAt(i));
		}
		return word ? before + last : last;
	}

	// The name cut in its middle with its ending kept, or null when not
	// even one character of its start fits beside the ending.
	private static String file(String name, Fits fits) {
		String ending = ending(name);
		String stem = name.substring(0, name.length() - ending.length());
		String shown = middle(stem, ending, fits);
		if (shown != null) {
			return shown;
		}
		return !ending.isEmpty() && fits.fits(ELLIPSIS + ending)
				? ELLIPSIS + ending
				: null;
	}

	// The most of stem that fits as start, ellipsis, end of stem and
	// after, or null when not even one character of its start does.
	private static String middle(String stem, String after, Fits fits) {
		List<Integer> bounds = characters(stem);
		int count = bounds.size() - 1;
		String fitting = null;
		int low = 1;
		int high = count - 1;
		while (low <= high) {
			int kept = (low + high) >>> 1;
			String text = keep(stem, bounds, kept, after);
			if (fits.fits(text)) {
				fitting = text;
				low = kept + 1;
			} else {
				high = kept - 1;
			}
		}
		return fitting;
	}

	// kept characters of stem, the start and the end, around an
	// ellipsis, with after.
	private static String keep(String stem, List<Integer> bounds, int kept,
			String after) {
		int count = bounds.size() - 1;
		int shown = kept + after.length();
		int end = Math.round(shown * END_SHARE) - after.length();
		// Even narrow, a little of the stem's end: names that differ
		// only there stay apart.
		end = Math.max(end, Math.min(STEM_END, kept - 1));
		end = Math.max(0, Math.min(end, kept - 1));
		int start = kept - end;
		String head = stem.substring(0, bounds.get(start));
		String tail = stem.substring(bounds.get(count - end));
		// No blank beside the ellipsis, while something else is left.
		String trimmedHead = head.replaceAll("\\s+$", "");
		String trimmedTail = tail.replaceAll("^\\s+", "");
		return (trimmedHead.isEmpty() ? head : trimmedHead) + ELLIPSIS +
				(trimmedTail.isEmpty() && !tail.isEmpty()
						? tail
						: trimmedTail) + after;
	}

	// Where the characters of text begin, and its length: never between
	// the parts of one character a reader sees.
	private static List<Integer> characters(String text) {
		BreakIterator characters = BreakIterator.getCharacterInstance();
		characters.setText(text);
		List<Integer> bounds = new ArrayList<>();
		for (int at = characters.first(); at != BreakIterator.DONE;
				at = characters.next()) {
			bounds.add(at);
		}
		return bounds;
	}

	// The last ".part" of name before end when it can be part of an
	// ending, or "".
	private static String part(String name, int end) {
		int dot = name.lastIndexOf('.', end - 1);
		if (dot <= 0 || end - dot > LONGEST_PART || end - dot < 2) {
			return "";
		}
		for (int i = dot + 1; i < end; ++i) {
			char c = name.charAt(i);
			if (Character.isWhitespace(c) || c == '.') {
				return "";
			}
		}
		return name.substring(dot, end);
	}

	// The last resort, where not even the ending fits: the start that
	// fits, then the ellipsis.
	private static String end(String name, Fits fits) {
		List<Integer> bounds = characters(name);
		String fitting = ELLIPSIS;
		for (int i = 1; i < bounds.size() - 1; ++i) {
			String text = name.substring(0, bounds.get(i)) + ELLIPSIS;
			if (!fits.fits(text)) {
				break;
			}
			fitting = text;
		}
		return fitting;
	}
}
