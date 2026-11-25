package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/**
 * The nodes of a piece of the tree. A node is a kind and the compiled
 * tokens it covers, [first, end); a node's children come before it,
 * so the last node of an item is the item. An ERROR node holds tokens
 * that fit nowhere; a MISSING node is empty and stands where a token
 * should be.
 */
final class Nodes {
	static final byte FUNCTION = 1;
	static final byte PROTOTYPE = 2;
	static final byte DECLARATION = 3;
	static final byte PRECISION = 4;
	static final byte STRUCT = 5;
	static final byte FIELD = 6;
	static final byte PARAMETER = 7;
	static final byte QUALIFIERS = 8;
	static final byte TYPE = 9;
	static final byte DECLARATOR = 10;
	static final byte BLOCK = 11;
	static final byte IF = 12;
	static final byte LOOP = 13;
	static final byte SWITCH = 14;
	static final byte LABEL = 15;
	static final byte JUMP = 16;
	static final byte EXPRESSION_STATEMENT = 17;
	static final byte CALL = 18;
	static final byte INDEX = 19;
	static final byte MEMBER = 20;
	static final byte BINARY = 21;
	static final byte UNARY = 22;
	static final byte TERNARY = 23;
	static final byte ASSIGNMENT = 24;
	static final byte PARENTHESES = 25;
	static final byte NAME = 26;
	static final byte LITERAL = 27;
	static final byte ERROR = 28;
	static final byte MISSING = 29;

	int size;
	byte[] kind = new byte[64];
	int[] first = new int[64];
	int[] end = new int[64];

	void add(byte kind, int first, int end) {
		if (size == this.kind.length) {
			resize(Math.max(16, size * 2));
		}
		this.kind[size] = kind;
		this.first[size] = first;
		this.end[size++] = end;
	}

	void trim() {
		resize(size);
	}

	private void resize(int capacity) {
		kind = Arrays.copyOf(kind, capacity);
		first = Arrays.copyOf(first, capacity);
		end = Arrays.copyOf(end, capacity);
	}
}
