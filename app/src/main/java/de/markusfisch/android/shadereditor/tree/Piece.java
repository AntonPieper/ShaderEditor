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

	Piece(Symbols symbols, RawTokens raw, CompiledTokens tokens, Nodes nodes,
			Names names, Parser parser) {
		this.symbols = symbols;
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
