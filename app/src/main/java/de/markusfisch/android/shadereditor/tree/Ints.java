package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/** Ints, one after another. */
final class Ints {
	int size;
	int[] at = new int[8];

	void add(int value) {
		if (size == at.length) {
			at = Arrays.copyOf(at, size * 2);
		}
		at[size++] = value;
	}

	int[] toArray() {
		return Arrays.copyOf(at, size);
	}
}
