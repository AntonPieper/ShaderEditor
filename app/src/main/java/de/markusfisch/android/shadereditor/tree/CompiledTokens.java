package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/**
 * The tokens the compiler parses: those of the text that no directive
 * turns off, with every macro expanded. Each says where it is written
 * and, if it came out of a macro, through which invocation.
 */
final class CompiledTokens {
	int size;
	int[] symbol = new int[64];
	byte[] kind = new byte[64];
	/**
	 * The raw token that spells it; or, for a token out of a macro's
	 * text, -1 - k for the k-th of {@link #macro} and {@link #bodyIndex}.
	 */
	int[] raw = new int[64];
	/** The raw token that names the outermost macro it came through, or -1. */
	int[] via = new int[64];
	/** For tokens out of a macro's text: the macro and which token of its text. */
	Macro[] macro = new Macro[4];
	int[] bodyIndex = new int[4];
	private int bodies;

	void add(int symbol, byte kind, int raw, int via) {
		if (size == this.symbol.length) {
			resize(Math.max(16, size * 2));
		}
		this.symbol[size] = symbol;
		this.kind[size] = kind;
		this.raw[size] = raw;
		this.via[size++] = via;
	}

	void add(int symbol, byte kind, Macro from, int index, int via) {
		if (bodies == macro.length) {
			macro = Arrays.copyOf(macro, Math.max(4, bodies * 2));
			bodyIndex = Arrays.copyOf(bodyIndex, macro.length);
		}
		macro[bodies] = from;
		bodyIndex[bodies] = index;
		add(symbol, kind, -1 - bodies++, via);
	}

	/** Forgets every token from index on. */
	void truncate(int index) {
		size = index;
		bodies = 0;
		for (int i = 0; i < size; ++i) {
			bodies = Math.max(bodies, -raw[i]);
		}
	}

	/** Gives back the room no token uses. */
	void trim() {
		resize(size);
		macro = Arrays.copyOf(macro, bodies);
		bodyIndex = Arrays.copyOf(bodyIndex, bodies);
	}

	private void resize(int capacity) {
		symbol = Arrays.copyOf(symbol, capacity);
		kind = Arrays.copyOf(kind, capacity);
		raw = Arrays.copyOf(raw, capacity);
		via = Arrays.copyOf(via, capacity);
	}
}
