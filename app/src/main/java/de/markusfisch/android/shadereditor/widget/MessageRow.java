package de.markusfisch.android.shadereditor.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rules of the row under a line with errors that shows their
 * messages: which line's row opens after a result, which lines of text
 * the row shows, and how its fixes are labelled and placed.
 */
final class MessageRow {
	static final int MAX_LINES = 3;

	/** How wide text is. */
	interface Measure {
		float width(String text);
	}

	/** How the row's text is measured and what it says when cut. */
	interface Style extends Measure {

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

	/**
	 * Return the first of labels, longest first, that fits in width, or
	 * else the last, the shortest: a label is never cut.
	 */
	static String label(List<String> labels, float width, Measure measure) {
		int last = labels.size() - 1;
		for (int i = 0; i < last; ++i) {
			String label = labels.get(i);
			if (measure.width(label) <= width) {
				return label;
			}
		}
		return labels.get(last);
	}

	/** How the fixes of a row are measured and spaced. */
	record FixMetrics(float padding, float gap, Measure measure,
			String ellipsis) {
	}

	/**
	 * A fix as the row shows it: the label shown, which fix of the row it
	 * is, the line of fixes it is on (0, or 1 when two do not fit side by
	 * side), and where its button starts and ends, from where the code's
	 * text starts.
	 */
	record Placed(String label, int index, int line, float start,
			float end) {
	}

	/**
	 * Return fixes laid out with their buttons ending at end, the last
	 * fix at the end and the one before it to its left, gap apart. Each
	 * label is the longest of its labels that lets all fit; when the
	 * shortest still do not, the second goes on a line under the first.
	 * A label that still does not fit alone has its name cut in the
	 * middle (CodeView.Fix), if it has one; any other is never cut.
	 */
	static List<Placed> place(List<CodeView.Fix> fixes, float end,
			FixMetrics metrics) {
		int count = fixes.size();
		int[] choice = new int[count];
		while (rowWidth(fixes, choice, metrics) > end) {
			// The widest that can shorten does.
			int widest = -1;
			float most = -1;
			for (int i = 0; i < count; ++i) {
				if (choice[i] < fixes.get(i).labels().size() - 1) {
					float width = buttonWidth(fixes.get(i), choice[i],
							metrics);
					if (width > most) {
						most = width;
						widest = i;
					}
				}
			}
			if (widest < 0) {
				break;
			}
			++choice[widest];
		}
		List<Placed> placed = new ArrayList<>(count);
		if (count < 2 || rowWidth(fixes, choice, metrics) <= end) {
			float at = end;
			for (int i = count; i-- > 0; ) {
				String label = fit(fixes.get(i), choice[i],
						end - 2 * metrics.padding(), metrics);
				float start = at - 2 * metrics.padding() -
						metrics.measure().width(label);
				placed.add(0, new Placed(label, i, 0, start, at));
				at = start - metrics.gap();
			}
			return placed;
		}
		// One under the other, each by itself.
		for (int i = 0; i < count; ++i) {
			CodeView.Fix fix = fixes.get(i);
			String label = fit(fix, MessageRow.labelIndex(fix,
							end - 2 * metrics.padding(), metrics.measure()),
					end - 2 * metrics.padding(), metrics);
			placed.add(new Placed(label, i, i, end - 2 * metrics.padding() -
					metrics.measure().width(label), end));
		}
		return placed;
	}

	/**
	 * Return label with the characters from cutStart to cutEnd, a name,
	 * cut in the middle with ellipsis so that it fits in width, keeping
	 * what is before and after the name; as short as two characters of
	 * the name, if nothing fits.
	 */
	static String cutMiddle(String label, int cutStart, int cutEnd,
			float width, Measure measure, String ellipsis) {
		if (measure.width(label) <= width || cutStart < 0 ||
				cutEnd - cutStart < 3) {
			return label;
		}
		String before = label.substring(0, cutStart);
		String after = label.substring(cutEnd);
		String name = label.substring(cutStart, cutEnd);
		String cut = label;
		for (int keep = name.length() - 1; keep >= 2; --keep) {
			int left = (keep + 1) / 2;
			int right = keep / 2;
			cut = before + name.substring(0, left) + ellipsis +
					name.substring(name.length() - right) + after;
			if (measure.width(cut) <= width) {
				return cut;
			}
		}
		return cut;
	}

	// The index of the first of the labels of fix that fits in width,
	// else the last.
	private static int labelIndex(CodeView.Fix fix, float width,
			Measure measure) {
		List<String> labels = fix.labels();
		for (int i = 0; i < labels.size() - 1; ++i) {
			if (measure.width(labels.get(i)) <= width) {
				return i;
			}
		}
		return labels.size() - 1;
	}

	// Label choice of fix, its name cut to width if it is the last.
	private static String fit(CodeView.Fix fix, int choice, float width,
			FixMetrics metrics) {
		String label = fix.labels().get(choice);
		return choice == fix.labels().size() - 1
				? cutMiddle(label, fix.cutStart(), fix.cutEnd(), width,
				metrics.measure(), metrics.ellipsis())
				: label;
	}

	private static float buttonWidth(CodeView.Fix fix, int choice,
			FixMetrics metrics) {
		return 2 * metrics.padding() +
				metrics.measure().width(fix.labels().get(choice));
	}

	private static float rowWidth(List<CodeView.Fix> fixes, int[] choice,
			FixMetrics metrics) {
		float width = 0;
		for (int i = 0; i < fixes.size(); ++i) {
			width += buttonWidth(fixes.get(i), choice[i], metrics);
		}
		return width + Math.max(0, fixes.size() - 1) * metrics.gap();
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
