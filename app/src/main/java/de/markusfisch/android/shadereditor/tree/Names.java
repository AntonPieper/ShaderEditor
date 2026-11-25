package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/**
 * What a piece of the tree does with names, in the order of the text:
 * the scopes it opens and closes, what it declares and what it uses.
 * The parser writes this without knowing what any name means; what
 * each means follows from it and from what is declared before the
 * piece ({@link Meanings}).
 *
 * Where a step needs the type of a value, it names the step that has
 * it: a use (the variable's type), a call (what the function returns,
 * or the struct it constructs), a member (the field's type), a type.
 * NO_STRUCT stands for a value of a type of the language.
 */
final class Names {
	/** A scope opens; a is the step that closes it. */
	static final byte OPEN = 1;
	static final byte CLOSE = 2;
	/** Token a declares a variable; b is the step of its type. */
	static final byte VARIABLE = 3;
	/** Token a is the name of a function's head; b the step of what it returns. */
	static final byte FUNCTION = 4;
	/**
	 * A struct with the name of token a, or -1 for none: FIELD steps
	 * follow up to END. The step is also the struct as a type.
	 */
	static final byte STRUCT = 5;
	/** As STRUCT, for the block of token a behind uniform, in or out. */
	static final byte BLOCK = 6;
	/** Token a declares a field of the struct or block that is open; b is the step of its type. */
	static final byte FIELD = 7;
	/** The fields end. For a block, b is 1 if it has no instance: its fields are variables. */
	static final byte END = 8;
	/** Token a is a name where a type stands. */
	static final byte TYPE = 9;
	/** Token a is a name used as a value. */
	static final byte VALUE = 10;
	/** Token a is a name that is called. */
	static final byte CALL = 11;
	/** Token a selects a member of the value of step b. */
	static final byte MEMBER = 12;
	/** The value of step a or step b, which are of one type: of "?:". */
	static final byte EITHER = 13;
	/** Token a is a name inside an ERROR node. */
	static final byte SKIPPED = 14;

	/** In place of a step: a type of the language. */
	static final int NO_STRUCT = -1;

	int size;
	byte[] step = new byte[64];
	int[] a = new int[64];
	int[] b = new int[64];

	int add(byte step, int a, int b) {
		if (size == this.step.length) {
			resize(Math.max(16, size * 2));
		}
		this.step[size] = step;
		this.a[size] = a;
		this.b[size] = b;
		return size++;
	}

	void trim() {
		resize(size);
	}

	private void resize(int capacity) {
		step = Arrays.copyOf(step, capacity);
		a = Arrays.copyOf(a, capacity);
		b = Arrays.copyOf(b, capacity);
	}
}
