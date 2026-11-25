package de.markusfisch.android.shadereditor.tree;

/**
 * Layer 2 of the tree: a parser of GLSL ES 1.00 and 3.00 over compiled
 * tokens. It never stops: where the grammar does not allow what
 * stands there, it writes an ERROR node over tokens it skips or a
 * MISSING node where a token should be, and reads on.
 *
 * It recovers by facts of the language, not by guesses:
 *
 * A function cannot stand in a function. A function's head at the
 * start of a statement means that braces are missing: every block
 * that is open ends there, and reading goes on outside.
 *
 * A word that only starts an item outside functions (uniform,
 * varying, attribute, layout and the like) means the same.
 *
 * Otherwise nothing is skipped for a token that is missing, and a
 * token that fits nowhere is skipped alone. Outside functions, what
 * starts no item is skipped up to where an item ends or must start.
 *
 * It needs no table of names. Whether a name is a type shows in the
 * text: two names in a row are a declaration, a name before "(" is
 * called. So the tree of an item depends on its tokens alone, and
 * what the names mean is worked out from {@link Names} afterwards.
 * An item that ends by recovery has also looked at tokens behind its
 * end: who reads the items of a part of a text by themselves has to
 * count those as read.
 */
final class Parser {
	private static final int NO_TYPE = Integer.MIN_VALUE;
	// How deep statements, expressions and structs may nest: far more
	// than any shader does, and little enough for a small stack.
	private static final int DEPTH = 100;
	// How far the head of a function is looked for. It only steers
	// recovery.
	private static final int HEAD = 512;

	/** The items read: kind, first token and end for each. */
	final Ints items = new Ints();
	/**
	 * Whether an item looked at the end of the tokens: with tokens
	 * behind them it might read otherwise. And how many items from
	 * the first on did not.
	 */
	boolean wantsMore;
	int settled;
	int errors;
	int missing;
	// For each item: how many nodes, steps, ERROR and MISSING nodes
	// there are up to its end.
	private final Ints marks = new Ints();

	private final CompiledTokens tokens;
	private final int version;
	private final Nodes nodes;
	private final Names names;
	private int n;
	private int p;
	// Function bodies open; a statement that cannot stand in one ends them.
	private int bodies;
	private boolean bail;
	private int depth;

	Parser(CompiledTokens tokens, int version, Nodes nodes, Names names) {
		this.tokens = tokens;
		this.version = version;
		this.nodes = nodes;
		this.names = names;
	}

	/** Reads the tokens before end as items, into empty nodes and names. */
	void parse(int end) {
		n = end;
		while (p < n) {
			int from = p;
			byte kind = item();
			if (p == from) {
				skip(1);
				kind = Nodes.ERROR;
			}
			items.add(kind);
			items.add(from);
			items.add(p);
			marks.add(nodes.size);
			marks.add(names.size);
			marks.add(errors);
			marks.add(missing);
			settled += !wantsMore ? 1 : 0;
			bail = false;
			bodies = 0;
			depth = 0;
		}
	}

	/**
	 * Forgets all but the first count items, which must be settled:
	 * they are as they would be with any tokens behind them.
	 */
	void keep(int count) {
		items.size = count * 3;
		nodes.size = count == 0 ? 0 : marks.at[count * 4 - 4];
		names.size = count == 0 ? 0 : marks.at[count * 4 - 3];
		errors = count == 0 ? 0 : marks.at[count * 4 - 2];
		missing = count == 0 ? 0 : marks.at[count * 4 - 1];
		wantsMore = false;
	}

	// Tokens. Every look at one goes through sym.

	// The symbol of token q; -1 behind the end, and for the q that
	// behindBrackets gives for none.
	private int sym(int q) {
		if (q >= n) {
			wantsMore = true;
			return -1;
		}
		return q < 0 ? -1 : tokens.symbol[q];
	}

	private boolean ended() {
		return sym(p) < 0;
	}

	private boolean at(int symbol) {
		return sym(p) == symbol;
	}

	private boolean at(int ahead, int symbol) {
		return sym(p + ahead) == symbol;
	}

	private boolean accept(int symbol) {
		if (sym(p) == symbol) {
			++p;
			return true;
		}
		return false;
	}

	private void expect(int symbol) {
		if (!accept(symbol)) {
			miss();
		}
	}

	private int cls(int q) {
		int symbol = sym(q);
		return symbol < 0 ? 0 : Words.cls(symbol, version);
	}

	private boolean word(int q) {
		return sym(q) >= 0 && (tokens.kind[q] == RawTokens.NAME ||
				tokens.kind[q] == RawTokens.GLUED_NAME);
	}

	// A word that is no word of the language.
	private boolean name(int q) {
		return word(q) && Words.cls(tokens.symbol[q], version) == 0;
	}

	// Nodes.

	private void node(byte kind, int first) {
		nodes.add(kind, first, p);
	}

	private void miss() {
		++missing;
		nodes.add(Nodes.MISSING, p, p);
	}

	private void skip(int count) {
		int from = p;
		for (; p < n && p < from + count; ++p) {
			if (name(p)) {
				names.add(Names.SKIPPED, p, 0);
			}
		}
		++errors;
		nodes.add(Nodes.ERROR, from, p);
	}

	private int open() {
		return names.add(Names.OPEN, 0, 0);
	}

	private void close(int open) {
		// First the step, which may move the steps to a larger array.
		int close = names.add(Names.CLOSE, 0, 0);
		names.a[open] = close;
	}

	// Items.

	private byte item() {
		int from = p;
		if (accept(Words.SEMICOLON)) {
			return Nodes.DECLARATION;
		}
		if (at(Words.RIGHT_BRACE) || at(Words.RIGHT_PAREN) ||
				at(Words.RIGHT_BRACKET)) {
			skip(1);
			return Nodes.ERROR;
		}
		if (at(Words.PRECISION)) {
			precision();
			return Nodes.PRECISION;
		}
		boolean qualified = qualifiers();
		if (qualified && accept(Words.SEMICOLON)) {
			node(Nodes.DECLARATION, from);
			return Nodes.DECLARATION;
		}
		if (qualified && name(p) && (at(1, Words.SEMICOLON) ||
				at(1, Words.COMMA))) {
			// Names that are declared elsewhere get a qualifier:
			// invariant gl_Position; invariant a, b;
			do {
				if (name(p)) {
					names.add(Names.VALUE, p++, 0);
				} else {
					miss();
				}
			} while (accept(Words.COMMA));
			expect(Words.SEMICOLON);
			node(Nodes.DECLARATION, from);
			return Nodes.DECLARATION;
		}
		if (qualified && name(p) && at(1, Words.LEFT_BRACE)) {
			int block = names.add(Names.BLOCK, p++, 0);
			members();
			int end = names.add(Names.END, 0, 1);
			if (name(p)) {
				names.b[end] = 0;
				int instance = p++;
				arrays();
				names.add(Names.VARIABLE, instance, block);
			}
			expect(Words.SEMICOLON);
			node(Nodes.STRUCT, from);
			return Nodes.STRUCT;
		}
		// Outside functions a name before ";" is a type: "S;" declares
		// nothing, and a compiler takes it.
		int type = type(name(p) && at(1, Words.SEMICOLON));
		if (type == NO_TYPE) {
			skipItem();
			return Nodes.ERROR;
		}
		if (accept(Words.SEMICOLON)) {
			node(Nodes.DECLARATION, from);
			return Nodes.DECLARATION;
		}
		if (name(p) && at(1, Words.LEFT_PAREN)) {
			return function(from, type);
		}
		declarators(type);
		expect(Words.SEMICOLON);
		node(Nodes.DECLARATION, from);
		return Nodes.DECLARATION;
	}

	// Nothing an item starts with: one ERROR up to where an item ends
	// or must start.
	private void skipItem() {
		int q = p;
		int level = 0;
		for (int s; (s = sym(q)) >= 0; ++q) {
			if (s == Words.LEFT_BRACE || s == Words.LEFT_PAREN) {
				++level;
			} else if (s == Words.RIGHT_BRACE || s == Words.RIGHT_PAREN) {
				if (--level <= 0 && s == Words.RIGHT_BRACE) {
					++q;
					break;
				}
			} else if (s == Words.SEMICOLON && level <= 0) {
				++q;
				break;
			} else if (q > p && top(q)) {
				break;
			}
		}
		skip(Math.max(1, q - p));
	}

	// What never stands in a function's body.
	private boolean top(int q) {
		return (cls(q) & Words.TOP) != 0 || functionHead(q);
	}

	// [qualifiers] type name ( ... ) {
	private boolean functionHead(int q) {
		while ((cls(q) & Words.QUALIFIER) != 0) {
			++q;
		}
		if (!((cls(q) & Words.TYPE) != 0 || name(q)) || !name(q + 1) ||
				sym(q + 2) != Words.LEFT_PAREN) {
			return false;
		}
		int level = 0;
		q += 2;
		for (int end = q + HEAD; q < end; ++q) {
			int s = sym(q);
			if (s == Words.LEFT_PAREN) {
				++level;
			} else if (s == Words.RIGHT_PAREN) {
				if (--level == 0) {
					return sym(q + 1) == Words.LEFT_BRACE;
				}
			} else if (s < 0 || s == Words.SEMICOLON ||
					s == Words.LEFT_BRACE || s == Words.RIGHT_BRACE) {
				return false;
			}
		}
		return false;
	}

	private byte function(int from, int returns) {
		names.add(Names.FUNCTION, p, returns);
		p += 2;
		int open = open();
		if (at(Words.VOID) && at(1, Words.RIGHT_PAREN)) {
			++p;
		} else if (!at(Words.RIGHT_PAREN)) {
			do {
				int start = p;
				qualifiers();
				int type = type(true);
				if (type == NO_TYPE) {
					int q = p;
					for (int s; (s = sym(q)) >= 0 && s != Words.COMMA &&
							s != Words.RIGHT_PAREN && s != Words.LEFT_BRACE &&
							s != Words.RIGHT_BRACE && s != Words.SEMICOLON; ) {
						++q;
					}
					if (q > p) {
						skip(q - p);
					} else {
						miss();
					}
				} else if (name(p)) {
					int name = p++;
					arrays();
					names.add(Names.VARIABLE, name, type);
				}
				node(Nodes.PARAMETER, start);
			} while (accept(Words.COMMA));
		}
		expect(Words.RIGHT_PAREN);
		byte kind = Nodes.PROTOTYPE;
		if (!accept(Words.SEMICOLON)) {
			if (at(Words.LEFT_BRACE)) {
				kind = Nodes.FUNCTION;
				++bodies;
				body(p++);
			} else if (!ended() && !at(Words.RIGHT_BRACE) && !top(p)) {
				// The opening brace is missing: the body starts here.
				kind = Nodes.FUNCTION;
				miss();
				++bodies;
				body(p);
			} else {
				miss();
			}
		}
		close(open);
		node(kind, from);
		return kind;
	}

	// Statements up to the brace that closes the block that starts at from.
	private void body(int from) {
		while (!ended() && !at(Words.RIGHT_BRACE)) {
			if (bodies > 0 && top(p)) {
				bail = true;
				break;
			}
			int before = p;
			statement();
			if (bail) {
				break;
			}
			if (p == before) {
				skip(1);
			}
		}
		if (bail || !accept(Words.RIGHT_BRACE)) {
			miss();
		}
		node(Nodes.BLOCK, from);
	}

	private void block() {
		int open = open();
		body(p++);
		close(open);
	}

	private void statement() {
		if (++depth > DEPTH) {
			--depth;
			skip(1);
			return;
		}
		int from = p;
		int s = sym(p);
		if (s == Words.LEFT_BRACE) {
			block();
		} else if (s == Words.SEMICOLON) {
			++p;
		} else if (s == Words.IF) {
			++p;
			expect(Words.LEFT_PAREN);
			expression();
			expect(Words.RIGHT_PAREN);
			inner();
			if (!bail && accept(Words.ELSE)) {
				inner();
			}
			node(Nodes.IF, from);
		} else if (s == Words.FOR) {
			++p;
			expect(Words.LEFT_PAREN);
			int open = open();
			if (!accept(Words.SEMICOLON)) {
				simple();
				expect(Words.SEMICOLON);
			}
			if (!at(Words.SEMICOLON) && !at(Words.RIGHT_PAREN)) {
				simple();
			}
			expect(Words.SEMICOLON);
			if (!at(Words.RIGHT_PAREN) && !at(Words.LEFT_BRACE)) {
				expression();
			}
			expect(Words.RIGHT_PAREN);
			inner();
			close(open);
			node(Nodes.LOOP, from);
		} else if (s == Words.WHILE) {
			++p;
			expect(Words.LEFT_PAREN);
			int open = open();
			simple();
			expect(Words.RIGHT_PAREN);
			inner();
			close(open);
			node(Nodes.LOOP, from);
		} else if (s == Words.DO) {
			++p;
			inner();
			if (!bail) {
				expect(Words.WHILE);
				expect(Words.LEFT_PAREN);
				expression();
				expect(Words.RIGHT_PAREN);
				expect(Words.SEMICOLON);
			}
			node(Nodes.LOOP, from);
		} else if (s == Words.SWITCH && version >= 300) {
			++p;
			expect(Words.LEFT_PAREN);
			expression();
			expect(Words.RIGHT_PAREN);
			if (at(Words.LEFT_BRACE)) {
				block();
			} else {
				miss();
			}
			node(Nodes.SWITCH, from);
		} else if ((s == Words.CASE || s == Words.DEFAULT) && version >= 300) {
			++p;
			if (s == Words.CASE) {
				expression();
			}
			expect(Words.COLON);
			node(Nodes.LABEL, from);
		} else if (s == Words.RETURN) {
			++p;
			if (!at(Words.SEMICOLON) && !at(Words.RIGHT_BRACE)) {
				expression();
			}
			expect(Words.SEMICOLON);
			node(Nodes.JUMP, from);
		} else if (s == Words.BREAK || s == Words.CONTINUE ||
				s == Words.DISCARD) {
			++p;
			expect(Words.SEMICOLON);
			node(Nodes.JUMP, from);
		} else if (s == Words.PRECISION) {
			precision();
		} else if (s == Words.ELSE || s == Words.RIGHT_PAREN ||
				s == Words.RIGHT_BRACKET || s == Words.COMMA ||
				s == Words.COLON) {
			skip(1);
		} else {
			boolean declares = simple();
			expect(Words.SEMICOLON);
			node(declares ? Nodes.DECLARATION : Nodes.EXPRESSION_STATEMENT,
					from);
		}
		--depth;
	}

	// The statement of if, else and the loops, in its own scope.
	private void inner() {
		if (ended() || at(Words.RIGHT_BRACE) || top(p)) {
			miss();
			return;
		}
		int open = open();
		int before = p;
		statement();
		if (p == before && !bail) {
			miss();
		}
		close(open);
	}

	// A declaration or an expression, without its semicolon; true for
	// a declaration.
	private boolean simple() {
		int c = cls(p);
		// "float;" declares nothing, and a compiler takes it.
		boolean declares = (c & Words.QUALIFIER) != 0 || at(Words.STRUCT) ||
				((c & Words.TYPE) != 0 && at(1, Words.SEMICOLON)) ||
				(((c & Words.TYPE) != 0 || name(p)) &&
						name(behindBrackets(p + 1)));
		if (!declares) {
			expression();
			return false;
		}
		qualifiers();
		int type = type(false);
		if (type == NO_TYPE) {
			miss();
		} else if (!at(Words.SEMICOLON)) {
			declarators(type);
		}
		return true;
	}

	// Behind the bracket pairs that start at q, or -1 if one is not closed.
	private int behindBrackets(int q) {
		while (sym(q) == Words.LEFT_BRACKET) {
			int level = 0;
			for (; ; ++q) {
				int s = sym(q);
				if (s == Words.LEFT_BRACKET) {
					++level;
				} else if (s == Words.RIGHT_BRACKET) {
					if (--level == 0) {
						++q;
						break;
					}
				} else if (s < 0 || s == Words.SEMICOLON ||
						s == Words.LEFT_BRACE || s == Words.RIGHT_BRACE) {
					return -1;
				}
			}
		}
		return q;
	}

	private void precision() {
		int from = p++;
		if ((cls(p) & Words.QUALIFIER) != 0) {
			++p;
		} else {
			miss();
		}
		if ((cls(p) & Words.TYPE) != 0) {
			++p;
		} else {
			miss();
		}
		expect(Words.SEMICOLON);
		node(Nodes.PRECISION, from);
	}

	private boolean qualifiers() {
		int from = p;
		while ((cls(p) & Words.QUALIFIER) != 0) {
			if (tokens.symbol[p++] == Words.LAYOUT) {
				if (accept(Words.LEFT_PAREN)) {
					while (!ended() && !at(Words.RIGHT_PAREN) &&
							!at(Words.SEMICOLON) && !at(Words.LEFT_BRACE) &&
							!at(Words.RIGHT_BRACE)) {
						if (word(p)) {
							++p;
							if (accept(Words.ASSIGN)) {
								ternary();
							}
						} else if (!accept(Words.COMMA)) {
							skip(1);
						}
					}
				}
				expect(Words.RIGHT_PAREN);
			}
		}
		if (p > from) {
			node(Nodes.QUALIFIERS, from);
		}
		return p > from;
	}

	// A type; gives the step that is the type, NO_STRUCT for one of
	// the language, or NO_TYPE if none stands here. A name is a type
	// when a name follows it, or alone if lone says so.
	private int type(boolean lone) {
		int from = p;
		int type = Names.NO_STRUCT;
		if (accept(Words.STRUCT)) {
			type = names.add(Names.STRUCT, name(p) ? p++ : -1, 0);
			if (at(Words.LEFT_BRACE)) {
				members();
			} else {
				miss();
			}
			names.add(Names.END, 0, 0);
		} else if ((cls(p) & Words.TYPE) != 0) {
			++p;
		} else if (name(p) && (lone || name(behindBrackets(p + 1)))) {
			type = names.add(Names.TYPE, p++, 0);
		} else {
			return NO_TYPE;
		}
		arrays();
		node(Nodes.TYPE, from);
		return type;
	}

	// The fields between braces.
	private void members() {
		if (++depth > DEPTH) {
			--depth;
			skip(1);
			return;
		}
		++p;
		while (!ended() && !at(Words.RIGHT_BRACE) && !functionHead(p)) {
			int from = p;
			qualifiers();
			int type = type(false);
			if (type == NO_TYPE) {
				int q = p;
				for (int s; (s = sym(q)) >= 0 && s != Words.RIGHT_BRACE; ++q) {
					if (s == Words.SEMICOLON) {
						++q;
						break;
					}
				}
				skip(Math.max(1, q - p));
				continue;
			}
			do {
				if (!name(p)) {
					miss();
					break;
				}
				int name = p++;
				arrays();
				names.add(Names.FIELD, name, type);
				node(Nodes.DECLARATOR, name);
			} while (accept(Words.COMMA));
			expect(Words.SEMICOLON);
			node(Nodes.FIELD, from);
		}
		expect(Words.RIGHT_BRACE);
		--depth;
	}

	private void declarators(int type) {
		do {
			if (!name(p)) {
				miss();
				return;
			}
			int name = p++;
			arrays();
			if (accept(Words.ASSIGN)) {
				assignment();
			}
			// A name is declared behind its initializer.
			names.add(Names.VARIABLE, name, type);
			node(Nodes.DECLARATOR, name);
		} while (accept(Words.COMMA));
	}

	private void arrays() {
		while (accept(Words.LEFT_BRACKET)) {
			if (!at(Words.RIGHT_BRACKET)) {
				expression();
			}
			expect(Words.RIGHT_BRACKET);
		}
	}

	// Expressions. Each gives the step that has the type of its value,
	// or NO_STRUCT.

	private int expression() {
		int from = p;
		int value = assignment();
		while (accept(Words.COMMA)) {
			value = assignment();
			node(Nodes.BINARY, from);
		}
		return value;
	}

	// Every expression inside another comes through here: an argument,
	// an index, an arm of "?:", what is assigned.
	private int assignment() {
		if (++depth > DEPTH) {
			--depth;
			skip(1);
			return Names.NO_STRUCT;
		}
		int from = p;
		int value = ternary();
		if (Words.assigns(sym(p))) {
			++p;
			assignment();
			node(Nodes.ASSIGNMENT, from);
		}
		--depth;
		return value;
	}

	private int ternary() {
		int from = p;
		int value = binary(1);
		if (accept(Words.QUESTION)) {
			int a = expression();
			expect(Words.COLON);
			int b = assignment();
			node(Nodes.TERNARY, from);
			return a == b || a < 0 || b < 0 ? Math.min(a, b)
					: names.add(Names.EITHER, a, b);
		}
		return value;
	}

	private int binary(int level) {
		int from = p;
		int value = unary();
		for (int mine; (mine = Words.precedence(sym(p))) >= level; ) {
			++p;
			binary(mine + 1);
			node(Nodes.BINARY, from);
			value = Names.NO_STRUCT;
		}
		return value;
	}

	private int unary() {
		int s = sym(p);
		if (s == Words.PLUS || s == Words.MINUS || s == Words.NOT ||
				s == Words.COMPLEMENT || s == Words.INCREMENT ||
				s == Words.DECREMENT) {
			if (++depth > DEPTH) {
				--depth;
				skip(1);
				return Names.NO_STRUCT;
			}
			int from = p++;
			unary();
			node(Nodes.UNARY, from);
			--depth;
			return Names.NO_STRUCT;
		}
		return postfix();
	}

	private int postfix() {
		int from = p;
		int value = primary();
		while (true) {
			if (at(Words.LEFT_BRACKET)) {
				arrays();
				node(Nodes.INDEX, from);
			} else if (accept(Words.DOT)) {
				if (!word(p)) {
					miss();
				} else if (at(Words.LENGTH) && at(1, Words.LEFT_PAREN) &&
						at(2, Words.RIGHT_PAREN)) {
					p += 3;
					value = Names.NO_STRUCT;
				} else if (value == Names.NO_STRUCT) {
					// A member of a type of the language.
					++p;
				} else {
					value = names.add(Names.MEMBER, p++, value);
				}
				node(Nodes.MEMBER, from);
			} else if (at(Words.INCREMENT) || at(Words.DECREMENT)) {
				++p;
				node(Nodes.UNARY, from);
				value = Names.NO_STRUCT;
			} else {
				return value;
			}
		}
	}

	private void arguments() {
		++p;
		if (at(Words.VOID) && at(1, Words.RIGHT_PAREN)) {
			++p;
		} else if (!at(Words.RIGHT_PAREN)) {
			do {
				if (ended() || at(Words.RIGHT_PAREN) || at(Words.SEMICOLON) ||
						at(Words.RIGHT_BRACE)) {
					miss();
					break;
				}
				assignment();
			} while (accept(Words.COMMA));
		}
		expect(Words.RIGHT_PAREN);
	}

	private int primary() {
		int from = p;
		int s = sym(p);
		int value = Names.NO_STRUCT;
		if (s >= 0 && (tokens.kind[p] == RawTokens.NUMBER || s == Words.TRUE ||
				s == Words.FALSE)) {
			++p;
			node(Nodes.LITERAL, from);
		} else if (s == Words.LEFT_PAREN) {
			if (++depth > DEPTH) {
				--depth;
				skip(1);
				return value;
			}
			++p;
			value = expression();
			expect(Words.RIGHT_PAREN);
			node(Nodes.PARENTHESES, from);
			--depth;
		} else if ((cls(p) & Words.TYPE) != 0) {
			// A constructor of a type of the language.
			++p;
			arrays();
			if (at(Words.LEFT_PAREN)) {
				arguments();
			} else {
				miss();
			}
			node(Nodes.CALL, from);
		} else if (!name(p)) {
			miss();
		} else if (at(1, Words.LEFT_PAREN)) {
			value = names.add(Names.CALL, p++, 0);
			arguments();
			node(Nodes.CALL, from);
		} else if (at(1, Words.LEFT_BRACKET) &&
				sym(behindBrackets(p + 1)) == Words.LEFT_PAREN) {
			// The constructor of an array of structs: S[2](a, b).
			value = names.add(Names.TYPE, p++, 0);
			arrays();
			arguments();
			node(Nodes.CALL, from);
		} else {
			value = names.add(Names.VALUE, p++, 0);
			node(Nodes.NAME, from);
		}
		return value;
	}
}
