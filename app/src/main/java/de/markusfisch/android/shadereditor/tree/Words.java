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
	// The directives that may have a quoted path.
	static final int INCLUDE = word("include");
	static final int PRAGMA = word("pragma");
	static final int SE = word("se");

	// The directives and what their conditions are made of.
	static final int DEFINE = word("define");
	static final int UNDEF = word("undef");
	static final int IF = word("if");
	static final int IFDEF = word("ifdef");
	static final int IFNDEF = word("ifndef");
	static final int ELIF = word("elif");
	static final int ELSE = word("else");
	static final int ENDIF = word("endif");
	static final int VERSION = word("version");
	static final int DEFINED = word("defined");
	static final int GL_ES = word("GL_ES");
	static final int VERSION_NUMBER = word("__VERSION__");
	static final int LINE_NUMBER = word("__LINE__");
	static final int FILE_NUMBER = word("__FILE__");
	static final int PRECISION_HIGH = word("GL_FRAGMENT_PRECISION_HIGH");
	static final int ZERO = word("0");
	static final int ONE = word("1");
	static final int NOT = word("!");
	static final int COMPLEMENT = word("~");
	static final int PLUS = word("+");
	static final int MINUS = word("-");
	// The binary operators of a condition, the loosest first.
	private static final String[][] BINARY = {{"||"}, {"&&"}, {"|"}, {"^"},
			{"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"},
			{"+", "-"}, {"*", "/", "%"}};
	static final int LEVELS = BINARY.length;
	private static final int[] LEVEL;

	static {
		int[] level = new int[64];
		for (int i = 0; i < BINARY.length; ++i) {
			for (String operator : BINARY[i]) {
				int at = ALL.indexOf(operator);
				level[at >= 0 ? at : word(operator)] = i + 1;
			}
		}
		LEVEL = level;
	}

	private Words() {
	}

	static List<String> all() {
		return ALL;
	}

	/** How tightly a binary operator of a condition binds, from 1; 0 for any other symbol. */
	static int level(int symbol) {
		return symbol < LEVEL.length ? LEVEL[symbol] : 0;
	}

	private static int word(String spelling) {
		ALL.add(spelling);
		return ALL.size() - 1;
	}
}
