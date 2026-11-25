package de.markusfisch.android.shadereditor.tree;

import java.util.List;

/** A whole text read as one piece, with what its names mean, for tests. */
final class Whole {
	final String text;
	final Compiled compiled;
	final Parser parser;
	final Piece piece;
	final Meanings.Declared[] means;

	Whole(String text) {
		this.text = text;
		compiled = new Compiled(text);
		Nodes nodes = new Nodes();
		Names names = new Names();
		parser = new Parser(compiled.tokens, compiled.env.version, nodes, names);
		parser.parse(compiled.tokens.size);
		piece = new Piece(compiled.symbols, compiled.raw, compiled.tokens, nodes,
				names, parser);
		means = Meanings.of(List.of(piece));
	}

	/** Where compiled token i is written in the text. */
	int at(int i) {
		int raw = piece.tokens.raw[i];
		if (raw < 0) {
			int k = -1 - raw;
			raw = piece.tokens.macro[k].bodyAt + piece.tokens.bodyIndex[k];
		}
		return piece.raw.start[raw];
	}

	/** Where the writing of compiled token i ends. */
	int end(int i) {
		int raw = piece.tokens.raw[i];
		if (raw < 0) {
			int k = -1 - raw;
			raw = piece.tokens.macro[k].bodyAt + piece.tokens.bodyIndex[k];
		}
		return piece.raw.end[raw];
	}

	/** Where compiled token i counts as standing: where its outermost macro is invoked. */
	int place(int i) {
		int via = piece.tokens.via[i];
		return via >= 0 ? piece.raw.start[via] : at(i);
	}

	/** The line of an offset, from 1. */
	int line(int offset) {
		int line = 1;
		for (int i = 0; i < offset; ++i) {
			line += text.charAt(i) == '\n' ? 1 : 0;
		}
		return line;
	}

	/**
	 * What every name means, one per line: "name line -> line of its
	 * declaration", or what stands in place of one.
	 */
	String meanings() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < means.length; ++i) {
			Meanings.Declared d = means[i];
			if (d == null) {
				continue;
			}
			sb.append(piece.spelling(i)).append(' ').append(line(at(i)))
					.append(" -> ").append(d == Meanings.LANGUAGE ? "language"
							: d == Meanings.UNDECLARED ? "undeclared"
							: d == Meanings.UNKNOWN ? "unknown"
							: d == Meanings.UNREAD ? "unread"
							: d == Meanings.REDECLARED ? "redeclared"
							: d.piece == null ? "unnamed"
							: line(at(d.token)) + (d.orLanguage ? " or language" : ""))
					.append('\n');
		}
		return sb.toString();
	}
}
