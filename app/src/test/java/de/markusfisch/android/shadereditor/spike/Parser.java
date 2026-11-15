package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.spike.Types.Type;

/**
 * SPIKE, throwaway: option B, a hand-written recursive-descent parser
 * for GLSL ES 1.00 and 3.00 that accepts any text. It never throws and
 * always ends: what it cannot read becomes an error node, a statement
 * that went wrong ends at its ";", at a closing brace, or where a new
 * line starts a statement, and a function head inside a body ends that
 * body, because GLSL has no nested functions.
 *
 * It reads the text as it is written, not as the preprocessor leaves
 * it: directives are not part of the tree, a macro is a name, and of a
 * conditional only the branch the token stream marked active is read.
 */
final class Parser {
	static final int UNIT = 0;
	static final int FUNCTION = 1;
	static final int PARAM = 2;
	static final int DECLARATION = 3;
	static final int DECLARATOR = 4;
	static final int STRUCT = 5;
	static final int TYPE = 6;
	static final int PRECISION = 7;
	static final int BLOCK = 10;
	static final int IF = 11;
	static final int FOR = 12;
	static final int WHILE = 13;
	static final int DO = 14;
	static final int SWITCH = 15;
	static final int CASE = 16;
	static final int JUMP = 17;
	static final int EXPRESSION = 18;
	static final int EMPTY = 19;
	static final int NAME = 30;
	static final int NUMBER = 31;
	static final int BOOL = 32;
	static final int BINARY = 33;
	static final int UNARY = 34;
	static final int POSTFIX = 35;
	static final int ASSIGN = 36;
	static final int TERNARY = 37;
	static final int CALL = 38;
	static final int FIELD = 39;
	static final int INDEX = 40;
	static final int COMMA = 41;
	static final int ERROR = 42;

	// Qualifier bits of a TYPE node, in op.
	static final int Q_CONST = 1;
	static final int Q_UNIFORM = 2;
	static final int Q_IN = 4;
	static final int Q_OUT = 8;

	private static final Node[] NONE = new Node[0];

	/** A node of the tree; what each field means depends on the kind. */
	static final class Node {
		final int kind;
		int start;
		int end;
		// A name as a number, and where it stands in the text.
		int name = -1;
		int at;
		int op;
		Node a;
		Node b;
		Node c;
		Node[] list = NONE;
		// Filled by Sema.
		Type type;
		Sema.Symbol symbol;

		Node(int kind, int start) {
			this.kind = kind;
			this.start = start;
			this.end = start;
		}
	}

	/** A place the parser could not read. */
	record Problem(int offset, String message) {
	}

	final TokenStream t;
	final List<Problem> problems = new ArrayList<>();
	int problemCount;
	int nodes;
	private int n;
	private int p;
	// Set when a function head is met inside a body: unwind to the top.
	private boolean lostBrace;
	private int depth;
	// False only for the first operand of a statement that is an
	// expression, which may stand first on its line.
	private boolean inExpression = true;
	private Node[] scratch = new Node[64];
	private int scratchTop;
	// The names of the structs read so far, by the name's number: GLSL
	// needs to know a type's name to read a declaration.
	private boolean[] structNames = new boolean[256];

	Parser(TokenStream t) {
		this.t = t;
		n = t.size;
		skipInactive();
	}

	/** Read no further than token index. */
	void limit(int index) {
		n = Math.min(n, index);
	}

	/** Parse one declaration of the top level, or null. */
	Node one() {
		lostBrace = false;
		return external();
	}

	/** Start reading at token index. */
	void from(int index) {
		p = index;
		skipInactive();
	}

	static Node parse(TokenStream t) {
		return new Parser(t).unit();
	}

	Node unit() {
		Node unit = node(UNIT, 0);
		int mark = scratchTop;
		while (p < n) {
			int before = p;
			lostBrace = false;
			Node d = external();
			if (d != null) {
				push(d);
			}
			if (p == before) {
				problem("unexpected");
				advance();
			}
		}
		unit.list = pop(mark);
		unit.end = t.source.length();
		return unit;
	}

	/** Parse the tokens as one expression; null if they are not one. */
	Node wholeExpression() {
		if (p >= n) {
			return null;
		}
		Node e = expression();
		return p >= n && problemCount == 0 ? e : null;
	}

	// ---- Tokens --------------------------------------------------------

	private void skipInactive() {
		while (p < n && t.inactive(p)) {
			++p;
		}
	}

	private void advance() {
		if (p < n) {
			++p;
			skipInactive();
		}
	}

	private int kind() {
		return p < n ? t.kind(p) : 0;
	}

	private boolean is(char c) {
		return p < n && t.kind(p) == TokenStream.PUNCT && t.charAt(p) == c;
	}

	private boolean isKeyword(int id) {
		return p < n && t.kind(p) == TokenStream.KEYWORD && t.ids[p] == id;
	}

	// The next active token after p, or n.
	private int next(int i) {
		for (++i; i < n && t.inactive(i); ) {
			++i;
		}
		return i;
	}

	private boolean isAt(int i, char c) {
		return i < n && t.kind(i) == TokenStream.PUNCT && t.charAt(i) == c;
	}

	private boolean accept(char c) {
		if (is(c)) {
			advance();
			return true;
		}
		return false;
	}

	private boolean expect(char c) {
		if (accept(c)) {
			return true;
		}
		problem("expected " + c);
		return false;
	}

	private int here() {
		return p < n ? t.starts[p] : t.source.length();
	}

	private int before() {
		// The end of the last active token before p.
		int i = p - 1;
		while (i >= 0 && t.inactive(i)) {
			--i;
		}
		return i >= 0 ? t.ends[i] : 0;
	}

	private void problem(String message) {
		++problemCount;
		if (problems.size() < 100) {
			problems.add(new Problem(here(), message));
		}
	}

	private Node node(int kind, int start) {
		++nodes;
		return new Node(kind, start);
	}

	private void push(Node node) {
		if (scratchTop == scratch.length) {
			scratch = Arrays.copyOf(scratch, scratchTop * 2);
		}
		scratch[scratchTop++] = node;
	}

	private Node[] pop(int mark) {
		if (scratchTop == mark) {
			return NONE;
		}
		Node[] list = Arrays.copyOfRange(scratch, mark, scratchTop);
		scratchTop = mark;
		return list;
	}

	// The operator at p: its characters packed, its length in tokens in
	// opLength. Characters that touch form one operator.
	private int opLength;

	private int operator() {
		if (kind() != TokenStream.PUNCT) {
			opLength = 0;
			return 0;
		}
		char a = t.charAt(p);
		char b = p + 1 < n && t.kind(p + 1) == TokenStream.PUNCT &&
				t.starts[p + 1] == t.ends[p] && !t.inactive(p + 1)
				? t.charAt(p + 1) : 0;
		char c = b != 0 && p + 2 < n && t.kind(p + 2) == TokenStream.PUNCT &&
				t.starts[p + 2] == t.ends[p + 1] && !t.inactive(p + 2)
				? t.charAt(p + 2) : 0;
		if ((a == '<' || a == '>') && b == a && c == '=') {
			opLength = 3;
			return a | b << 8 | c << 16;
		}
		if (b != 0 && ((b == '=' && "<>=!+-*/%&|^".indexOf(a) >= 0) ||
				(b == a && "+-<>&|^".indexOf(a) >= 0))) {
			opLength = 2;
			return a | b << 8;
		}
		opLength = 1;
		return a;
	}

	private void takeOperator() {
		for (int i = 0; i < opLength; ++i) {
			advance();
		}
	}

	// ---- Declarations --------------------------------------------------

	private Node external() {
		if (accept(';')) {
			return null;
		}
		if (is('}')) {
			problem("unmatched }");
			advance();
			return null;
		}
		if (!startsDeclaration(true)) {
			problem("expected a declaration");
			recover(true);
			return null;
		}
		return declaration(true);
	}

	// Whether a declaration starts at p: a qualifier, "struct",
	// "precision", a type followed by a name or by brackets and a name,
	// or two names in a row.
	private boolean startsDeclaration(boolean global) {
		int kind = kind();
		if (kind == TokenStream.KEYWORD) {
			int id = t.ids[p];
			return id == Names.STRUCT || id == Names.PRECISION ||
					isQualifier(id);
		}
		if (kind != TokenStream.TYPE && kind != TokenStream.IDENT) {
			return false;
		}
		int i = next(p);
		if (kind == TokenStream.IDENT) {
			if (i >= n || t.kind(i) != TokenStream.IDENT) {
				return false;
			}
			int id = t.ids[p];
			if (id < structNames.length && structNames[id]) {
				return true;
			}
			// Two names in a row with a type this text does not
			// declare (a macro, an include): a declaration only if both
			// stand on one line and what follows fits one. Across a
			// line break it is a statement that lost its ";".
			if (t.lineFirst(i)) {
				return false;
			}
			int j = next(i);
			return isAt(j, ';') || isAt(j, '=') || isAt(j, ',') ||
					isAt(j, '[') || isAt(j, ')') || (global && isAt(j, '('));
		}
		if (isAt(i, '[')) {
			// "float[3] a" declares, "a[3] = x" does not.
			if (kind != TokenStream.TYPE) {
				return false;
			}
			int depth = 0;
			for (; i < n; i = next(i)) {
				if (isAt(i, '[')) {
					++depth;
				} else if (isAt(i, ']') && --depth == 0) {
					break;
				}
			}
			i = next(i);
		}
		if (i < n && t.kind(i) == TokenStream.IDENT) {
			return true;
		}
		// At the top level a type alone is a declaration that is not
		// finished: "vec3 ".
		return global && kind == TokenStream.TYPE && !isAt(i, '(');
	}

	private static boolean isQualifier(int id) {
		return id == Names.CONST || id == Names.IN || id == Names.OUT ||
				id == Names.INOUT || id == Names.UNIFORM ||
				id == Names.ATTRIBUTE || id == Names.VARYING ||
				id == Names.CENTROID || id == Names.FLAT ||
				id == Names.SMOOTH || id == Names.INVARIANT ||
				id == Names.LAYOUT || id == Names.HIGHP ||
				id == Names.MEDIUMP || id == Names.LOWP;
	}

	private Node declaration(boolean global) {
		int start = here();
		if (isKeyword(Names.PRECISION)) {
			Node node = node(PRECISION, start);
			advance();
			if (kind() == TokenStream.KEYWORD) {
				advance();
			}
			if (kind() == TokenStream.TYPE) {
				advance();
			}
			expect(';');
			node.end = before();
			return node;
		}
		Node type = typeSpecifier();
		if (type == null) {
			// Qualifiers alone: "invariant x;", "layout(...) in;".
			recover(global);
			return null;
		}
		if (accept(';')) {
			// A struct alone.
			Node node = node(DECLARATION, start);
			node.a = type;
			node.end = before();
			return node;
		}
		if (kind() != TokenStream.IDENT) {
			problem("expected a name");
			Node node = node(DECLARATION, start);
			node.a = type;
			recover(global);
			node.end = before();
			return node;
		}
		if (isAt(next(p), '(')) {
			if (!global) {
				// A function head in a body: the body lost its brace.
				lostBrace = true;
				problem("missing } before this function");
				p = t.tokenAt(start);
				skipInactive();
				return null;
			}
			return function(type, start);
		}
		Node node = node(DECLARATION, start);
		node.a = type;
		int mark = scratchTop;
		do {
			if (kind() != TokenStream.IDENT) {
				problem("expected a name");
				break;
			}
			Node d = node(DECLARATOR, here());
			d.name = t.ids[p];
			d.at = t.starts[p];
			advance();
			if (accept('[')) {
				d.op = 1;
				if (!is(']')) {
					d.b = expression();
				}
				expect(']');
			}
			if (accept('=')) {
				d.a = is('{') ? initializerList() : assignment();
			}
			d.end = before();
			push(d);
		} while (accept(','));
		node.list = pop(mark);
		if (!accept(';')) {
			problem("expected ;");
			if (!atStatementStart()) {
				recover(global);
			}
		}
		node.end = before();
		return node;
	}

	// "{ a, b }" as an initializer: read as a comma list.
	private Node initializerList() {
		Node node = node(COMMA, here());
		advance();
		int mark = scratchTop;
		while (p < n && !is('}')) {
			int before = p;
			push(is('{') ? initializerList() : assignment());
			if (!accept(',') && !is('}')) {
				if (p == before) {
					break;
				}
			}
		}
		node.list = pop(mark);
		expect('}');
		node.end = before();
		return node;
	}

	// Qualifiers and a type: built-in, a struct's name, or a struct
	// written out. Null if there is no type.
	private Node typeSpecifier() {
		int start = here();
		int qualifiers = 0;
		while (kind() == TokenStream.KEYWORD && isQualifier(t.ids[p])) {
			int id = t.ids[p];
			qualifiers |= id == Names.CONST ? Q_CONST
					: id == Names.UNIFORM ? Q_UNIFORM
					: id == Names.IN || id == Names.INOUT ? Q_IN : 0;
			qualifiers |= id == Names.OUT || id == Names.INOUT ? Q_OUT : 0;
			advance();
			if (id == Names.LAYOUT && is('(')) {
				skipBrackets('(', ')');
			}
		}
		Node type = node(TYPE, start);
		type.op = qualifiers;
		if (isKeyword(Names.STRUCT)) {
			type.a = struct();
			type.name = type.a.name;
		} else if (kind() == TokenStream.TYPE ||
				kind() == TokenStream.IDENT) {
			type.name = t.ids[p];
			type.at = t.starts[p];
			advance();
		} else {
			return null;
		}
		if (is('[')) {
			// An array type: "float[3]".
			advance();
			type.b = is(']') ? node(EMPTY, here()) : expression();
			expect(']');
		}
		type.end = before();
		return type;
	}

	private Node struct() {
		Node node = node(STRUCT, here());
		advance();
		if (kind() == TokenStream.IDENT) {
			node.name = t.ids[p];
			node.at = t.starts[p];
			if (node.name >= structNames.length) {
				structNames = Arrays.copyOf(structNames, node.name * 2);
			}
			structNames[node.name] = true;
			advance();
		}
		if (accept('{')) {
			int mark = scratchTop;
			while (p < n && !is('}')) {
				int before = p;
				if (startsDeclaration(false)) {
					Node member = declaration(false);
					if (member != null) {
						push(member);
					}
					if (lostBrace) {
						break;
					}
				} else {
					problem("expected a member");
					recover(false);
				}
				if (p == before) {
					advance();
				}
			}
			node.list = pop(mark);
			if (!lostBrace) {
				expect('}');
			}
		}
		node.end = before();
		return node;
	}

	private Node function(Node type, int start) {
		Node node = node(FUNCTION, start);
		node.a = type;
		node.name = t.ids[p];
		node.at = t.starts[p];
		advance();
		advance();
		int mark = scratchTop;
		while (p < n && !is(')')) {
			int before = p;
			if (isKeyword(Names.IF) || is('{') || is(';') || is('}')) {
				break;
			}
			Node param = node(PARAM, here());
			param.a = typeSpecifier();
			if (kind() == TokenStream.IDENT) {
				param.name = t.ids[p];
				param.at = t.starts[p];
				advance();
				if (accept('[')) {
					param.op = 1;
					if (!is(']')) {
						param.b = expression();
					}
					expect(']');
				}
			}
			param.end = before();
			if (param.a != null) {
				push(param);
			}
			if (!accept(',')) {
				if (p == before) {
					problem("expected a parameter");
					advance();
				}
			}
		}
		node.list = pop(mark);
		expect(')');
		node.op = before();
		if (is('{')) {
			node.b = block();
		} else if (!accept(';')) {
			problem("expected { or ;");
		}
		node.end = before();
		return node;
	}

	// ---- Statements ----------------------------------------------------

	private Node block() {
		Node node = node(BLOCK, here());
		advance();
		if (++depth > 200) {
			// Deeper than any code: give up on this block.
			--depth;
			skipToClose();
			node.end = before();
			return node;
		}
		int mark = scratchTop;
		while (p < n && !is('}') && !lostBrace) {
			int before = p;
			Node s = statement();
			if (s != null) {
				push(s);
			}
			if (p == before && !lostBrace) {
				problem("unexpected");
				advance();
			}
		}
		node.list = pop(mark);
		if (!lostBrace) {
			expect('}');
		}
		--depth;
		node.end = before();
		return node;
	}

	private void skipToClose() {
		int nesting = 1;
		while (p < n && nesting > 0) {
			if (is('{')) {
				++nesting;
			} else if (is('}')) {
				--nesting;
			}
			advance();
		}
	}

	private Node statement() {
		int start = here();
		if (is('{')) {
			return block();
		}
		if (accept(';')) {
			return node(EMPTY, start);
		}
		if (kind() == TokenStream.KEYWORD) {
			int id = t.ids[p];
			if (id == Names.IF) {
				Node node = node(IF, start);
				advance();
				node.a = condition();
				node.b = body();
				if (isKeyword(Names.ELSE)) {
					advance();
					node.c = body();
				}
				node.end = before();
				return node;
			} else if (id == Names.FOR) {
				Node node = node(FOR, start);
				advance();
				int mark = scratchTop;
				if (expect('(')) {
					// Initializer, condition, step.
					if (!accept(';')) {
						Node init = startsDeclaration(false)
								? declaration(false)
								: expressionStatement();
						push(init != null ? init : node(EMPTY, here()));
					} else {
						push(node(EMPTY, here()));
					}
					push(is(';') ? node(EMPTY, here()) : expression());
					expect(';');
					push(is(')') ? node(EMPTY, here()) : expression());
					expect(')');
				}
				node.list = pop(mark);
				node.b = lostBrace ? null : body();
				node.end = before();
				return node;
			} else if (id == Names.WHILE) {
				Node node = node(WHILE, start);
				advance();
				node.a = condition();
				node.b = body();
				node.end = before();
				return node;
			} else if (id == Names.DO) {
				Node node = node(DO, start);
				advance();
				node.b = body();
				if (isKeyword(Names.WHILE)) {
					advance();
					node.a = condition();
				} else {
					problem("expected while");
				}
				accept(';');
				node.end = before();
				return node;
			} else if (id == Names.SWITCH) {
				Node node = node(SWITCH, start);
				advance();
				node.a = condition();
				node.b = is('{') ? block() : null;
				node.end = before();
				return node;
			} else if (id == Names.CASE || id == Names.DEFAULT) {
				Node node = node(CASE, start);
				advance();
				if (id == Names.CASE) {
					node.a = expression();
				}
				expect(':');
				node.end = before();
				return node;
			} else if (id == Names.RETURN || id == Names.BREAK ||
					id == Names.CONTINUE || id == Names.DISCARD) {
				Node node = node(JUMP, start);
				node.op = id;
				advance();
				if (id == Names.RETURN && !is(';') && !is('}') &&
						!atStatementStart()) {
					node.a = expression();
				}
				if (!accept(';')) {
					problem("expected ;");
					if (!atStatementStart()) {
						recover(false);
					}
				}
				node.end = before();
				return node;
			} else if (id == Names.ELSE) {
				problem("else without if");
				advance();
				return null;
			}
		}
		if (startsDeclaration(false)) {
			return declaration(false);
		}
		return expressionStatement();
	}

	private Node expressionStatement() {
		Node node = node(EXPRESSION, here());
		int before = p;
		inExpression = false;
		node.a = expression();
		if (!accept(';')) {
			problem("expected ;");
			if (p == before || !atStatementStart()) {
				recover(false);
			}
		}
		node.end = before();
		return node;
	}

	private Node condition() {
		if (!expect('(')) {
			return node(ERROR, here());
		}
		Node e = is(')') ? node(ERROR, here()) : expression();
		expect(')');
		return e;
	}

	private Node body() {
		if (p >= n || is('}')) {
			problem("expected a statement");
			return null;
		}
		int before = p;
		Node s = statement();
		if (p == before && !lostBrace) {
			problem("expected a statement");
		}
		return s;
	}

	// Whether p, the first token of its line, starts a statement.
	private boolean atStatementStart() {
		if (p >= n || !t.lineFirst(p)) {
			return false;
		}
		int kind = t.kind(p);
		if (kind == TokenStream.KEYWORD) {
			return true;
		}
		return (kind == TokenStream.TYPE || kind == TokenStream.IDENT) &&
				startsDeclaration(false);
	}

	/**
	 * After an error, skip to where reading can go on: past the next
	 * ";", or before a closing brace, an opening brace of a body, or a
	 * token that starts a statement on a new line.
	 */
	private void recover(boolean global) {
		int from = p;
		int nesting = 0;
		while (p < n) {
			if (is(';') && nesting == 0) {
				advance();
				return;
			} else if (is('}') || (is('{') && nesting == 0)) {
				return;
			} else if (is('(') || is('[')) {
				++nesting;
			} else if (is(')') || is(']')) {
				nesting = Math.max(0, nesting - 1);
			} else if (p > from && t.lineFirst(p) && (atStatementStart() ||
					(global && startsDeclaration(true)))) {
				return;
			}
			advance();
		}
	}

	private void skipBrackets(char open, char close) {
		int nesting = 0;
		while (p < n) {
			if (is(open)) {
				++nesting;
			} else if (is(close) && --nesting == 0) {
				advance();
				return;
			} else if (is(';') || is('{') || is('}')) {
				return;
			}
			advance();
		}
	}

	// ---- Expressions ---------------------------------------------------

	private Node expression() {
		Node first = assignment();
		if (!is(',')) {
			return first;
		}
		Node node = node(COMMA, first.start);
		int mark = scratchTop;
		push(first);
		while (accept(',')) {
			push(assignment());
		}
		node.list = pop(mark);
		node.end = before();
		return node;
	}

	private Node assignment() {
		if (++depth > 200) {
			--depth;
			problem("too deep");
			return node(ERROR, here());
		}
		Node left = ternary();
		int op = operator();
		if (op == '=' || (opLength >= 2 && (op >> (8 * (opLength - 1))) ==
				'=' && op != ('=' | '=' << 8) && op != ('!' | '=' << 8) &&
				op != ('<' | '=' << 8) && op != ('>' | '=' << 8))) {
			Node node = node(ASSIGN, left.start);
			node.op = op;
			takeOperator();
			node.a = left;
			node.b = assignment();
			node.end = before();
			left = node;
		}
		--depth;
		return left;
	}

	private Node ternary() {
		Node condition = binary(0);
		if (!is('?')) {
			return condition;
		}
		Node node = node(TERNARY, condition.start);
		advance();
		node.a = condition;
		node.b = expression();
		expect(':');
		node.c = assignment();
		node.end = before();
		return node;
	}

	private static int precedence(int op) {
		switch (op) {
			case '*':
			case '/':
			case '%':
				return 10;
			case '+':
			case '-':
				return 9;
			case '<' | '<' << 8:
			case '>' | '>' << 8:
				return 8;
			case '<':
			case '>':
			case '<' | '=' << 8:
			case '>' | '=' << 8:
				return 7;
			case '=' | '=' << 8:
			case '!' | '=' << 8:
				return 6;
			case '&':
				return 5;
			case '^':
				return 4;
			case '|':
				return 3;
			case '&' | '&' << 8:
				return 2;
			case '^' | '^' << 8:
				return 1;
			case '|' | '|' << 8:
				return 0;
			default:
				return -1;
		}
	}

	private Node binary(int min) {
		Node left = unary();
		while (true) {
			int op = operator();
			int precedence = precedence(op);
			if (precedence < min || precedence < 0) {
				return left;
			}
			takeOperator();
			Node node = node(BINARY, left.start);
			node.op = op;
			node.a = left;
			node.b = binary(precedence + 1);
			node.end = before();
			left = node;
		}
	}

	private Node unary() {
		int op = operator();
		if (op == '-' || op == '+' || op == '!' || op == '~' ||
				op == ('+' | '+' << 8) || op == ('-' | '-' << 8)) {
			if (++depth > 200) {
				--depth;
				problem("too deep");
				return node(ERROR, here());
			}
			Node node = node(UNARY, here());
			node.op = op;
			takeOperator();
			node.a = unary();
			node.end = before();
			--depth;
			return node;
		}
		return postfix();
	}

	private Node postfix() {
		Node e = primary();
		while (true) {
			if (is('.')) {
				Node node = node(FIELD, e.start);
				advance();
				node.a = e;
				if (kind() == TokenStream.IDENT ||
						kind() == TokenStream.TYPE ||
						kind() == TokenStream.KEYWORD) {
					// "a.length()" and a half-typed member both land
					// here; a keyword after a dot is the next statement.
					if (kind() == TokenStream.KEYWORD || (t.lineFirst(p) &&
							atStatementStart())) {
						problem("expected a member");
						node.end = before();
						return node;
					}
					node.name = t.ids[p];
					node.at = t.starts[p];
					advance();
					if (node.name == Names.LENGTH && is('(') &&
							isAt(next(p), ')')) {
						advance();
						advance();
					}
				} else {
					problem("expected a member");
				}
				node.end = before();
				e = node;
			} else if (is('[')) {
				Node node = node(INDEX, e.start);
				advance();
				node.a = e;
				node.b = is(']') ? node(ERROR, here()) : expression();
				expect(']');
				node.end = before();
				e = node;
			} else {
				int op = operator();
				if (op == ('+' | '+' << 8) || op == ('-' | '-' << 8)) {
					Node node = node(POSTFIX, e.start);
					node.op = op;
					takeOperator();
					node.a = e;
					node.end = before();
					e = node;
				} else {
					return e;
				}
			}
		}
	}

	// 0 for a float, 1 for an int, 2 for a uint.
	private static int numberType(String s, int start, int end) {
		char last = s.charAt(end - 1);
		if (last == 'u' || last == 'U') {
			return 2;
		}
		if (end - start > 1 && s.charAt(start) == '0' &&
				(s.charAt(start + 1) | 0x20) == 'x') {
			return 1;
		}
		for (int i = start; i < end; ++i) {
			char c = s.charAt(i);
			if (c == '.' || c == 'e' || c == 'E' || c == 'f' || c == 'F') {
				return 0;
			}
		}
		return 1;
	}

	private Node primary() {
		int start = here();
		int kind = kind();
		if (inExpression && atStatementStart()) {
			// The expression before was not finished: "a = b +" and a
			// new statement on the next line.
			problem("expected an expression");
			return node(ERROR, start);
		}
		inExpression = true;
		if (kind == TokenStream.NUMBER) {
			Node node = node(NUMBER, start);
			node.op = numberType(t.source, t.starts[p], t.ends[p]);
			advance();
			node.end = before();
			return node;
		} else if (kind == TokenStream.BOOL) {
			Node node = node(BOOL, start);
			advance();
			node.end = before();
			return node;
		} else if (kind == TokenStream.IDENT || kind == TokenStream.TYPE) {
			int id = t.ids[p];
			int i = next(p);
			boolean arrayType = false;
			if (kind == TokenStream.TYPE && isAt(i, '[')) {
				// "float[3](...)": an array constructor.
				int close = next(i);
				while (close < n && !isAt(close, ']') && !isAt(close, ';')) {
					close = next(close);
				}
				if (isAt(close, ']') && isAt(next(close), '(')) {
					arrayType = true;
					i = next(close);
				}
			}
			if (isAt(i, '(')) {
				Node node = node(CALL, start);
				node.name = id;
				node.at = start;
				node.op = (kind == TokenStream.TYPE ? 1 : 0) |
						(arrayType ? 2 : 0);
				p = i;
				advance();
				int mark = scratchTop;
				while (p < n && !is(')')) {
					int before = p;
					if (is(';') || is('}') || is('{') || atStatementStart()) {
						break;
					}
					push(assignment());
					if (!accept(',')) {
						if (p == before) {
							break;
						}
						if (!is(')')) {
							break;
						}
					}
				}
				node.list = pop(mark);
				expect(')');
				node.end = before();
				return node;
			}
			if (kind == TokenStream.TYPE) {
				problem("expected (");
			}
			Node node = node(NAME, start);
			node.name = id;
			node.at = start;
			advance();
			node.end = before();
			return node;
		} else if (is('(')) {
			advance();
			Node e = is(')') ? node(ERROR, here()) : expression();
			expect(')');
			return e;
		}
		problem("expected an expression");
		return node(ERROR, start);
	}
}
