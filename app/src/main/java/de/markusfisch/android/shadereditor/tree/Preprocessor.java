package de.markusfisch.android.shadereditor.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Layer 1 of the tree: runs the directives of a text as the compiler
 * does. Conditionals are decided, macros are expanded, and out come
 * the tokens the compiler parses, each with where it is written.
 *
 * It never stops and never refuses. What it cannot do as written it
 * does in a stated way and writes down as a doubt:
 *
 * A condition it cannot decide (a name only the driver knows, as
 * GL_OES_standard_derivatives, or something no condition may have)
 * counts as false, so an #else is compiled. Every token of such a
 * conditional is marked UNDECIDED: what is compiled there is a guess.
 * So is every name an arm of it defines or undefines, compiled or
 * not: a condition that asks for one cannot be decided either, and
 * expanding one is a doubt.
 *
 * An invocation whose parenthesis is not closed before the next
 * directive or the end of the text is a plain name. One with the
 * wrong number of arguments is expanded with what it has.
 *
 * A macro whose text has "#" or "##" expands to nothing; GLSL ES has
 * neither operator.
 *
 * Expansion is that of the C preprocessor: arguments are expanded
 * before they are put in, the result is read again together with what
 * follows it, and a macro is not expanded inside its own expansion.
 * A name the lexer found inside a preprocessing number (GLUED_NAME) is
 * no macro, here as for the compiler.
 */
final class Preprocessor {
	// Why a token was not read as written; see doubts.
	static final int UNDECIDED_CONDITION = 1;
	static final int UNCLOSED_INVOCATION = 2;
	static final int ARGUMENT_COUNT = 3;
	static final int PASTING = 4;
	static final int TOO_LARGE = 5;
	static final int BROKEN_DIRECTIVE = 6;
	static final int GUESSED_MACRO = 7;

	// Tokens one invocation in the text may expand to, and how deep
	// arguments may nest, before it is left as it is.
	private static final int BUDGET = 1 << 17;
	private static final int NESTING = 32;

	// A token on its way through expansion.
	private static final class Piece {
		final int symbol;
		final byte kind;
		// The raw token that spells it; or -1, and then which token of
		// which macro's text it is.
		final int raw;
		final Macro macro;
		final int index;
		// The raw token that names the outermost invocation it came
		// through, or -1.
		final int via;
		final Hide hide;

		Piece(int symbol, byte kind, int raw, Macro macro, int index, int via,
				Hide hide) {
			this.symbol = symbol;
			this.kind = kind;
			this.raw = raw;
			this.macro = macro;
			this.index = index;
			this.via = via;
			this.hide = hide;
		}
	}

	// The macros a piece is inside the expansion of.
	private static final class Hide {
		final int name;
		final Hide rest;

		Hide(int name, Hide rest) {
			this.name = name;
			this.rest = rest;
		}

		static boolean has(Hide hide, int name) {
			for (; hide != null; hide = hide.rest) {
				if (hide.name == name) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * With this many compiled tokens {@link #run} stops at the next
	 * place between two tokens of the text, wherever that is.
	 */
	int stopAt = Integer.MAX_VALUE;
	/** How many tokens were compiled when the last directive was run; -1 for none. */
	int directiveAt = -1;
	/** Every #define and #undef line that was compiled, in order. */
	final List<Macro> definitions = new ArrayList<>();
	/** Pairs: a raw token and why it was not read as written. */
	final Ints doubts = new Ints();

	private final Lexer lexer;
	private final RawTokens raw;
	private final CompiledTokens out;
	private final Environment env;
	private final Symbols symbols;
	private final ArrayDeque<Piece> pending = new ArrayDeque<>();
	// The raw token to read next, and whether the text has ended.
	private int next;
	private boolean ended;
	// Braces, and parentheses and brackets, open in what was compiled;
	// and whether the last token compiled is one an item may end with.
	private int braces;
	private int brackets;
	private boolean boundary;
	private int budget;
	private int nesting;
	// Whether a macro that is only a guess was expanded.
	private boolean guessed;

	/**
	 * @param raw receives the tokens of the text as the lexer reads
	 *            them, marked OFF and UNDECIDED
	 * @param out receives the compiled tokens
	 */
	Preprocessor(Lexer lexer, RawTokens raw, CompiledTokens out,
			Environment env, Symbols symbols) {
		this.lexer = lexer;
		this.raw = raw;
		this.out = out;
		this.env = env;
		this.symbols = symbols;
	}

	/** How many raw tokens were read. */
	int read() {
		return next;
	}

	/**
	 * Reads on to where an item of the program may end: behind a "}"
	 * that closes the outermost brace, or behind a ";" outside every
	 * brace, parenthesis and bracket, where no expansion goes on. A
	 * parenthesis that is still open at such a "}" is forgotten: none
	 * spans a function. False if the text ends first.
	 *
	 * This is only where to try: whether an item does end there is
	 * for the parser to say. So that it gets to say it in a block
	 * whose brace is not closed, reading also stops once
	 * {@link #stopAt} tokens are compiled.
	 */
	boolean run() {
		while (true) {
			Piece piece = pending.pollFirst();
			if (piece == null) {
				if (out.size >= stopAt) {
					return true;
				}
				if (!has(next)) {
					return false;
				}
				int i = next++;
				if (raw.symbol[i] == Words.HASH &&
						raw.is(i, RawTokens.LINE_START) &&
						raw.is(i, RawTokens.DIRECTIVE)) {
					directive(i);
					continue;
				}
				mark(i);
				if (!env.compiling()) {
					continue;
				}
				int symbol = raw.symbol[i];
				if (raw.kind[i] != RawTokens.NAME || !expands(symbol)) {
					out.add(symbol, raw.kind[i], i, -1);
					if (compiled(symbol)) {
						boundary = false;
						return true;
					}
					continue;
				}
				budget = BUDGET;
				piece = new Piece(symbol, RawTokens.NAME, i, null, 0, -1, null);
			}
			step(piece, pending, null);
			if (pending.isEmpty() && boundary) {
				boundary = false;
				return true;
			}
		}
	}

	private boolean has(int index) {
		while (index >= raw.size && !ended) {
			ended = !lexer.next();
		}
		return index < raw.size;
	}

	private boolean expands(int symbol) {
		return env.macro(symbol) != null || symbol == Words.LINE_NUMBER ||
				symbol == Words.FILE_NUMBER;
	}

	private void mark(int index) {
		raw.flags[index] |= (env.compiling() ? 0 : RawTokens.OFF) |
				(env.undecided > 0 ? RawTokens.UNDECIDED : 0);
	}

	private void doubt(int index, int why) {
		doubts.add(index);
		doubts.add(why);
	}

	// Counts a compiled token; true if an item may end with it.
	private boolean compiled(int symbol) {
		boundary = false;
		if (symbol == Words.LEFT_BRACE) {
			++braces;
		} else if (symbol == Words.RIGHT_BRACE) {
			braces = Math.max(0, braces - 1);
			boundary = braces == 0;
			brackets = boundary ? 0 : brackets;
		} else if (symbol == Words.LEFT_PAREN || symbol == Words.LEFT_BRACKET) {
			++brackets;
		} else if (symbol == Words.RIGHT_PAREN || symbol == Words.RIGHT_BRACKET) {
			brackets = Math.max(0, brackets - 1);
		} else if (symbol == Words.SEMICOLON) {
			boundary = braces == 0 && brackets == 0;
		}
		return boundary;
	}

	// Takes one piece: expands it into the front of input if it names
	// a macro, else passes it on, to sink or, without one, to the
	// compiled tokens. Only without a sink may an invocation's
	// arguments be read on in the text.
	private void step(Piece piece, ArrayDeque<Piece> input, List<Piece> sink) {
		boolean hidden = piece.kind != RawTokens.NAME ||
				Hide.has(piece.hide, piece.symbol);
		Macro macro = hidden ? null : env.macro(piece.symbol);
		int via = piece.via >= 0 ? piece.via : piece.raw;
		if (macro == null) {
			if (!hidden && (piece.symbol == Words.LINE_NUMBER ||
					piece.symbol == Words.FILE_NUMBER)) {
				// A number; which one depends on where the text stands
				// in its file, so a condition cannot be decided by it.
				piece = new Piece(piece.symbol, RawTokens.NUMBER, piece.raw,
						piece.macro, piece.index, piece.via, piece.hide);
			}
			pass(piece, sink);
			return;
		}
		List<List<Piece>> arguments = List.of();
		if (macro.function) {
			arguments = arguments(input, sink == null);
			if (arguments == null) {
				pass(piece, sink);
				return;
			}
			if (arguments.size() != macro.parameters.length &&
					!(macro.parameters.length == 0 && arguments.size() == 1 &&
							arguments.get(0).isEmpty())) {
				doubt(via, ARGUMENT_COUNT);
			}
		}
		if (macro.pastes) {
			doubt(via, PASTING);
			return;
		}
		if (macro.guess) {
			guessed = true;
			doubt(via, GUESSED_MACRO);
		}
		Hide hide = new Hide(macro.name, piece.hide);
		List<Piece> result = new ArrayList<>();
		for (int k = 0; k < macro.body.length; ++k) {
			int parameter = macro.kinds[k] == RawTokens.NAME
					? macro.parameter(macro.body[k]) : -1;
			if (parameter < 0) {
				result.add(new Piece(macro.body[k], macro.kinds[k], -1, macro,
						k, via, hide));
			} else if (parameter < arguments.size()) {
				for (Piece a : expanded(arguments.get(parameter))) {
					result.add(new Piece(a.symbol, a.kind, a.raw, a.macro,
							a.index, via, new Hide(macro.name, a.hide)));
				}
			}
		}
		budget -= result.size() + 1;
		if (budget < 0) {
			doubt(via, TOO_LARGE);
			pass(piece, sink);
			return;
		}
		for (int k = result.size() - 1; k >= 0; --k) {
			input.addFirst(result.get(k));
		}
	}

	private void pass(Piece piece, List<Piece> sink) {
		if (sink != null) {
			sink.add(piece);
			return;
		}
		if (piece.raw >= 0) {
			out.add(piece.symbol, piece.kind, piece.raw, piece.via);
		} else {
			out.add(piece.symbol, piece.kind, piece.macro, piece.index,
					piece.via);
		}
		compiled(piece.symbol);
	}

	// An argument with its own macros expanded, as if nothing followed it.
	private List<Piece> expanded(List<Piece> argument) {
		boolean any = false;
		for (Piece piece : argument) {
			any |= piece.kind == RawTokens.NAME && expands(piece.symbol);
		}
		if (!any) {
			return argument;
		}
		if (nesting == NESTING) {
			doubt(Math.max(0, next - 1), TOO_LARGE);
			return argument;
		}
		++nesting;
		ArrayDeque<Piece> input = new ArrayDeque<>(argument);
		List<Piece> sink = new ArrayList<>();
		for (Piece piece; (piece = input.pollFirst()) != null; ) {
			step(piece, input, sink);
		}
		--nesting;
		return sink;
	}

	// The arguments that follow a macro's name: first in input, then,
	// if it may, on in the text. Null, and nothing taken, if no "("
	// follows or it is not closed.
	private List<List<Piece>> arguments(ArrayDeque<Piece> input, boolean text) {
		Piece first = input.peekFirst();
		if (first != null ? first.symbol != Words.LEFT_PAREN
				: !(text && has(next) && !raw.is(next, RawTokens.DIRECTIVE) &&
						raw.symbol[next] == Words.LEFT_PAREN)) {
			return null;
		}
		List<List<Piece>> arguments = new ArrayList<>();
		List<Piece> argument = new ArrayList<>();
		List<Piece> taken = new ArrayList<>();
		int level = 0;
		// Once input is used up: the raw token that closes the invocation.
		int close = -1;
		while (true) {
			Piece piece = input.pollFirst();
			if (piece != null) {
				taken.add(piece);
			} else {
				if (close < 0 && text) {
					close = closing(level);
				}
				if (close < 0) {
					for (int k = taken.size() - 1; k >= 0; --k) {
						input.addFirst(taken.get(k));
					}
					doubt(Math.max(0, next - 1), UNCLOSED_INVOCATION);
					return null;
				}
				mark(next);
				piece = new Piece(raw.symbol[next], raw.kind[next], next, null,
						0, -1, null);
				++next;
			}
			if (piece.symbol == Words.LEFT_PAREN) {
				if (level++ == 0) {
					continue;
				}
			} else if (piece.symbol == Words.RIGHT_PAREN) {
				if (--level == 0) {
					arguments.add(argument);
					return arguments;
				}
			} else if (piece.symbol == Words.COMMA && level == 1) {
				arguments.add(argument);
				argument = new ArrayList<>();
				continue;
			}
			argument.add(piece);
		}
	}

	// The raw token that closes an invocation with level parentheses
	// open, from next on; -1 if a directive or the end comes first.
	private int closing(int level) {
		for (int i = next; has(i) && !raw.is(i, RawTokens.DIRECTIVE); ++i) {
			if (raw.symbol[i] == Words.LEFT_PAREN) {
				++level;
			} else if (raw.symbol[i] == Words.RIGHT_PAREN && --level == 0) {
				return i;
			}
		}
		return -1;
	}

	// Directives.

	private void directive(int hash) {
		int end = hash + 1;
		while (has(end) && raw.is(end, RawTokens.DIRECTIVE) &&
				!raw.is(end, RawTokens.LINE_START)) {
			++end;
		}
		next = end;
		directiveAt = out.size;
		int word = end > hash + 1 ? raw.symbol[hash + 1] : -1;
		boolean compiling = env.compiling();
		// Whether the line itself is compiled: a conditional's lines
		// are when the code around it is.
		boolean line = compiling;
		boolean undecided = env.undecided > 0;
		if (word == Words.IF || word == Words.IFDEF || word == Words.IFNDEF) {
			Boolean value = !compiling ? Boolean.FALSE
					: word == Words.IF ? condition(hash + 2, end)
					: defined(hash + 2, end, word == Words.IFNDEF);
			env.push((compiling ? Environment.AROUND : 0) | arm(value));
		} else if (word == Words.ELIF || word == Words.ELSE) {
			int top = env.open == 0 ? Environment.ELSE : env.top();
			if ((top & Environment.ELSE) != 0) {
				doubt(hash, BROKEN_DIRECTIVE);
			} else {
				line = (top & Environment.AROUND) != 0;
				Boolean value = !line || (top & Environment.TAKEN) != 0
						? Boolean.FALSE : word == Words.ELSE ? Boolean.TRUE
						: condition(hash + 2, end);
				env.replace((top & ~Environment.COMPILING) | arm(value) |
						(word == Words.ELSE ? Environment.ELSE : 0));
			}
		} else if (word == Words.ENDIF) {
			if (env.open == 0) {
				doubt(hash, BROKEN_DIRECTIVE);
			} else {
				line = (env.top() & Environment.AROUND) != 0;
				env.pop();
			}
		} else if ((word == Words.DEFINE || word == Words.UNDEF) &&
				hash + 2 < end && !compiling && undecided) {
			// It may be compiled after all: what the name is, is a guess.
			Macro known = env.known(raw.symbol[hash + 2]);
			define(known == null ? Macro.undefined(raw.symbol[hash + 2], true)
					: known.guessed());
		} else if (compiling && word == Words.DEFINE) {
			define(hash, hash + 2, end, undecided);
		} else if (compiling && word == Words.UNDEF && hash + 2 < end) {
			define(Macro.undefined(raw.symbol[hash + 2], undecided));
		} else if (compiling && word == Words.VERSION && hash + 2 < end) {
			long version = number(raw.symbol[hash + 2]);
			if (version > 0 && version < Integer.MAX_VALUE) {
				env.version = (int) version;
			}
		}
		undecided |= env.undecided > 0;
		for (int i = hash; i < end; ++i) {
			raw.flags[i] |= (line ? 0 : RawTokens.OFF) |
					(undecided ? RawTokens.UNDECIDED : 0);
		}
	}

	// What an arm with a condition of value adds to its conditional.
	private static int arm(Boolean value) {
		return value == null ? Environment.UNDECIDED
				: value ? Environment.TAKEN | Environment.COMPILING : 0;
	}

	private void define(Macro macro) {
		definitions.add(macro);
		env.define(macro);
	}

	private void define(int hash, int from, int end, boolean guess) {
		if (from >= end || raw.kind[from] != RawTokens.NAME) {
			doubt(hash, BROKEN_DIRECTIVE);
			return;
		}
		int at = from + 1;
		// It takes arguments if the parenthesis stands right behind
		// the name; a line continuation between them is nothing.
		boolean function = at < end && raw.symbol[at] == Words.LEFT_PAREN &&
				!raw.is(at, RawTokens.SPACED);
		Ints parameters = new Ints();
		if (function) {
			for (++at; at < end && raw.symbol[at] != Words.RIGHT_PAREN; ++at) {
				if (raw.kind[at] == RawTokens.NAME) {
					parameters.add(raw.symbol[at]);
				}
			}
			if (at == end) {
				doubt(hash, BROKEN_DIRECTIVE);
			} else {
				++at;
			}
		}
		define(new Macro(raw.symbol[from], function, parameters.toArray(),
				Arrays.copyOfRange(raw.symbol, at, end),
				Arrays.copyOfRange(raw.kind, at, end), at, guess));
	}

	// Conditions. Null is "cannot be decided".

	private Boolean isDefined(int symbol) {
		Macro known = env.known(symbol);
		if (known != null && known.guess) {
			return null;
		}
		if (env.macro(symbol) != null || symbol == Words.GL_ES ||
				symbol == Words.VERSION_NUMBER || symbol == Words.LINE_NUMBER ||
				symbol == Words.FILE_NUMBER ||
				(symbol == Words.PRECISION_HIGH && env.version >= 300)) {
			return Boolean.TRUE;
		}
		// What only the driver defines: its names start with GL_ or
		// have two underscores in a row.
		String name = symbols.name(symbol);
		return name.startsWith("GL_") || name.contains("__")
				? null : Boolean.FALSE;
	}

	private Boolean defined(int from, int end, boolean not) {
		Boolean value = from < end && raw.kind[from] == RawTokens.NAME
				? isDefined(raw.symbol[from]) : null;
		if (value == null) {
			doubt(from - 2, UNDECIDED_CONDITION);
			return null;
		}
		return value ^ not;
	}

	private List<Piece> condition;
	private int at;
	private boolean undecidable;

	private Boolean condition(int from, int end) {
		ArrayDeque<Piece> input = new ArrayDeque<>();
		undecidable = from >= end;
		for (int i = from; i < end; ++i) {
			if (raw.symbol[i] != Words.DEFINED) {
				input.add(new Piece(raw.symbol[i], raw.kind[i], i, null, 0, -1,
						null));
				continue;
			}
			boolean paren = i + 1 < end && raw.symbol[i + 1] == Words.LEFT_PAREN;
			int name = i + (paren ? 2 : 1);
			Boolean value = name < end && raw.kind[name] == RawTokens.NAME
					? isDefined(raw.symbol[name]) : null;
			undecidable |= value == null;
			input.add(new Piece(Boolean.TRUE.equals(value) ? Words.ONE
					: Words.ZERO, RawTokens.NUMBER, i, null, 0, -1, null));
			i = name + (paren ? 1 : 0);
		}
		condition = new ArrayList<>();
		budget = BUDGET;
		guessed = false;
		for (Piece piece; (piece = input.pollFirst()) != null; ) {
			step(piece, input, condition);
		}
		at = 0;
		long value = binary(1, 0);
		if (undecidable || guessed || at != condition.size()) {
			doubt(from - 2, UNDECIDED_CONDITION);
			return null;
		}
		return value != 0;
	}

	private long binary(int level, int depth) {
		if (level > Words.LEVELS) {
			return unary(depth);
		}
		long left = binary(level + 1, depth);
		while (at < condition.size() &&
				Words.level(condition.get(at).symbol) == level) {
			String operator = symbols.name(condition.get(at++).symbol);
			long right = binary(level + 1, depth);
			switch (operator) {
				case "||" -> left = left != 0 || right != 0 ? 1 : 0;
				case "&&" -> left = left != 0 && right != 0 ? 1 : 0;
				case "|" -> left |= right;
				case "^" -> left ^= right;
				case "&" -> left &= right;
				case "==" -> left = left == right ? 1 : 0;
				case "!=" -> left = left != right ? 1 : 0;
				case "<" -> left = left < right ? 1 : 0;
				case ">" -> left = left > right ? 1 : 0;
				case "<=" -> left = left <= right ? 1 : 0;
				case ">=" -> left = left >= right ? 1 : 0;
				case "<<", ">>" -> {
					if (right < 0 || right > 31) {
						undecidable = true;
					} else {
						left = operator.equals("<<") ? left << right : left >> right;
					}
				}
				case "+" -> left += right;
				case "-" -> left -= right;
				case "*" -> left *= right;
				default -> {
					if (right == 0) {
						undecidable = true;
					} else {
						left = operator.equals("/") ? left / right : left % right;
					}
				}
			}
			// A condition is an int: what does not fit one is the
			// compiler's to say.
			undecidable |= left != (int) left;
		}
		return left;
	}

	private long unary(int depth) {
		if (at >= condition.size() || depth > 64) {
			undecidable = true;
			return 0;
		}
		Piece piece = condition.get(at++);
		int symbol = piece.symbol;
		if (symbol == Words.NOT) {
			return unary(depth + 1) == 0 ? 1 : 0;
		} else if (symbol == Words.MINUS) {
			return -unary(depth + 1);
		} else if (symbol == Words.PLUS) {
			return unary(depth + 1);
		} else if (symbol == Words.COMPLEMENT) {
			return ~unary(depth + 1);
		} else if (symbol == Words.LEFT_PAREN) {
			long value = binary(1, depth + 1);
			if (at < condition.size() &&
					condition.get(at).symbol == Words.RIGHT_PAREN) {
				++at;
			} else {
				undecidable = true;
			}
			return value;
		} else if (piece.kind == RawTokens.NUMBER) {
			long value = number(symbol);
			undecidable |= value < 0 || value > Integer.MAX_VALUE;
			return value;
		} else if (symbol == Words.GL_ES || (symbol == Words.PRECISION_HIGH &&
				env.version >= 300)) {
			return 1;
		} else if (symbol == Words.VERSION_NUMBER) {
			return env.version;
		}
		undecidable = true;
		return 0;
	}

	// The value of an integer as it is spelled, or -1.
	private long number(int symbol) {
		String digits = symbols.name(symbol);
		if (digits.endsWith("u") || digits.endsWith("U")) {
			digits = digits.substring(0, digits.length() - 1);
		}
		try {
			return Long.decode(digits);
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
