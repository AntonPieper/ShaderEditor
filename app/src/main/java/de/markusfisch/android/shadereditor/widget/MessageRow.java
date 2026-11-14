package de.markusfisch.android.shadereditor.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rules of the row under a line with errors that shows their
 * messages: which line's row opens after a result, and which lines of
 * text the row shows.
 */
final class MessageRow {
	static final int MAX_LINES = 3;

	/** How the row's text is measured and what it says when cut. */
	interface Style {
		float width(String text);

		/**
		 * Return how many characters of text from start fit in width;
		 * at least one.
		 */
		int fit(String text, int start, float width);

		String ellipsis();

		/** Return the hint for count errors that are left out. */
		String more(int count);
	}

	private MessageRow() {
	}

	/**
	 * Return the line whose row opens after a result with errors on the
	 * lines failing, in order: the caret's line if it fails; else the
	 * open line if it still fails; else the first failing line from
	 * first to last, the lines on screen; else -1.
	 */
	static int choose(int[] failing, int open, int caret, int first,
			int last) {
		if (Arrays.binarySearch(failing, caret) >= 0) {
			return caret;
		}
		if (open >= 0 && Arrays.binarySearch(failing, open) >= 0) {
			return open;
		}
		for (int line : failing) {
			if (line >= first && line <= last) {
				return line;
			}
		}
		return -1;
	}

	/**
	 * Return the lines that show messages in width: each message from a
	 * new line without the white space around it, wrapped at spaces, at
	 * most MAX_LINES. When more does not
	 * fit, the last line ends in an ellipsis if a message is cut, and in
	 * the hint for the messages left out entirely.
	 */
	static String[] lines(List<String> messages, float width, Style style) {
		List<String> lines = new ArrayList<>();
		// The message and offset the last line starts at.
		int lastMessage = 0;
		int lastStart = 0;
		for (int m = 0, count = messages.size(); m < count; ++m) {
			String message = messages.get(m).trim();
			int pos = 0;
			do {
				if (lines.size() == MAX_LINES) {
					lines.set(MAX_LINES - 1, cut(
							messages.get(lastMessage).trim().substring(lastStart),
							pos == 0, count - m - (pos > 0 ? 1 : 0), width,
							style));
					return lines.toArray(new String[0]);
				}
				int end = breakAt(message, pos, width, style);
				lastMessage = m;
				lastStart = pos;
				lines.add(message.substring(pos, end).trim());
				pos = end;
				while (pos < message.length() && message.charAt(pos) == ' ') {
					++pos;
				}
			} while (pos < message.length());
		}
		return lines.toArray(new String[0]);
	}

	// The last line, from rest, followed by the hint for left out
	// messages; rest is the whole end of its message if complete.
	private static String cut(String rest, boolean complete, int left,
			float width, Style style) {
		String more = left > 0 ? " " + style.more(left) : "";
		if (complete && style.width(rest + more) <= width) {
			return rest + more;
		}
		String end = style.ellipsis() + more;
		float room = Math.max(1, width - style.width(end));
		return rest.substring(0, breakAt(rest, 0, room, style)).trim() + end;
	}

	// Where the line of text from start ends in width: after the last
	// space that fits, or where the characters stop fitting if there is
	// none.
	private static int breakAt(String text, int start, float width,
			Style style) {
		int end = start + Math.max(1, style.fit(text, start, width));
		if (end >= text.length()) {
			return text.length();
		}
		if (text.charAt(end) == ' ') {
			return end;
		}
		int space = text.lastIndexOf(' ', end - 1);
		return space > start ? space : end;
	}
}
