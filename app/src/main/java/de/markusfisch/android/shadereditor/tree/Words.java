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
		int[] level = new int[ALL.size() + 16];
		for (int i = 0; i < BINARY.length; ++i) {
			for (String operator : BINARY[i]) {
				level[symbol(operator)] = i + 1;
			}
		}
		LEVEL = level;
	}

	// The words of the language the parser knows; see cls.
	/** Class: a type of the language. */
	static final byte TYPE = 1;
	/** Class: a qualifier or a precision. */
	static final byte QUALIFIER = 2;
	/** Class: another word that is no name. */
	static final byte KEYWORD = 4;
	/** Class: a word only an item outside functions starts with. */
	static final byte TOP = 8;

	static final int STRUCT = word("struct");
	static final int PRECISION = word("precision");
	static final int FOR = word("for");
	static final int WHILE = word("while");
	static final int DO = word("do");
	static final int RETURN = word("return");
	static final int BREAK = word("break");
	static final int CONTINUE = word("continue");
	static final int DISCARD = word("discard");
	static final int SWITCH = word("switch");
	static final int CASE = word("case");
	static final int DEFAULT = word("default");
	static final int LAYOUT = word("layout");
	static final int VOID = word("void");
	static final int LENGTH = word("length");
	static final int DOT = word(".");
	static final int ASSIGN = word("=");
	static final int QUESTION = word("?");
	static final int COLON = word(":");
	static final int INCREMENT = word("++");
	static final int DECREMENT = word("--");
	static final int TRUE = word("true");
	static final int FALSE = word("false");
	private static final byte[] CLASS_100;
	private static final byte[] CLASS_300;
	private static final boolean[] ASSIGNS;
	private static final byte[] PRECEDENCE;

	static {
		String types = "void float int bool vec2 vec3 vec4 ivec2 ivec3 ivec4 " +
				"bvec2 bvec3 bvec4 mat2 mat3 mat4 sampler2D samplerCube " +
				"samplerExternalOES";
		String types300 = " uint uvec2 uvec3 uvec4 mat2x2 mat2x3 mat2x4 " +
				"mat3x2 mat3x3 mat3x4 mat4x2 mat4x3 mat4x4 sampler3D " +
				"sampler2DShadow samplerCubeShadow sampler2DArray " +
				"sampler2DArrayShadow isampler2D isampler3D isamplerCube " +
				"isampler2DArray usampler2D usampler3D usamplerCube " +
				"usampler2DArray";
		String keywords = "precision struct if else for while do return " +
				"break continue discard true false";
		String all = types + types300 + " " + keywords + " switch case " +
				"default const in out inout uniform attribute varying " +
				"invariant highp mediump lowp centroid flat smooth layout " +
				"|| ^^ && | ^ & == != < > <= >= << >> + - * / % " +
				"= += -= *= /= %= <<= >>= &= |= ^=";
		for (String word : all.split(" ")) {
			symbol(word);
		}
		CLASS_100 = new byte[ALL.size()];
		CLASS_300 = new byte[ALL.size()];
		mark(CLASS_100, types, TYPE);
		mark(CLASS_100, "const in out inout uniform attribute varying " +
				"invariant highp mediump lowp", QUALIFIER);
		mark(CLASS_100, "uniform attribute varying", TOP);
		mark(CLASS_100, keywords, KEYWORD);
		mark(CLASS_300, types + types300, TYPE);
		mark(CLASS_300, "const in out inout uniform centroid flat smooth " +
				"invariant highp mediump lowp layout", QUALIFIER);
		mark(CLASS_300, "uniform in out centroid flat smooth layout", TOP);
		mark(CLASS_300, keywords + " switch case default", KEYWORD);
		ASSIGNS = new boolean[ALL.size()];
		for (String operator : "= += -= *= /= %= <<= >>= &= |= ^=".split(" ")) {
			ASSIGNS[symbol(operator)] = true;
		}
		PRECEDENCE = new byte[ALL.size()];
		String[] levels = {"||", "^^", "&&", "|", "^", "&", "== !=",
				"< > <= >=", "<< >>", "+ -", "* / %"};
		for (int i = 0; i < levels.length; ++i) {
			for (String operator : levels[i].split(" ")) {
				PRECEDENCE[symbol(operator)] = (byte) (i + 1);
			}
		}
	}

	private Words() {
	}

	/** The classes of a symbol in a version of the language; 0 for a name. */
	static int cls(int symbol, int version) {
		byte[] classes = version >= 300 ? CLASS_300 : CLASS_100;
		return symbol < classes.length ? classes[symbol] : 0;
	}

	static boolean assigns(int symbol) {
		return symbol >= 0 && symbol < ASSIGNS.length && ASSIGNS[symbol];
	}

	/** How tightly a binary operator of an expression binds, from 1; else 0. */
	static int precedence(int symbol) {
		return symbol >= 0 && symbol < PRECEDENCE.length
				? PRECEDENCE[symbol] : 0;
	}

	private static void mark(byte[] classes, String words, byte cls) {
		for (String word : words.trim().split(" ")) {
			classes[symbol(word)] |= cls;
		}
	}

	private static int symbol(String spelling) {
		int at = ALL.indexOf(spelling);
		return at >= 0 ? at : word(spelling);
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
