package de.markusfisch.android.shadereditor.tree;

/**
 * A piece of the tree: one or more whole items of the program, with
 * everything that was read of them. It is made once and never
 * changes. Its offsets count from where its text starts and its
 * indices from its own first token, so it stays good when text before
 * it changes.
 */
final class Piece {
	final Symbols symbols;
	/** The tokens of its text; the gap before each belongs to it. */
	final RawTokens raw;
	/** The tokens the compiler parses of it. */
	final CompiledTokens tokens;
	final Nodes nodes;
	final Names names;
	/** Its items: kind (as a node's), first compiled token and end for each. */
	final int[] items;
	/** How many ERROR and MISSING nodes it has. */
	final int errors;
	final int missing;
	/** The #define and #undef lines compiled in it, in order. */
	final Macro[] definitions;
	/** Pairs: a raw token and why it was not read as written ({@link Preprocessor}). */
	final int[] doubts;
	/** How many characters it covers, and how many were looked at to read it. */
	final int length;
	final int reach;
	/**
	 * What it was read with: the version and the open conditionals
	 * before it, and whether it stood at the start of the text. And
	 * what it leaves behind.
	 */
	final int versionBefore;
	final byte[] conditionalsBefore;
	final boolean first;
	final int versionAfter;
	final byte[] conditionalsAfter;

	/**
	 * @param length the characters from where the reading started to
	 *               where the piece ends
	 * @param before the conditionals that were open where it started
	 * @param env    what the directives have set up behind it
	 */
	Piece(Symbols symbols, RawTokens raw, CompiledTokens tokens, Nodes nodes,
			Names names, Parser parser, Preprocessor preprocessor, int length,
			int reach, boolean first, int versionBefore, byte[] before,
			Environment env) {
		this.symbols = symbols;
		this.length = length;
		this.reach = reach;
		this.first = first;
		this.versionBefore = versionBefore;
		conditionalsBefore = before;
		versionAfter = env.version;
		conditionalsAfter = env.conditionals();
		definitions = preprocessor.definitions.toArray(new Macro[0]);
		// A doubt about a token behind the piece is not the piece's.
		Ints doubts = new Ints();
		for (int i = 0; i < preprocessor.doubts.size; i += 2) {
			if (preprocessor.doubts.at[i] < raw.size) {
				doubts.add(preprocessor.doubts.at[i]);
				doubts.add(preprocessor.doubts.at[i + 1]);
			}
		}
		this.doubts = doubts.toArray();
		raw.trim();
		tokens.trim();
		nodes.trim();
		names.trim();
		this.raw = raw;
		this.tokens = tokens;
		this.nodes = nodes;
		this.names = names;
		items = parser.items.toArray();
		errors = parser.errors;
		missing = parser.missing;
	}

	/** How a compiled token is spelled. */
	String spelling(int token) {
		return symbols.name(tokens.symbol[token]);
	}
}
