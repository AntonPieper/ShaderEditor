package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/**
 * Every different spelling of a token, as a number. Two tokens are the
 * same word exactly when their symbols are equal, so the layers above
 * the lexer compare ints and keep no String per token. The words of
 * the language come first and have the same number in every table
 * ({@link Words}).
 *
 * A table belongs to the trees of one text and only grows; it is used
 * by one thread at a time.
 */
final class Symbols {
	private String[] names = new String[512];
	// Open addressing; -1 where nothing is.
	private int[] slots = new int[1024];
	private int count;
	private int characters;

	Symbols() {
		Arrays.fill(slots, -1);
		for (String word : Words.all()) {
			of(word, 0, word.length());
		}
	}

	/** The symbol of the characters [start, end) of text. */
	int of(CharSequence text, int start, int end) {
		int hash = 0;
		for (int i = start; i < end; ++i) {
			hash = hash * 31 + text.charAt(i);
		}
		int mask = slots.length - 1;
		int slot = (hash ^ (hash >>> 15)) & mask;
		for (int id; (id = slots[slot]) >= 0; slot = (slot + 1) & mask) {
			if (spells(names[id], text, start, end)) {
				return id;
			}
		}
		if (count == names.length) {
			names = Arrays.copyOf(names, count * 2);
		}
		names[count] = text.subSequence(start, end).toString();
		characters += end - start;
		slots[slot] = count;
		if (++count * 2 > slots.length) {
			grow();
		}
		return count - 1;
	}

	String name(int symbol) {
		return names[symbol];
	}

	/**
	 * How many characters the spellings hold. A table never forgets
	 * one; who keeps a table over many edits reads the text anew with
	 * a fresh one when this has grown far beyond the text.
	 */
	int characters() {
		return characters;
	}

	private static boolean spells(String name, CharSequence text, int start,
			int end) {
		if (name.length() != end - start) {
			return false;
		}
		for (int i = 0, n = name.length(); i < n; ++i) {
			if (name.charAt(i) != text.charAt(start + i)) {
				return false;
			}
		}
		return true;
	}

	private void grow() {
		slots = new int[slots.length * 2];
		Arrays.fill(slots, -1);
		int mask = slots.length - 1;
		for (int id = 0; id < count; ++id) {
			int hash = 0;
			String name = names[id];
			for (int i = 0, n = name.length(); i < n; ++i) {
				hash = hash * 31 + name.charAt(i);
			}
			int slot = (hash ^ (hash >>> 15)) & mask;
			while (slots[slot] >= 0) {
				slot = (slot + 1) & mask;
			}
			slots[slot] = id;
		}
	}
}
