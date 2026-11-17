package de.markusfisch.android.shadereditor.syntax;

import java.util.Arrays;
import java.util.Collections;

import de.markusfisch.android.shadereditor.syntax.Knowledge.Kind;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * The locals and parameters of the bodies of one text, and where each
 * can be seen: a table of flat arrays, so that a text with thousands of
 * locals keeps six values for each and no object.
 *
 * A local is added where it is declared, in the order of the text, with
 * its scope still open; the scope is closed when its brace is. Once
 * {@link #freeze} was called nothing changes any more, and
 * {@link #find} tells what a name means at a place: the local of that
 * name declared last before it whose scope holds it. Scopes lie inside
 * each other, so that is the one declared last before the place or one
 * of those it hides: a lookup walks outward, at most as deep as scopes
 * nest, however often the name is declared.
 */
final class GlslLocals {
	/** No locals. */
	static final GlslLocals NONE = new GlslLocals();

	static {
		NONE.freeze();
	}

	private String[] names = new String[16];
	private String[] types = new String[16];
	// Where the name stands, from where it is visible, and where its
	// scope ends, -1 while it is open.
	private int[] starts = new int[16];
	private int[] ends = new int[16];
	private int[] froms = new int[16];
	private int[] tos = new int[16];
	private Kind[] kinds = new Kind[16];
	private int size;
	// The locals by name, those of one name in the order of the text;
	// and for each local the one of its name whose scope it was declared
	// in, which it hides, or -1.
	private int[] byName;
	private int[] hides;

	int size() {
		return size;
	}

	/**
	 * Add a local whose name stands from start to end and that is
	 * visible from from on; its scope is open.
	 */
	void add(String name, Kind kind, String type, int start, int end,
			int from) {
		if (size == names.length) {
			int capacity = size * 2;
			names = Arrays.copyOf(names, capacity);
			types = Arrays.copyOf(types, capacity);
			starts = Arrays.copyOf(starts, capacity);
			ends = Arrays.copyOf(ends, capacity);
			froms = Arrays.copyOf(froms, capacity);
			tos = Arrays.copyOf(tos, capacity);
			kinds = Arrays.copyOf(kinds, capacity);
		}
		names[size] = name;
		kinds[size] = kind;
		types[size] = type;
		starts[size] = start;
		ends[size] = end;
		froms[size] = from;
		tos[size] = -1;
		++size;
	}

	/**
	 * Close the scopes that are still open of the locals from the one
	 * at first on: they end at offset.
	 */
	void close(int first, int offset) {
		for (int i = Math.max(0, first); i < size; ++i) {
			if (tos[i] < 0) {
				tos[i] = offset;
			}
		}
	}

	/** Return the locals from the one at from on whose scope is open. */
	int[] open(int from) {
		int[] open = new int[Math.max(0, size - from)];
		int count = 0;
		for (int i = Math.max(0, from); i < size; ++i) {
			if (tos[i] < 0) {
				open[count++] = i;
			}
		}
		return Arrays.copyOf(open, count);
	}

	/** Open again the scopes of these locals: their end is taken back. */
	void reopen(int[] locals) {
		for (int local : locals) {
			tos[local] = -1;
		}
	}

	/** Close every scope still open and sort for {@link #find}. */
	void freeze() {
		close(0, Integer.MAX_VALUE);
		Integer[] sorted = new Integer[size];
		for (int i = 0; i < size; ++i) {
			sorted[i] = i;
		}
		// The sort is stable, so one name keeps the order of the text.
		Arrays.sort(sorted, (a, b) -> names[a].compareTo(names[b]));
		byName = new int[size];
		hides = new int[size];
		// Of one name, those whose scope is open where the next one is
		// declared, innermost last.
		int[] open = new int[size];
		int depth = 0;
		for (int i = 0; i < size; ++i) {
			int local = byName[i] = sorted[i];
			if (i == 0 || !names[local].equals(names[sorted[i - 1]])) {
				depth = 0;
			}
			while (depth > 0 && tos[open[depth - 1]] <= starts[local]) {
				--depth;
			}
			hides[local] = depth > 0 ? open[depth - 1] : -1;
			open[depth++] = local;
		}
	}

	/**
	 * Return the local name means at offset, or -1: the one declared
	 * last whose name stands there or whose scope holds the offset.
	 */
	int find(CharSequence name, int offset) {
		// The last of that name that is declared at or before offset.
		int low = 0;
		int high = size - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			int local = byName[mid];
			int d = compare(names[local], name);
			if (d < 0 || (d == 0 && starts[local] <= offset)) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		if (high < 0 || compare(names[byName[high]], name) != 0) {
			return -1;
		}
		// It, or one it hides: every scope that holds offset and began
		// before it holds it too.
		for (int local = byName[high]; local >= 0; local = hides[local]) {
			if ((offset >= starts[local] && offset <= ends[local]) ||
					(offset >= froms[local] && offset < tos[local])) {
				return local;
			}
		}
		return -1;
	}

	/** Return where the name of a local stands. */
	int start(int local) {
		return starts[local];
	}

	/** Return a local as a symbol: its type and its name. */
	Symbol symbol(int local) {
		String type = types[local];
		return new Symbol(names[local], kinds[local], (type == null ? ""
				: type + " ") + names[local], null, null, starts[local],
				ends[local], starts[local], Math.max(ends[local],
				kinds[local] == Kind.PARAMETER ? 0 : froms[local]),
				Collections.emptyList(), type);
	}

	private static int compare(String name, CharSequence word) {
		int length = Math.min(name.length(), word.length());
		for (int i = 0; i < length; ++i) {
			int d = name.charAt(i) - word.charAt(i);
			if (d != 0) {
				return d;
			}
		}
		return name.length() - word.length();
	}
}
