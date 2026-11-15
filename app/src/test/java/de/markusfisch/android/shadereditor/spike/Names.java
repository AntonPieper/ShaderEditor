package de.markusfisch.android.shadereditor.spike;

import java.util.Arrays;

/**
 * SPIKE, throwaway: names as numbers. A word of a text gets one number
 * however often it appears, without a String per appearance; the words
 * of the language have the same numbers in every text.
 */
final class Names {
	private static final Names SEED = new Names();

	// Words of the language, with fixed numbers.
	static final int STRUCT = seed("struct");
	static final int IF = seed("if");
	static final int ELSE = seed("else");
	static final int FOR = seed("for");
	static final int WHILE = seed("while");
	static final int DO = seed("do");
	static final int RETURN = seed("return");
	static final int BREAK = seed("break");
	static final int CONTINUE = seed("continue");
	static final int DISCARD = seed("discard");
	static final int SWITCH = seed("switch");
	static final int CASE = seed("case");
	static final int DEFAULT = seed("default");
	static final int CONST = seed("const");
	static final int IN = seed("in");
	static final int OUT = seed("out");
	static final int INOUT = seed("inout");
	static final int UNIFORM = seed("uniform");
	static final int ATTRIBUTE = seed("attribute");
	static final int VARYING = seed("varying");
	static final int CENTROID = seed("centroid");
	static final int FLAT = seed("flat");
	static final int SMOOTH = seed("smooth");
	static final int LAYOUT = seed("layout");
	static final int INVARIANT = seed("invariant");
	static final int HIGHP = seed("highp");
	static final int MEDIUMP = seed("mediump");
	static final int LOWP = seed("lowp");
	static final int PRECISION = seed("precision");
	static final int TRUE = seed("true");
	static final int FALSE = seed("false");
	static final int LAST_KEYWORD = FALSE;
	// Directive names.
	static final int DEFINE = seed("define");
	static final int UNDEF = seed("undef");
	static final int IFDEF = seed("ifdef");
	static final int IFNDEF = seed("ifndef");
	static final int ELIF = seed("elif");
	static final int ENDIF = seed("endif");
	static final int INCLUDE = seed("include");
	static final int PRAGMA = seed("pragma");
	static final int VERSION = seed("version");
	static final int EXTENSION = seed("extension");
	static final int LENGTH = seed("length");
	static final int DEFINED = seed("defined");
	static final int GL_ES = seed("GL_ES");
	static final int SHADER_EDITOR = seed("SHADER_EDITOR");
	static final int GL_FRAGMENT_PRECISION_HIGH =
			seed("GL_FRAGMENT_PRECISION_HIGH");

	private String[] strings = new String[256];
	private int[] starts = new int[256];
	private int[] ends = new int[256];
	private int[] hashes = new int[256];
	private int[] table = new int[1024];
	// How often each word was asked for: how often the text has it.
	private int[] counts = new int[256];
	private int size;
	private final CharSequence source;

	private Names() {
		source = "";
	}

	/** The words of the language, and those of source as they come. */
	Names(CharSequence source) {
		this.source = source;
		Types.touch();
		Builtins.touch();
		strings = SEED.strings.clone();
		starts = SEED.starts.clone();
		ends = SEED.ends.clone();
		hashes = SEED.hashes.clone();
		table = SEED.table.clone();
		counts = new int[strings.length];
		size = SEED.size;
	}

	/** Give word a fixed number; only while classes load. */
	static synchronized int seed(String word) {
		return SEED.intern(word, 0, word.length(), word);
	}

	int size() {
		return size;
	}

	/** How often the text has the word with this number. */
	int count(int id) {
		return counts[id];
	}

	/** Return the number of the word [start, end) of the source. */
	int intern(int start, int end) {
		return intern(source, start, end, null);
	}

	/** Return the number of a word that need not be in the source. */
	int intern(String word) {
		return intern(word, 0, word.length(), word);
	}

	/** Return the number of word, or -1 if the text has no such word. */
	int find(String word) {
		int hash = hash(word, 0, word.length());
		int mask = table.length - 1;
		for (int slot = hash & mask; ; slot = (slot + 1) & mask) {
			int id = table[slot] - 1;
			if (id < 0) {
				return -1;
			} else if (hashes[id] == hash && equal(id, word, 0,
					word.length())) {
				return id;
			}
		}
	}

	String name(int id) {
		if (id < 0) {
			return "?";
		}
		String s = strings[id];
		if (s == null) {
			s = source.subSequence(starts[id], ends[id]).toString();
			strings[id] = s;
		}
		return s;
	}

	private int intern(CharSequence text, int start, int end, String word) {
		int hash = hash(text, start, end);
		int mask = table.length - 1;
		int slot = hash & mask;
		for (; ; slot = (slot + 1) & mask) {
			int id = table[slot] - 1;
			if (id < 0) {
				break;
			} else if (hashes[id] == hash && equal(id, text, start, end)) {
				++counts[id];
				return id;
			}
		}
		if (size == strings.length) {
			int capacity = size * 2;
			counts = Arrays.copyOf(counts, capacity);
			strings = Arrays.copyOf(strings, capacity);
			starts = Arrays.copyOf(starts, capacity);
			ends = Arrays.copyOf(ends, capacity);
			hashes = Arrays.copyOf(hashes, capacity);
		}
		int id = size++;
		counts[id] = 1;
		strings[id] = word;
		starts[id] = start;
		ends[id] = end;
		hashes[id] = hash;
		table[slot] = id + 1;
		if (size * 2 > table.length) {
			rehash();
		}
		return id;
	}

	private void rehash() {
		int[] grown = new int[table.length * 2];
		int mask = grown.length - 1;
		for (int id = 0; id < size; ++id) {
			int slot = hashes[id] & mask;
			while (grown[slot] != 0) {
				slot = (slot + 1) & mask;
			}
			grown[slot] = id + 1;
		}
		table = grown;
	}

	private boolean equal(int id, CharSequence text, int start, int end) {
		String s = strings[id];
		int length = end - start;
		if (s != null) {
			if (s.length() != length) {
				return false;
			}
			for (int i = 0; i < length; ++i) {
				if (s.charAt(i) != text.charAt(start + i)) {
					return false;
				}
			}
			return true;
		}
		if (ends[id] - starts[id] != length) {
			return false;
		}
		for (int i = 0, a = starts[id]; i < length; ++i) {
			if (source.charAt(a + i) != text.charAt(start + i)) {
				return false;
			}
		}
		return true;
	}

	private static int hash(CharSequence text, int start, int end) {
		int h = 0;
		for (int i = start; i < end; ++i) {
			h = h * 31 + text.charAt(i);
		}
		return h ^ (h >>> 15);
	}
}
