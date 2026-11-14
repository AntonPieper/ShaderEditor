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
 * <p>
 * Movement by a unit finds the segment a service moves over from an
 * offset, as TextView's iterators for accessibility do, with Motion's
 * characters and words: a word is a run of letters, digits and
 * underscores or of other characters that are not white space, and
 * white space alone is never one. A line is a line with its line break;
 * forward it is the line at its start or else the next one, backward
 * the last line at the end of the text or else the one before the
 * caret's. A paragraph is the rest of a line that is not empty, or the
 * next line that is not; a page the given number of lines. A search
 * gives up after MAX_CHARS characters or lines.
 * <p>
 * A change is heard in the window of its start in the text before it,
 * and in a window of the text after it that starts at the same offset,
 * the removed and the inserted text cut at MAX_CHARS characters from
 * that start.
 */
public final class AccessibleText {
	/** The lines of a block. */
	public static final int BLOCK_LINES = 128;
	/** The most characters of a window. */
	public static final int MAX_CHARS = 32_768;

	/** How far a service moves. */
	public enum Unit {
		CHARACTER, WORD, LINE, PARAGRAPH, PAGE
	}

	/** The range [start, end) of text moved over. */
	public record Segment(int start, int end) {
	}

	/**
	 * A change as a service hears it: the window of the text before and
	 * of the text after it, both from the same start, and where in them
	 * count removed characters took the place of added ones.
	 */
	public record Edit(Window before, Window after, int from, int removed,
			int added) {
	}

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
			end = cut(text, end);
		}
		return new Window(start, end);
	}

	/** Return change, made to before and giving after, as heard. */
	public static Edit edit(Text before, Text after, Document.Change change) {
		int from = change.start();
		Window window = window(before, from, from);
		int start = window.start();
		int limit = start + MAX_CHARS;
		int removedEnd = from + change.removed().length();
		int addedEnd = from + change.inserted().length();
		int beforeEnd = cut(before, Math.min(Math.max(window.end(), removedEnd),
				limit));
		int afterEnd = cut(after, Math.min(beforeEnd >= removedEnd
				? beforeEnd - removedEnd + addedEnd
				: addedEnd, limit));
		return new Edit(new Window(start, beforeEnd),
				new Window(start, afterEnd),
				from - start,
				Math.max(0, Math.min(removedEnd, beforeEnd) - from),
				Math.max(0, Math.min(addedEnd, afterEnd) - from));
	}

	/**
	 * Return the segment of unit after offset, null at the end; a page
	 * is pageLines lines.
	 */
	public static Segment following(Motion motion, Text text, Unit unit,
			int offset, int pageLines) {
		int length = text.length();
		if (offset >= length) {
			return null;
		}
		int n = text.lineOf(offset);
		switch (unit) {
			case CHARACTER:
				return new Segment(offset, motion.nextChar(text, offset));
			case WORD:
				int end = offset;
				int start;
				do {
					start = end;
					if (start >= length || start - offset > MAX_CHARS) {
						return null;
					}
					end = motion.nextWord(text, start);
				} while (blank(text, start, end));
				return new Segment(Math.max(offset,
						motion.previousWord(text, end)), end);
			case LINE:
				if (motion.lineStart(text, offset) != offset) {
					++n;
				}
				return n < text.lineCount() ? line(text, n) : null;
			case PARAGRAPH:
				int lineEnd = motion.lineEnd(text, offset);
				if (offset < lineEnd) {
					return new Segment(offset, lineEnd);
				}
				for (int last = n + MAX_CHARS; ++n < text.lineCount() &&
						n < last; ) {
					if (!text.line(n).isEmpty()) {
						return paragraph(text, n);
					}
				}
				return null;
			case PAGE:
				int next = n + Math.max(1, pageLines);
				return new Segment(offset, next < text.lineCount()
						? text.lineStart(next)
						: length);
		}
		return null;
	}

	/**
	 * Return the segment of unit before offset, null at the start; a
	 * page is pageLines lines.
	 */
	public static Segment preceding(Motion motion, Text text, Unit unit,
			int offset, int pageLines) {
		if (offset <= 0) {
			return null;
		}
		int n = text.lineOf(offset);
		int lineStart = text.lineStart(n);
		switch (unit) {
			case CHARACTER:
				return new Segment(motion.previousChar(text, offset), offset);
			case WORD:
				int start = offset;
				int end;
				do {
					end = start;
					if (end <= 0 || offset - end > MAX_CHARS) {
						return null;
					}
					start = motion.previousWord(text, end);
				} while (blank(text, start, end));
				return new Segment(start, Math.min(end,
						motion.nextWord(text, start)));
			case LINE:
				if (offset < text.length() || lineStart == offset) {
					--n;
				}
				return n >= 0 ? line(text, n) : null;
			case PARAGRAPH:
				if (offset > lineStart) {
					return new Segment(lineStart, offset);
				}
				for (int last = n - MAX_CHARS; --n >= 0 && n > last; ) {
					if (!text.line(n).isEmpty()) {
						return paragraph(text, n);
					}
				}
				return null;
			case PAGE:
				int first = Math.max(0, n - Math.max(1, pageLines));
				if (offset == text.length() && first < n) {
					++first;
				}
				return new Segment(text.lineStart(first), offset);
		}
		return null;
	}

	// Line n with its line break.
	private static Segment line(Text text, int n) {
		return new Segment(text.lineStart(n), n + 1 < text.lineCount()
				? text.lineStart(n + 1)
				: text.length());
	}

	// Line n without its line break.
	private static Segment paragraph(Text text, int n) {
		int start = text.lineStart(n);
		return new Segment(start, start + text.line(n).length());
	}

	private static boolean blank(Text text, int start, int end) {
		String s = text.substring(start, end);
		for (int i = 0; i < s.length(); ++i) {
			if (!Character.isWhitespace(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	// Returns end, or the offset before it if it splits a surrogate pair.
	private static int cut(Text text, int end) {
		return end > 0 && end < text.length() &&
				Character.isLowSurrogate(charAt(text, end)) ? end - 1 : end;
	}

	private static char charAt(Text text, int offset) {
		return text.substring(offset, offset + 1).charAt(0);
	}
}
