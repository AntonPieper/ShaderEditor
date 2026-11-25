package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/**
 * The tokens of a piece of text, in order and without overlap. What
 * lies between two tokens is white space, comments and line
 * continuations, so the text and its tokens together lose nothing.
 * Offsets count from where the piece starts.
 */
final class RawTokens {
	static final byte NAME = 1;
	static final byte NUMBER = 2;
	static final byte PUNCTUATOR = 3;
	/** A quoted path; only a directive line has one. */
	static final byte STRING = 4;
	/** A character no token of the language starts with. */
	static final byte INVALID = 5;

	/** Flag: the first token on its line. */
	static final byte LINE_START = 1;
	/** Flag: a token of a directive line, its "#" included. */
	static final byte DIRECTIVE = 2;

	int size;
	byte[] kind = new byte[64];
	byte[] flags = new byte[64];
	int[] start = new int[64];
	int[] end = new int[64];
	int[] symbol = new int[64];

	void add(byte kind, int start, int end, int symbol, int flags) {
		if (size == this.kind.length) {
			resize(size * 2);
		}
		this.kind[size] = kind;
		this.flags[size] = (byte) flags;
		this.start[size] = start;
		this.end[size] = end;
		this.symbol[size++] = symbol;
	}

	boolean is(int index, int flag) {
		return (flags[index] & flag) != 0;
	}

	/** Forgets every token from index on. */
	void truncate(int index) {
		size = index;
	}

	/** Gives back the room no token uses. */
	void trim() {
		resize(size);
	}

	private void resize(int capacity) {
		kind = Arrays.copyOf(kind, capacity);
		flags = Arrays.copyOf(flags, capacity);
		start = Arrays.copyOf(start, capacity);
		end = Arrays.copyOf(end, capacity);
		symbol = Arrays.copyOf(symbol, capacity);
	}
}
