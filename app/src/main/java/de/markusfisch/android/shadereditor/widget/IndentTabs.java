package de.markusfisch.android.shadereditor.widget;

import androidx.annotation.NonNull;

/**
 * Which tabs of a text tab stops can place at the editor's tab width.
 * <p>
 * The editor gives each tab one tab width. Tab stops at every multiple
 * of that width do the same for the tabs of a line's leading indentation
 * (tabs that only follow other tabs), but not for a tab after any other
 * character: a stop moves that tab to the next multiple instead.
 */
final class IndentTabs {
	/**
	 * Number of tab stops, and so the deepest leading tab they place.
	 */
	static final int STOP_COUNT = 32;

	@FunctionalInterface
	interface TabConsumer {
		void accept(int index);
	}

	private IndentTabs() {
	}

	/**
	 * @return the offsets of the tab stops for {@code tabWidth}, or
	 * none for a width below 1
	 */
	@NonNull
	static int[] stops(int tabWidth) {
		if (tabWidth < 1) {
			return new int[0];
		}
		int[] stops = new int[STOP_COUNT];
		for (int i = 0; i < STOP_COUNT; ++i) {
			stops[i] = tabWidth * (i + 1);
		}
		return stops;
	}

	/**
	 * Calls {@code out} with the index of every tab in the lines that
	 * contain {@code [start, end]} that {@code stopCount} stops cannot
	 * place: a tab after any character other than a tab on its line, or
	 * a leading tab deeper than {@code stopCount}.
	 */
	static void forEachTabWithoutStop(
			@NonNull CharSequence text,
			int start,
			int end,
			int stopCount,
			@NonNull TabConsumer out) {
		int length = text.length();
		end = lineEnd(text, Math.min(Math.max(end, start), length));
		boolean leading = true;
		int depth = 0;
		for (int i = lineStart(text, Math.min(start, length)); i < end; ++i) {
			char c = text.charAt(i);
			if (c == '\n') {
				leading = true;
				depth = 0;
			} else if (c == '\t') {
				if (leading && depth < stopCount) {
					++depth;
				} else {
					leading = false;
					out.accept(i);
				}
			} else {
				leading = false;
			}
		}
	}

	/**
	 * @return the index of the first character of the line that
	 * contains {@code index}
	 */
	static int lineStart(@NonNull CharSequence text, int index) {
		while (index > 0 && text.charAt(index - 1) != '\n') {
			--index;
		}
		return index;
	}

	/**
	 * @return the index of the line break that ends the line that
	 * contains {@code index}, or the length of the text
	 */
	static int lineEnd(@NonNull CharSequence text, int index) {
		int length = text.length();
		while (index < length && text.charAt(index) != '\n') {
			++index;
		}
		return index;
	}
}
