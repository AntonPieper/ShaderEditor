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
	/** A quoted path; only #include and #pragma se have one. */
	static final byte STRING = 4;
	/** A character no token of the language starts with. */
	static final byte INVALID = 5;
	/**
	 * A name written right behind a number, as the "oo" of "1.0foo"
	 * or the "A" of "0x1e+A". The preprocessor takes the number and
	 * the name for one number and expands no macro there; to the
	 * language it is a name like any other.
	 */
	static final byte GLUED_NAME = 6;

	/** Flag: the first token on its line. */
	static final byte LINE_START = 1;
	/** Flag: a token of a directive line, its "#" included. */
	static final byte DIRECTIVE = 2;
	/**
	 * Flag: white space or a comment stands before the token. A line
	 * continuation is none: it leaves what it joins side by side.
	 */
	static final byte SPACED = 4;
	/** Flag, set by the directives: not compiled, in a branch that is off. */
	static final byte OFF = 8;
	/**
	 * Flag, set by the directives: in a conditional whose condition
	 * could not be decided, so whether it is compiled is a guess.
	 */
	static final byte UNDECIDED = 16;

	int size;
	byte[] kind = new byte[64];
	byte[] flags = new byte[64];
	int[] start = new int[64];
	int[] end = new int[64];
	int[] symbol = new int[64];

	void add(byte kind, int start, int end, int symbol, int flags) {
		if (size == this.kind.length) {
			resize(Math.max(16, size * 2));
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
