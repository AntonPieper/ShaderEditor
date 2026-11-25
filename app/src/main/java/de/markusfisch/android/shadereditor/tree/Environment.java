package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/**
 * What the directives before a place have set up: the version, the
 * macros in force and the conditionals that are open. A reading
 * carries it through the text.
 */
final class Environment {
	// What is known of an open conditional.
	/** The code around it is compiled. */
	static final byte AROUND = 1;
	/** An arm of it is or was compiled. */
	static final byte TAKEN = 2;
	/** The arm being read is compiled. */
	static final byte COMPILING = 4;
	/** Its #else was read. */
	static final byte ELSE = 8;
	/** A condition of it could not be decided; see {@link Preprocessor}. */
	static final byte UNDECIDED = 16;

	/** 100 until a #version line says another. */
	int version = 100;
	/** How many conditionals are open, and how many of them are undecided. */
	int open;
	int undecided;
	private byte[] conditionals = new byte[8];
	// By symbol.
	private Macro[] macros = new Macro[256];

	boolean compiling() {
		return open == 0 || (conditionals[open - 1] & COMPILING) != 0;
	}

	/** The macro a name is defined as, or null. */
	Macro macro(int symbol) {
		Macro macro = known(symbol);
		return macro != null && macro.isDefined() ? macro : null;
	}

	/** What the last #define or #undef of a name left, or null. */
	Macro known(int symbol) {
		return symbol < macros.length ? macros[symbol] : null;
	}

	/** Takes a #define line, or a #undef as {@link Macro#undefined}. */
	void define(Macro macro) {
		if (macro.name >= macros.length) {
			macros = Arrays.copyOf(macros, Math.max(macro.name + 1,
					macros.length * 2));
		}
		macros[macro.name] = macro;
	}

	/** The innermost open conditional. */
	byte top() {
		return conditionals[open - 1];
	}

	void push(int conditional) {
		if (open == conditionals.length) {
			conditionals = Arrays.copyOf(conditionals, open * 2);
		}
		conditionals[open++] = (byte) conditional;
		undecided += (conditional & UNDECIDED) != 0 ? 1 : 0;
	}

	void replace(int conditional) {
		undecided += ((conditional & UNDECIDED) != 0 ? 1 : 0) -
				((top() & UNDECIDED) != 0 ? 1 : 0);
		conditionals[open - 1] = (byte) conditional;
	}

	void pop() {
		undecided -= (top() & UNDECIDED) != 0 ? 1 : 0;
		--open;
	}

	/** The open conditionals, the outermost first. */
	byte[] conditionals() {
		return Arrays.copyOf(conditionals, open);
	}
}
