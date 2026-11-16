package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslStatement.Token;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Kind;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * The type of an expression of GLSL, read from its tokens when it is
 * asked for: nothing of an expression is kept.
 *
 * It reads the tokens it is given and asks {@link Names} what a name
 * means where they stand, so the same tokens with the same names have
 * the same type. It takes any tokens: the type of what it cannot read
 * is not known, null, and it never guesses one.
 */
final class GlslExpression {
	/** What a name means where an expression stands. */
	interface Names {
		/** Return the type of the variable name, or null. */
		String variable(String name);

		/** Return the functions name is declared as, each a form. */
		List<Symbol> functions(String name);

		/** Return the struct named name with its fields, or null. */
		Symbol struct(String name);
	}

	// Brackets inside brackets beyond this are not read.
	private static final int MAX_DEPTH = 48;

	private final GlslStatement s;
	private final int to;
	private final Names names;
	private int at;
	private int depth;

	private GlslExpression(GlslStatement s, int from, int to, Names names,
			int depth) {
		this.s = s;
		this.at = from;
		this.to = Math.min(to, s.size());
		this.names = names;
		this.depth = depth;
	}

	/**
	 * Return the type of the expression the tokens from from to to of
	 * statement are, or null when it cannot be told.
	 */
	static String type(GlslStatement statement, int from, int to,
			Names names) {
		return new GlslExpression(statement, from, to, names, 0).whole();
	}

	/**
	 * Return the types of the arguments the tokens from from to to of
	 * statement are: what stands between the commas outside of
	 * brackets, the last one also when it is not finished. A type that
	 * cannot be told is null.
	 */
	static List<String> arguments(GlslStatement statement, int from, int to,
			Names names) {
		return arguments(statement, from, to, names, 0);
	}

	private static List<String> arguments(GlslStatement statement, int from,
			int to, Names names, int depth) {
		List<String> types = new ArrayList<>();
		to = Math.min(to, statement.size());
		for (int start = from; start < to; ) {
			int end = statement.afterDeclarator(start, to);
			types.add(new GlslExpression(statement, start, end, names,
					depth).whole());
			start = end + 1;
		}
		return types;
	}

	/**
	 * Return where the value starts that ends before token end of
	 * statement: a name, a call or a group, with the members, indices
	 * and calls behind it, as in "rays[i].origin" or "normalize(p)".
	 */
	static int valueStart(GlslStatement statement, int end) {
		int i = end;
		while (i > 0) {
			int last = i - 1;
			char c = statement.punct(last);
			if (c == ')' || c == ']') {
				int open = statement.opening(last);
				if (open < 0) {
					return end;
				}
				i = open;
				if (c == ']') {
					// What is indexed stands before it.
					continue;
				} else if (open > 0 && isWord(statement.kind(open - 1))) {
					i = open - 1;
				}
			} else if (isWord(statement.kind(last)) ||
					statement.kind(last) == Token.NUMBER ||
					statement.kind(last) == Token.OTHER) {
				i = last;
			} else {
				return i;
			}
			if (i > 0 && statement.punct(i - 1) == '.') {
				--i;
				continue;
			}
			return i;
		}
		return 0;
	}

	/**
	 * Return the first of forms, functions of one name, whose
	 * parameters take arguments of these types; with whole, one that
	 * takes exactly so many. Null when none does.
	 */
	static Symbol form(List<Symbol> forms, List<String> arguments,
			boolean whole) {
		for (Symbol form : forms) {
			List<String> parameters = parameters(form);
			if ((!whole || parameters.size() == arguments.size()) &&
					GlslTypes.accepts(parameters, arguments)) {
				return form;
			}
		}
		return null;
	}

	/** Return the types of the parameters of function, in their order. */
	static List<String> parameters(Symbol function) {
		List<String> types = new ArrayList<>(function.parts().size());
		for (Symbol part : function.parts()) {
			types.add(part.type());
		}
		return types;
	}

	private static boolean isWord(Token token) {
		return token == Token.NAME || token == Token.TYPE;
	}

	// ---- From the loosest operator to the tightest ----------------------

	// The type of all the tokens: what something stands behind that is
	// no part of it has none. An operator with nothing behind it yet
	// is part of it.
	private String whole() {
		String type = expression();
		return at >= to ? type : null;
	}

	private String expression() {
		String type = assignment();
		while (is(',')) {
			++at;
			type = assignment();
		}
		return type;
	}

	// "a = b", "a += b": what is assigned to keeps its type.
	private String assignment() {
		String left = ternary();
		while (at < to) {
			char c = s.punct(at);
			if (c == '=' && !pair('=')) {
				++at;
			} else if (c != 0 && "+-*/%&|^".indexOf(c) >= 0 && next('=')) {
				at += 2;
			} else {
				break;
			}
			ternary();
		}
		return left;
	}

	private String ternary() {
		String type = logical();
		if (is('?')) {
			++at;
			if (++depth > MAX_DEPTH) {
				at = to;
				return null;
			}
			String yes = assignment();
			String no = null;
			if (is(':')) {
				++at;
				no = assignment();
			}
			--depth;
			return yes != null ? yes : no;
		}
		return type;
	}

	// "&&", "||" and "^^" give a bool.
	private String logical() {
		String type = comparison();
		while (at < to && (pair('&') || pair('|') || pair('^'))) {
			at += 2;
			comparison();
			type = "bool";
		}
		return type;
	}

	// "<", ">", "<=", ">=", "==" and "!=" give a bool; "<<" and ">>"
	// what they shift.
	private String comparison() {
		String type = sum();
		while (at < to) {
			char c = s.punct(at);
			if ((c == '<' || c == '>') && pair(c)) {
				at += 2;
				if (is('=')) {
					++at;
					assignment();
				} else {
					sum();
				}
			} else if (c == '<' || c == '>') {
				at += next('=') ? 2 : 1;
				sum();
				type = "bool";
			} else if ((c == '=' || c == '!') && next('=')) {
				at += 2;
				sum();
				type = "bool";
			} else {
				break;
			}
		}
		return type;
	}

	// "+" and "-", and "&", "|" and "^" of whole numbers.
	private String sum() {
		String type = product();
		while (at < to) {
			char c = s.punct(at);
			if ((c != '+' && c != '-' && c != '&' && c != '|' &&
					c != '^') || next('=') || ((c == '&' || c == '|' ||
					c == '^') && pair(c))) {
				break;
			}
			if (++at >= to) {
				// Not written yet.
				break;
			}
			type = GlslTypes.arithmetic(type, c, product());
		}
		return type;
	}

	private String product() {
		String type = unary();
		while (at < to) {
			char c = s.punct(at);
			if ((c != '*' && c != '/' && c != '%') || next('=')) {
				break;
			}
			if (++at >= to) {
				break;
			}
			type = GlslTypes.arithmetic(type, c, unary());
		}
		return type;
	}

	private String unary() {
		boolean not = false;
		while (at < to) {
			char c = s.punct(at);
			if (c != '+' && c != '-' && c != '!' && c != '~') {
				break;
			}
			not |= c == '!';
			++at;
		}
		String type = postfix();
		return not ? "bool" : type;
	}

	// A value and what stands behind it: a member, an index, "++".
	private String postfix() {
		String type = primary();
		while (at < to) {
			char c = s.punct(at);
			if (c == '.' && at + 1 < to && isWord(s.kind(at + 1))) {
				String name = s.text(at + 1);
				at += 2;
				if (is('(')) {
					// The one method there is: the length of an array.
					at = s.afterClosing(at, to);
					type = name.equals("length") ? "int" : null;
				} else {
					type = member(type, name);
				}
			} else if (c == '[') {
				int open = at++;
				if (++depth <= MAX_DEPTH) {
					expression();
				}
				--depth;
				at = s.afterClosing(open, to);
				type = GlslTypes.element(type);
			} else if ((c == '+' || c == '-') && pair(c)) {
				at += 2;
			} else {
				break;
			}
		}
		return type;
	}

	private String primary() {
		if (at >= to) {
			return null;
		}
		Token kind = s.kind(at);
		if (kind == Token.NUMBER) {
			return GlslTypes.literal(s.text(at++));
		} else if (kind == Token.OTHER) {
			boolean bool = s.is(at, "true") || s.is(at, "false");
			++at;
			return bool ? "bool" : null;
		} else if (kind == Token.TYPE) {
			// A constructor: it gives its type, of an array with its
			// brackets.
			String type = s.text(at++);
			if (is('[')) {
				int end = s.afterClosing(at, to);
				type += s.text(at, end).replace(" ", "");
				at = end;
			}
			if (is('(')) {
				at = s.afterClosing(at, to);
			}
			return type;
		} else if (kind == Token.NAME) {
			String name = s.text(at++);
			return is('(') ? call(name) : names.variable(name);
		} else if (is('(')) {
			int open = at++;
			String type = null;
			if (++depth <= MAX_DEPTH) {
				type = expression();
			}
			--depth;
			at = s.afterClosing(open, to);
			return type;
		}
		return null;
	}

	// A call of name, whose "(" is the next token.
	private String call(String name) {
		int open = at++;
		// Up to the ")" if there is one.
		int closed = s.closing(open, to);
		List<String> arguments = null;
		if (++depth <= MAX_DEPTH) {
			arguments = arguments(s, at, closed < 0 ? to : closed, names,
					depth);
		}
		--depth;
		at = closed < 0 ? to : closed + 1;
		if (names.struct(name) != null) {
			return name;
		} else if (arguments == null) {
			return null;
		}
		List<Symbol> forms = names.functions(name);
		if (forms.isEmpty()) {
			return GlslTypes.builtin(name, arguments);
		}
		// What the forms return that take them, as far as the types of
		// the arguments are known, if they all return the same; if none
		// takes them, what every form returns.
		String type = null;
		boolean taken = false;
		for (int pass = 0; pass < 2 && !taken; ++pass) {
			for (Symbol form : forms) {
				if (pass == 0 && (form.parts().size() != arguments.size() ||
						!GlslTypes.accepts(parameters(form), arguments))) {
					continue;
				} else if (form.type() == null || (taken &&
						!form.type().equals(type))) {
					return null;
				}
				type = form.type();
				taken = true;
			}
		}
		return type;
	}

	// The field of a struct, or the components of a vector.
	private String member(String type, String name) {
		if (type == null) {
			return null;
		}
		Symbol struct = names.struct(type);
		if (struct == null) {
			return GlslTypes.swizzle(type, name);
		}
		for (Symbol field : struct.parts()) {
			if (field.kind() == Kind.FIELD && field.name().equals(name)) {
				return field.type();
			}
		}
		return null;
	}

	private boolean is(char c) {
		return at < to && s.punct(at) == c;
	}

	// Whether the next token is c and stands right behind this one.
	private boolean next(char c) {
		return at + 1 < to && s.punct(at + 1) == c &&
				s.start(at + 1) == s.end(at);
	}

	// Whether this token and the next are both c, written as one.
	private boolean pair(char c) {
		return s.punct(at) == c && next(c);
	}
}
