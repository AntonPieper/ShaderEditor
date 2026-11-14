package de.markusfisch.android.shadereditor.editor;

/**
 * What an editor view shows accessibility services of a text: a window
 * of it, never all of a large text, so the work for a service is bounded
 * by the window and not by the text.
 * <p>
 * The window rule: lines are counted in blocks of BLOCK_LINES lines. The
 * window holds the block of the line of the focus, a range such as the
 * selection's head, the block before it and the block after it. When
 * those lines have more than MAX_CHARS characters, the window is the
 * MAX_CHARS characters from the quarter of MAX_CHARS before the focus's
 * quarter on, inside those lines. So a window moves only when the focus
 * leaves its block or its quarter, and indices a service holds stay
 * valid while the caret moves inside it. Offsets of the text are indices
 * of the window less its start; offsets outside count as its ends.
 */
public final class AccessibleText {
	/** The lines of a block. */
	public static final int BLOCK_LINES = 128;
	/** The most characters of a window. */
	public static final int MAX_CHARS = 32_768;

	/** A window [start, end) of a text. */
	public record Window(int start, int end) {
		public int length() {
			return end - start;
		}

		/** Return the index of offset in the window, clamped to it. */
		public int index(int offset) {
			return Math.max(0, Math.min(offset - start, end - start));
		}
	}

	private AccessibleText() {
	}

	/** Return the window of text for the focus from from to to. */
	public static Window window(Text text, int from, int to) {
		int lineCount = text.lineCount();
		int first = Math.max(0,
				(text.lineOf(from) / BLOCK_LINES - 1) * BLOCK_LINES);
		int last = Math.min(lineCount - 1,
				(text.lineOf(to) / BLOCK_LINES + 2) * BLOCK_LINES - 1);
		int start = text.lineStart(first);
		int end = text.lineStart(last) + text.line(last).length();
		if (end - start > MAX_CHARS) {
			int quarter = MAX_CHARS / 4;
			start = Math.max(start, (from / quarter - 1) * quarter);
			end = Math.min(end, start + MAX_CHARS);
			if (start > 0 && Character.isLowSurrogate(charAt(text, start))) {
				++start;
			}
			if (end < text.length() &&
					Character.isLowSurrogate(charAt(text, end))) {
				--end;
			}
		}
		return new Window(start, end);
	}

	private static char charAt(Text text, int offset) {
		return text.substring(offset, offset + 1).charAt(0);
	}
}
