package de.markusfisch.android.shadereditor.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * The words every {@link Symbols} table starts with, each with the
 * number it has there.
 */
final class Words {
	private static final List<String> ALL = new ArrayList<>();

	static final int HASH = word("#");
	static final int PASTE = word("##");
	static final int LEFT_PAREN = word("(");
	static final int RIGHT_PAREN = word(")");
	static final int LEFT_BRACE = word("{");
	static final int RIGHT_BRACE = word("}");
	static final int LEFT_BRACKET = word("[");
	static final int RIGHT_BRACKET = word("]");
	static final int SEMICOLON = word(";");
	static final int COMMA = word(",");

	private Words() {
	}

	static List<String> all() {
		return ALL;
	}

	private static int word(String spelling) {
		ALL.add(spelling);
		return ALL.size() - 1;
	}
}
