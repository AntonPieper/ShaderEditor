package de.markusfisch.android.shadereditor.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The syntax tree of one text: a reading of it that keeps every
 * character, recovers from errors and runs the directives. It never
 * changes; an edit gives another tree.
 *
 * It is a list of pieces, each one or more whole items of the program
 * with everything read of them. A piece ends where the preprocessor
 * says an item may end and the parser, reading the piece's tokens, did
 * not look for more: then the items of the piece are what they would
 * be in a reading of the whole text. What a piece holds depends only
 * on its own text as far as it was looked at, on where it stands (at
 * the start of the text or not) and on what the directives before it
 * have set up. So after an edit every piece for which all of that is
 * as it was is taken over, and only the others are read again: the
 * tree after an edit is the tree a reading from nothing gives.
 *
 * A tree and the trees made of it by edits share one table of
 * symbols: they are read and edited by one thread at a time.
 */
public final class ShaderTree {
	// How far a table of symbols may outgrow the text before a tree
	// is read from nothing, with a new one.
	private static final int SYMBOLS_SLACK = 1 << 16;
	// Up to how many tokens a piece is parsed again at every place
	// where an item may end.
	private static final int PATIENCE = 512;
	// After how many more compiled tokens a piece is parsed even where
	// no item seems to end.
	private static final int STRIDE = 1024;

	final CharSequence text;
	final Symbols symbols;
	final Piece[] pieces;
	/** Where each piece starts in the text, and the text's length last. */
	final int[] starts;
	/** How many pieces were taken over from the tree before. */
	final int reused;
	/** Conditionals that are still open at the end of the text. */
	final int openConditionals;

	private ShaderTree(CharSequence text, Symbols symbols, List<Piece> pieces,
			int reused, int openConditionals) {
		this.text = text;
		this.symbols = symbols;
		this.pieces = pieces.toArray(new Piece[0]);
		this.reused = reused;
		this.openConditionals = openConditionals;
		starts = new int[this.pieces.length + 1];
		for (int i = 0; i < this.pieces.length; ++i) {
			starts[i + 1] = starts[i] + this.pieces[i].length;
		}
	}

	/** What a name means at one of the places where it is compiled. */
	public static final class Meaning {
		public enum Kind {
			/** A declaration, from start to end of the text. */
			DECLARED,
			/** A macro; its #define names it from start to end. */
			MACRO,
			/** A name the language itself has. */
			LANGUAGE,
			/** Nothing before the place declares it. */
			NOT_DECLARED,
			/** A member of a value whose type the text does not say. */
			UNKNOWN_TYPE,
			/** It stands in an ERROR node. */
			NOT_READ,
			/** It stands where nothing is compiled. */
			NOT_COMPILED
		}

		public final Kind kind;
		public final int start;
		public final int end;
		/**
		 * Whether it stands in a conditional that could not be decided,
		 * so that a driver may compile something else there.
		 */
		public final boolean guess;

		Meaning(Kind kind, int start, int end, boolean guess) {
			this.kind = kind;
			this.start = start;
			this.end = end;
			this.guess = guess;
		}
	}

	/**
	 * Whether the text has an ERROR or MISSING node or a directive
	 * that was not read as written: then "not declared" may only mean
	 * that the declaration was not read.
	 */
	public boolean isBroken() {
		for (Piece piece : pieces) {
			if (piece.errors + piece.missing + piece.doubts.length > 0) {
				return true;
			}
		}
		return openConditionals > 0;
	}

	/**
	 * What the name that is written at offset means: once for every
	 * place where it is compiled, since a name in a macro's text is
	 * compiled wherever the macro is invoked. Empty if no name is
	 * written there.
	 */
	public List<Meaning> meanings(int offset) {
		List<Meaning> meanings = new ArrayList<>();
		int k = Math.max(0, pieceAt(offset));
		Piece piece = pieces[k];
		int r = tokenAt(piece.raw, offset - starts[k]);
		if (r > 0 && !isName(piece.raw, r) &&
				piece.raw.end[r - 1] == offset - starts[k]) {
			// Right behind a name, before what follows it.
			--r;
		}
		if (r < 0 || !isName(piece.raw, r)) {
			return meanings;
		}
		boolean guess = piece.raw.is(r, RawTokens.UNDECIDED);
		boolean directive = piece.raw.is(r, RawTokens.DIRECTIVE);
		// The macro whose text it is in, and where in it.
		Macro in = null;
		for (Macro macro : piece.definitions) {
			if (macro.bodyAt >= 0 && r >= macro.bodyAt &&
					r < macro.bodyAt + macro.body.length) {
				in = macro;
			}
		}
		// Every place where it is compiled, in this piece or, out of a
		// macro's text, in any behind it.
		List<Meanings.Declared[]> means = null;
		for (int j = k; j < (in != null ? pieces.length : k + 1); ++j) {
			CompiledTokens tokens = pieces[j].tokens;
			for (int i = 0; i < tokens.size; ++i) {
				int raw = tokens.raw[i];
				if (in == null ? raw != r : raw >= 0 ||
						tokens.bodyIndex[-1 - raw] != r - in.bodyAt ||
						!in.isSpelledAs(tokens.macro[-1 - raw])) {
					continue;
				}
				if (means == null) {
					means = Meanings.ofAll(Arrays.asList(pieces));
				}
				Meanings.Declared d = means.get(j)[i];
				meanings.add(d == null || d == Meanings.LANGUAGE
						? new Meaning(Meaning.Kind.LANGUAGE, 0, 0, guess)
						: d == Meanings.UNDECLARED
						? new Meaning(Meaning.Kind.NOT_DECLARED, 0, 0, guess)
						: d == Meanings.UNKNOWN || d.piece == null
						? new Meaning(Meaning.Kind.UNKNOWN_TYPE, 0, 0, guess)
						: d == Meanings.UNREAD
						? new Meaning(Meaning.Kind.NOT_READ, 0, 0, guess)
						: declared(d, guess));
			}
		}
		if (meanings.isEmpty()) {
			// Not compiled as it is written: a macro's name where it is
			// invoked, defined or asked for; or nothing a compiler
			// sees, as in a branch that is off, in the text of a macro
			// nothing invokes, or an argument its macro leaves out.
			Macro macro = piece.raw.is(r, RawTokens.OFF) ? null
					: macro(piece.raw.symbol[r], k, r, directive);
			int at = macro == null ? 0 : pieceOf(macro);
			meanings.add(macro == null
					? new Meaning(Meaning.Kind.NOT_COMPILED, 0, 0, guess)
					: new Meaning(Meaning.Kind.MACRO,
							starts[at] + pieces[at].raw.start[macro.nameAt],
							starts[at] + pieces[at].raw.end[macro.nameAt],
							guess || macro.guess));
		}
		return meanings;
	}

	private static boolean isName(RawTokens raw, int r) {
		return raw.kind[r] == RawTokens.NAME ||
				raw.kind[r] == RawTokens.GLUED_NAME;
	}

	// The piece that has the character at offset; the last for the end.
	private int pieceAt(int offset) {
		int k = Arrays.binarySearch(starts, 0, pieces.length, offset);
		return k >= 0 ? k : -k - 2;
	}

	// The token of raw that has the character at offset, or that ends
	// there; -1 for none.
	private static int tokenAt(RawTokens raw, int offset) {
		int i = Arrays.binarySearch(raw.start, 0, raw.size, offset);
		i = i >= 0 ? i : -i - 2;
		return i >= 0 && offset <= raw.end[i] ? i : -1;
	}

	private Meaning declared(Meanings.Declared d, boolean guess) {
		int at = Arrays.asList(pieces).indexOf(d.piece);
		Piece piece = pieces[at];
		int raw = piece.tokens.raw[d.token];
		if (raw < 0) {
			// Its name is written in a macro's text: where that is defined.
			Macro used = piece.tokens.macro[-1 - raw];
			int index = piece.tokens.bodyIndex[-1 - raw];
			Macro macro = macro(used.name, at, piece.raw.size, false);
			if (macro == null || !macro.isSpelledAs(used)) {
				return new Meaning(Meaning.Kind.UNKNOWN_TYPE, 0, 0, true);
			}
			at = pieceOf(macro);
			piece = pieces[at];
			raw = macro.bodyAt + index;
		}
		return new Meaning(Meaning.Kind.DECLARED, starts[at] + piece.raw.start[raw],
				starts[at] + piece.raw.end[raw], guess ||
				piece.raw.is(raw, RawTokens.UNDECIDED));
	}

	// The macro a name is before raw token r of piece k, as the
	// #define line has it that its text is written in; null if the
	// name is not defined there. For a directive's own tokens, also
	// the first that follows: a macro's text is read where it is
	// invoked.
	private Macro macro(int name, int k, int r, boolean orLater) {
		for (int j = k; j >= 0; --j) {
			Macro[] definitions = pieces[j].definitions;
			for (int i = definitions.length - 1; i >= 0; --i) {
				Macro macro = definitions[i];
				if (macro.name != name || (j == k && (macro.nameAt > r ||
						(macro.nameAt == r && !macro.isDefined())))) {
					// Another name, a later line, or the #undef asked from.
					continue;
				} else if (macro.isDefined() && macro.bodyAt >= 0) {
					return macro;
				} else if (!macro.isDefined() && !macro.guess) {
					return null;
				}
				// A guess says nothing of where the text is written.
			}
		}
		for (int j = orLater ? k : pieces.length; j < pieces.length; ++j) {
			for (Macro macro : pieces[j].definitions) {
				if (macro.name == name && macro.isDefined() && macro.bodyAt >= 0) {
					return macro;
				}
			}
		}
		return null;
	}

	private int pieceOf(Macro macro) {
		for (int j = 0; j < pieces.length; ++j) {
			if (Arrays.asList(pieces[j].definitions).contains(macro)) {
				return j;
			}
		}
		throw new IllegalStateException();
	}

	/** Reads a text. */
	public static ShaderTree read(CharSequence text) {
		return read(text, new Symbols(), null, 0, 0, 0);
	}

	/**
	 * The tree of the text this one's text has become: its characters
	 * from start to oldEnd are now those from start to newEnd of text.
	 * It is the tree {@link #read} gives of text.
	 */
	public ShaderTree edit(CharSequence text, int start, int oldEnd,
			int newEnd) {
		// A table of symbols never forgets a spelling; when it holds
		// far more than the text, start over with a new one.
		return symbols.characters() > 4 * text.length() + SYMBOLS_SLACK
				? read(text)
				: read(text, symbols, this, start, oldEnd, newEnd);
	}

	/**
	 * As {@link #edit(CharSequence, int, int, int)}, for a text of
	 * which only this is known: it is what this tree's text has become.
	 */
	public ShaderTree edit(CharSequence text) {
		int start = 0;
		int limit = Math.min(text.length(), this.text.length());
		while (start < limit && text.charAt(start) == this.text.charAt(start)) {
			++start;
		}
		int oldEnd = this.text.length();
		int newEnd = text.length();
		while (oldEnd > start && newEnd > start &&
				text.charAt(newEnd - 1) == this.text.charAt(oldEnd - 1)) {
			--oldEnd;
			--newEnd;
		}
		return edit(text, start, oldEnd, newEnd);
	}

	private static ShaderTree read(CharSequence text, Symbols symbols,
			ShaderTree old, int start, int oldEnd, int newEnd) {
		int length = text.length();
		int delta = newEnd - oldEnd;
		List<Piece> pieces = new ArrayList<>();
		Environment env = new Environment();
		// What the directives of the old tree had set up at the same
		// place, and the macros that may differ between the two.
		Environment oldEnv = new Environment();
		Set<Integer> touched = new HashSet<>();
		int passed = 0;
		int reused = 0;
		int at = 0;
		do {
			// Where this place was in the old text, if it is outside
			// what changed.
			int was = old == null ? -1 : at >= newEnd ? at - delta
					: at < start ? at : -1;
			Piece piece = null;
			if (was >= 0) {
				for (; passed < old.pieces.length && old.starts[passed] < was;
						++passed) {
					for (Macro macro : old.pieces[passed].definitions) {
						oldEnv.define(macro);
						touched.add(macro.name);
					}
				}
				if (passed < old.pieces.length && old.starts[passed] == was) {
					Piece candidate = old.pieces[passed];
					boolean unchanged = was >= oldEnd ||
							was + candidate.reach <= start;
					if (unchanged && candidate.first == (at == 0) &&
							candidate.versionBefore == env.version &&
							env.has(candidate.conditionalsBefore) &&
							sameMacros(env, oldEnv, touched)) {
						if (was >= oldEnd) {
							// Behind what changed, and the directives
							// have set up what they had: from here on
							// the old tree is the tree.
							pieces.addAll(Arrays.asList(old.pieces).subList(
									passed, old.pieces.length));
							return new ShaderTree(text, symbols, pieces, reused +
									old.pieces.length - passed, old.openConditionals);
						}
						piece = candidate;
						oldEnv.pass(piece);
						++passed;
						++reused;
					}
				}
			}
			if (piece == null) {
				piece = readPiece(text, at, symbols, env);
				for (Macro macro : piece.definitions) {
					touched.add(macro.name);
				}
			} else {
				env.pass(piece);
			}
			pieces.add(piece);
			at += piece.length;
		} while (at < length);
		return new ShaderTree(text, symbols, pieces, reused, env.open);
	}

	// Whether every macro that either reading has defined or undefined
	// since they last agreed is the same in both.
	private static boolean sameMacros(Environment env, Environment oldEnv,
			Set<Integer> touched) {
		for (Integer name : new ArrayList<>(touched)) {
			Macro now = env.known(name);
			Macro then = oldEnv.known(name);
			boolean same = now != null && now.isDefined()
					? now.expandsAs(then)
					: (then == null || !then.isDefined()) &&
							(now != null && now.guess) ==
									(then != null && then.guess);
			if (!same) {
				return false;
			}
			touched.remove(name);
		}
		return true;
	}

	// Reads the piece that starts at a place: the items up to the first
	// place where the parser has read whole items and looked at
	// nothing behind what was compiled, or to the end of the text.
	private static Piece readPiece(CharSequence text, int at, Symbols symbols,
			Environment env) {
		int versionBefore = env.version;
		byte[] before = env.conditionals();
		RawTokens raw = new RawTokens();
		CompiledTokens tokens = new CompiledTokens();
		Lexer lexer = new Lexer(text, at, symbols, raw);
		Preprocessor preprocessor = new Preprocessor(lexer, raw, tokens, env,
				symbols);
		// Tokens are parsed where an item may end, and at the latest
		// every STRIDE tokens, since in a block whose brace is not
		// closed no item seems to end. Many tokens are parsed again
		// only when there are half as many more: so that the work
		// stays in proportion to the text.
		int enough = 0;
		while (true) {
			preprocessor.stopAt = Math.max(enough, tokens.size + STRIDE);
			boolean more = preprocessor.run();
			if (more && tokens.size < enough) {
				continue;
			}
			Nodes nodes = new Nodes();
			Names names = new Names();
			Parser parser = new Parser(tokens, env.version, nodes, names);
			parser.parse(tokens.size);
			// The last raw token of the piece.
			int last = raw.size - 1;
			if (more) {
				last = -1;
				for (int count = parser.settled; count > 0 && last < 0; --count) {
					last = end(parser.items.at[count * 3 - 1], raw, tokens,
							preprocessor);
					if (last >= 0) {
						parser.keep(count);
					}
				}
				if (last < 0) {
					enough = tokens.size < PATIENCE ? 0
							: tokens.size + tokens.size / 2;
					continue;
				}
				tokens.truncate(parser.items.at[parser.items.size - 1]);
				raw.truncate(last + 1);
			}
			int length = more ? raw.end[last] : text.length() - at;
			return new Piece(symbols, raw, tokens, nodes, names, parser,
					preprocessor, length, more ? Math.max(length, lexer.reach())
					: length + 1, at == 0, versionBefore, before, env);
		}
	}

	// The raw token a piece ends with if it ends before compiled token
	// end, or -1 if it cannot end there: what stands behind must be
	// readable from there on as if nothing had been read of it.
	private static int end(int end, RawTokens raw, CompiledTokens tokens,
			Preprocessor preprocessor) {
		int last;
		if (end == tokens.size) {
			last = preprocessor.read() - 1;
		} else if (tokens.via[end - 1] < 0 && tokens.raw[end - 1] >= 0) {
			last = tokens.raw[end - 1];
		} else {
			// It ends inside what a macro expands to.
			return -1;
		}
		// No directive behind it may have been run, and the lexer must
		// be able to start there: behind a ";" or "}", or before a gap.
		boolean closes = raw.symbol[last] == Words.SEMICOLON ||
				raw.symbol[last] == Words.RIGHT_BRACE;
		return preprocessor.directiveAt < end && (closes ||
				(last + 1 < raw.size && raw.is(last + 1, RawTokens.SPACED)))
				? last : -1;
	}
}
