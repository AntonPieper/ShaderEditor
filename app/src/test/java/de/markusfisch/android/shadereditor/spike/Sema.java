package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.spike.Parser.Node;
import de.markusfisch.android.shadereditor.spike.Types.Type;

/**
 * SPIKE, throwaway: option B's second half. One walk over the tree in
 * the order of the text gives every name the declaration it refers to
 * (scopes as GLSL has them: a name is known from its declaration to the
 * end of its block) and every expression its type, with the overloads of
 * built-in and own functions resolved from the argument types. A macro
 * whose body is an expression is typed like an inline function: its
 * body with the types of the arguments.
 */
final class Sema {
	static final int VARIABLE = 0;
	static final int PARAMETER = 1;
	static final int FUNCTION = 2;
	static final int STRUCT = 3;
	static final int MACRO = 4;
	static final int FIELD = 5;
	static final int BUILTIN = 6;

	/** What a name refers to. */
	static final class Symbol {
		final int kind;
		final int name;
		Type type;
		// Where it is declared: the declarator, parameter, function or
		// struct node; null for a built-in and a macro.
		Node declaration;
		// The symbol this one hides while it is in scope, and for a
		// function its next overload.
		Symbol outer;
		Symbol next;
		Type[] parameters;
		int directive = -1;
		int[] macroParameters;
		Node macroBody;
		boolean macroRead;
		// The type a macro stands for, unknown if none, null if not
		// looked at yet.
		Type alias;
		// Declared by another file, not by this text.
		boolean outside;
		int uses;

		Symbol(int kind, int name, Type type, Node declaration) {
			this.kind = kind;
			this.name = name;
			this.type = type;
			this.declaration = declaration;
		}
	}

	final TokenStream t;
	final Node unit;
	// After the walk: the global meaning of every name, by its number.
	Symbol[] visible;
	final List<Symbol> globals = new ArrayList<>();
	int expressions;
	int typed;
	int names;
	int unresolved;
	private Symbol[] scope = new Symbol[64];
	private int scopeTop;
	private Symbol[] builtins = new Symbol[0];
	private Type[] arguments = new Type[16];
	private int argumentTop;
	private int macroDepth;

	private Sema(TokenStream t, Node unit) {
		this.t = t;
		this.unit = unit;
		visible = new Symbol[t.names.size() + 16];
	}

	static Sema analyse(TokenStream t, Node unit) {
		return analyse(t, unit, null);
	}

	/**
	 * Analyse unit after the declarations of prelude, which stand for
	 * what other files declare; they are known but not listed.
	 */
	static Sema analyse(TokenStream t, Node unit, Node prelude) {
		Sema sema = new Sema(t, unit);
		if (prelude != null) {
			for (Node node : prelude.list) {
				sema.external(node);
			}
			for (Symbol symbol : sema.globals) {
				symbol.outside = true;
			}
			sema.globals.clear();
			sema.expressions = sema.typed = sema.names = 0;
			sema.unresolved = 0;
		}
		sema.run();
		return sema;
	}

	private void run() {
		for (int d = 0; d < t.directives; ++d) {
			if (t.directiveKinds[d] == TokenStream.D_DEFINE &&
					t.directiveNames[d] >= 0 && t.directiveActive[d]) {
				Symbol macro = new Symbol(MACRO, t.directiveNames[d],
						Types.T_UNKNOWN, null);
				macro.directive = d;
				declare(macro);
				globals.add(macro);
			}
		}
		int mark = scopeTop;
		for (Node node : unit.list) {
			external(node);
		}
		// Globals stay visible: drop the stack without restoring.
		scopeTop = mark;
	}

	// ---- Scopes --------------------------------------------------------

	Symbol lookup(int name) {
		return name >= 0 && name < visible.length ? visible[name] : null;
	}

	private void declare(Symbol symbol) {
		int name = symbol.name;
		if (name < 0) {
			return;
		}
		if (name >= visible.length) {
			visible = Arrays.copyOf(visible, name * 2);
		}
		symbol.outer = visible[name];
		visible[name] = symbol;
		if (scopeTop == scope.length) {
			scope = Arrays.copyOf(scope, scopeTop * 2);
		}
		scope[scopeTop++] = symbol;
	}

	private void leave(int mark) {
		while (scopeTop > mark) {
			Symbol symbol = scope[--scopeTop];
			visible[symbol.name] = symbol.outer;
		}
	}

	/**
	 * Walk the body of one function again, with the globals as they
	 * are: what a re-analysis of only the function that changed does.
	 */
	void again(Node function) {
		if (function.b == null) {
			return;
		}
		int mark = scopeTop;
		for (Node p : function.list) {
			if (p.name >= 0) {
				Symbol s = new Symbol(PARAMETER, p.name, p.a != null &&
						p.a.type != null ? p.a.type : Types.T_UNKNOWN, p);
				p.symbol = s;
				declare(s);
			}
		}
		block(function.b, false);
		leave(mark);
	}

	// ---- Declarations --------------------------------------------------

	private void external(Node node) {
		if (node.kind == Parser.FUNCTION) {
			function(node);
		} else if (node.kind == Parser.DECLARATION) {
			declaration(node, true);
		}
	}

	private void function(Node node) {
		Type result = resolve(node.a);
		Type[] parameters = new Type[node.list.length];
		for (int i = 0; i < parameters.length; ++i) {
			Node p = node.list[i];
			Type type = resolve(p.a);
			parameters[i] = p.op == 1 ? Types.array(type, -1) : type;
		}
		// "f(void)" has no parameters.
		if (parameters.length == 1 && parameters[0] == Types.T_VOID) {
			parameters = new Type[0];
		}
		Symbol symbol = new Symbol(FUNCTION, node.name, result, node);
		symbol.parameters = parameters;
		Symbol before = lookup(node.name);
		if (before != null && before.kind == FUNCTION) {
			symbol.next = before;
			// Replace it in place so the chain stays one global name.
			visible[node.name] = symbol;
			symbol.outer = before.outer;
		} else {
			declare(symbol);
		}
		globals.add(symbol);
		node.symbol = symbol;
		if (node.b == null) {
			return;
		}
		int mark = scopeTop;
		for (int i = 0; i < node.list.length; ++i) {
			Node p = node.list[i];
			if (p.name >= 0) {
				Symbol s = new Symbol(PARAMETER, p.name,
						i < parameters.length ? parameters[i]
								: Types.T_UNKNOWN, p);
				p.symbol = s;
				declare(s);
			}
		}
		block(node.b, false);
		leave(mark);
	}

	private void declaration(Node node, boolean global) {
		Type type = resolve(node.a);
		for (Node d : node.list) {
			Type declared = d.op == 1 ? Types.array(type, -1) : type;
			if (d.b != null) {
				expression(d.b);
			}
			if (d.a != null) {
				expression(d.a);
			}
			Symbol symbol = new Symbol(VARIABLE, d.name, declared, d);
			d.symbol = symbol;
			d.type = declared;
			declare(symbol);
			if (global) {
				globals.add(symbol);
			}
		}
	}

	// The type a TYPE node names; declares a struct written out in it.
	private Type resolve(Node node) {
		if (node == null) {
			return Types.T_UNKNOWN;
		}
		Type type;
		if (node.a != null) {
			type = struct(node.a);
		} else {
			type = Types.byNameId(node.name);
			if (type == null) {
				Symbol symbol = lookup(node.name);
				if (symbol != null && symbol.kind == STRUCT) {
					type = symbol.type;
					node.symbol = symbol;
					++symbol.uses;
				} else if (symbol != null && symbol.kind == MACRO) {
					// "#define V vec3": a macro that is a type's name.
					node.symbol = symbol;
					++symbol.uses;
					type = alias(symbol);
				} else {
					type = Types.T_UNKNOWN;
				}
			}
		}
		if (node.b != null) {
			if (node.b.kind != Parser.EMPTY) {
				expression(node.b);
			}
			type = Types.array(type, -1);
		}
		node.type = type;
		return type;
	}

	private Type alias(Symbol macro) {
		if (macro.alias == null) {
			macro.alias = readAlias(macro);
		}
		return macro.alias;
	}

	private Type readAlias(Symbol macro) {
		int d = macro.directive;
		int from = t.directiveBodies[d];
		int to = t.directiveEnds[d];
		if (from < 0 || from >= to) {
			return Types.T_UNKNOWN;
		}
		TokenStream body = TokenStream.fragment(t, from, to);
		if (body.size != 1) {
			return Types.T_UNKNOWN;
		}
		Type type = Types.byNameId(body.ids[0]);
		if (type == null) {
			Symbol symbol = lookup(body.ids[0]);
			type = symbol != null && symbol.kind == STRUCT ? symbol.type
					: Types.T_UNKNOWN;
		}
		return type;
	}

	private Type struct(Node node) {
		Type type = Types.struct(node.name >= 0 ? t.names.name(node.name)
				: "struct");
		int count = 0;
		for (Node member : node.list) {
			count += member.list.length;
		}
		int[] fieldNames = new int[count];
		Type[] fieldTypes = new Type[count];
		Symbol[] fields = new Symbol[count];
		int i = 0;
		for (Node member : node.list) {
			Type memberType = resolve(member.a);
			for (Node d : member.list) {
				Type declared = d.op == 1 ? Types.array(memberType, -1)
						: memberType;
				fieldNames[i] = d.name;
				fieldTypes[i] = declared;
				fields[i] = new Symbol(FIELD, d.name, declared, d);
				d.symbol = fields[i];
				d.type = declared;
				++i;
			}
		}
		type.fieldNames = fieldNames;
		type.fieldTypes = fieldTypes;
		type.declaration = fields;
		Symbol symbol = new Symbol(STRUCT, node.name, type, node);
		node.symbol = symbol;
		if (node.name >= 0) {
			declare(symbol);
			globals.add(symbol);
		}
		return type;
	}

	// ---- Statements ----------------------------------------------------

	private void block(Node node, boolean ownScope) {
		int mark = scopeTop;
		for (Node s : node.list) {
			statement(s);
		}
		if (ownScope) {
			leave(mark);
		}
	}

	private void statement(Node node) {
		if (node == null) {
			return;
		}
		switch (node.kind) {
			case Parser.BLOCK:
				block(node, true);
				break;
			case Parser.DECLARATION:
				declaration(node, false);
				break;
			case Parser.EXPRESSION:
				expression(node.a);
				break;
			case Parser.IF:
				expression(node.a);
				scoped(node.b);
				scoped(node.c);
				break;
			case Parser.FOR: {
				int mark = scopeTop;
				for (Node part : node.list) {
					if (part.kind == Parser.DECLARATION) {
						declaration(part, false);
					} else if (part.kind == Parser.EXPRESSION) {
						expression(part.a);
					} else if (part.kind != Parser.EMPTY) {
						expression(part);
					}
				}
				statement(node.b);
				leave(mark);
				break;
			}
			case Parser.WHILE:
			case Parser.DO:
			case Parser.SWITCH:
				expression(node.a);
				scoped(node.b);
				break;
			case Parser.CASE:
			case Parser.JUMP:
				if (node.a != null) {
					expression(node.a);
				}
				break;
			default:
				break;
		}
	}

	private void scoped(Node node) {
		if (node != null) {
			int mark = scopeTop;
			statement(node);
			leave(mark);
		}
	}

	// ---- Expressions ---------------------------------------------------

	Type expression(Node node) {
		if (node == null) {
			return Types.T_UNKNOWN;
		}
		Type type = type(node);
		node.type = type;
		if (macroDepth == 0) {
			++expressions;
			if (type.kind != Types.UNKNOWN) {
				++typed;
			}
		}
		return type;
	}

	private Type type(Node node) {
		switch (node.kind) {
			case Parser.NUMBER:
				return number(node);
			case Parser.BOOL:
				return Types.T_BOOL;
			case Parser.NAME:
				return name(node);
			case Parser.BINARY:
				return binary(node.op, expression(node.a),
						expression(node.b));
			case Parser.UNARY: {
				Type a = expression(node.a);
				return node.op == '!' ? Types.T_BOOL : a;
			}
			case Parser.POSTFIX:
				return expression(node.a);
			case Parser.ASSIGN: {
				Type a = expression(node.a);
				Type b = expression(node.b);
				return a.kind != Types.UNKNOWN ? a : b;
			}
			case Parser.TERNARY: {
				expression(node.a);
				Type b = expression(node.b);
				Type c = expression(node.c);
				return b.kind != Types.UNKNOWN ? b : c;
			}
			case Parser.CALL:
				return call(node);
			case Parser.FIELD: {
				Type a = expression(node.a);
				if (node.name < 0) {
					return Types.T_UNKNOWN;
				}
				if (a.kind == Types.STRUCT && a.fieldNames != null) {
					for (int i = 0; i < a.fieldNames.length; ++i) {
						if (a.fieldNames[i] == node.name) {
							if (a.declaration instanceof Symbol[]) {
								node.symbol = ((Symbol[]) a.declaration)[i];
								++node.symbol.uses;
							}
							return a.fieldTypes[i];
						}
					}
					return Types.T_UNKNOWN;
				}
				String member = t.names.name(node.name);
				return Types.member(a, member, 0, member.length(),
						node.name);
			}
			case Parser.INDEX: {
				Type a = expression(node.a);
				expression(node.b);
				return a.kind == Types.ARRAY ? a.element
						: a.kind == Types.VECTOR ? Types.vector(a.base, 1)
						: a.kind == Types.MATRIX
								? Types.vector(Types.FLOAT, a.rows)
						: Types.T_UNKNOWN;
			}
			case Parser.COMMA: {
				Type last = Types.T_UNKNOWN;
				for (Node e : node.list) {
					last = expression(e);
				}
				return last;
			}
			default:
				return Types.T_UNKNOWN;
		}
	}

	private Type number(Node node) {
		return node.op == 0 ? Types.T_FLOAT : node.op == 1 ? Types.T_INT
				: Types.T_UINT;
	}

	private Type name(Node node) {
		if (macroDepth == 0) {
			++names;
		}
		Symbol symbol = lookup(node.name);
		if (symbol == null) {
			Type builtin = Builtins.variable(node.name);
			if (builtin != null) {
				node.symbol = builtin(node.name, builtin);
				return builtin;
			}
			if (macroDepth == 0) {
				++unresolved;
			}
			return Types.T_UNKNOWN;
		}
		node.symbol = symbol;
		++symbol.uses;
		if (symbol.kind == MACRO) {
			return macro(symbol, 0, 0);
		}
		return symbol.kind == VARIABLE || symbol.kind == PARAMETER
				? symbol.type : Types.T_UNKNOWN;
	}

	private Symbol builtin(int name, Type type) {
		if (name >= builtins.length) {
			builtins = Arrays.copyOf(builtins, Math.max(512, name * 2));
		}
		Symbol symbol = builtins[name];
		if (symbol == null) {
			symbol = new Symbol(BUILTIN, name, type, null);
			builtins[name] = symbol;
		}
		++symbol.uses;
		return symbol;
	}

	private Type call(Node node) {
		int base = argumentTop;
		int count = node.list.length;
		if (base + count > arguments.length) {
			arguments = Arrays.copyOf(arguments, (base + count) * 2);
		}
		argumentTop += count;
		for (int i = 0; i < count; ++i) {
			// A call among the arguments may replace the array, so the
			// type is taken first and stored after.
			Type argument = expression(node.list[i]);
			arguments[base + i] = argument;
		}
		Type result = resolveCall(node, base, count);
		argumentTop = base;
		return result;
	}

	private Type resolveCall(Node node, int base, int count) {
		if ((node.op & 1) != 0) {
			Type type = Types.byNameId(node.name);
			if (type == null) {
				return Types.T_UNKNOWN;
			}
			return (node.op & 2) != 0 ? Types.array(type, -1) : type;
		}
		if (macroDepth == 0) {
			++names;
		}
		Symbol symbol = lookup(node.name);
		if (symbol != null && symbol.kind == STRUCT) {
			node.symbol = symbol;
			++symbol.uses;
			return symbol.type;
		} else if (symbol != null && symbol.kind == MACRO) {
			node.symbol = symbol;
			++symbol.uses;
			// "#define V3 vec3" and "V3(1.0)": a constructor.
			Type alias = alias(symbol);
			return alias.kind != Types.UNKNOWN ? alias
					: macro(symbol, base, count);
		} else if (symbol != null && symbol.kind == FUNCTION) {
			Symbol byCount = null;
			for (Symbol f = symbol; f != null; f = f.next) {
				if (f.parameters.length != count) {
					continue;
				}
				if (byCount == null) {
					byCount = f;
				}
				boolean fits = true;
				for (int i = 0; i < count && fits; ++i) {
					Type a = arguments[base + i];
					Type p = f.parameters[i];
					fits = a.kind == Types.UNKNOWN ||
							p.kind == Types.UNKNOWN || same(a, p);
				}
				if (fits) {
					byCount = f;
					break;
				}
			}
			Symbol chosen = byCount != null ? byCount : symbol;
			node.symbol = chosen;
			++chosen.uses;
			return chosen.type;
		}
		Builtins.Function function = Builtins.function(node.name);
		if (function != null) {
			Type[] args = Arrays.copyOfRange(arguments, base, base + count);
			for (Builtins.Signature s : function.signatures) {
				Type r = s.apply(args, count, false);
				if (r != null) {
					node.symbol = builtin(node.name, r);
					return r;
				}
			}
			node.symbol = builtin(node.name, Types.T_UNKNOWN);
			return Types.T_UNKNOWN;
		}
		if (macroDepth == 0) {
			++unresolved;
		}
		return Types.T_UNKNOWN;
	}

	private static boolean same(Type a, Type b) {
		if (a == b) {
			return true;
		}
		if (a.kind == Types.ARRAY && b.kind == Types.ARRAY) {
			return same(a.element, b.element);
		}
		return a.kind == Types.STRUCT && b.kind == Types.STRUCT &&
				a.name.equals(b.name);
	}

	// The type of a use of a macro: its body, read as an expression,
	// with the parameters as variables of the arguments' types.
	private Type macro(Symbol macro, int base, int count) {
		if (!macro.macroRead) {
			macro.macroRead = true;
			readMacro(macro);
		}
		if (macro.macroBody == null || macroDepth > 8) {
			return Types.T_UNKNOWN;
		}
		int[] parameters = macro.macroParameters;
		if (parameters.length == 0 && macro.type.kind != Types.UNKNOWN) {
			return macro.type;
		}
		int mark = scopeTop;
		for (int i = 0; i < parameters.length; ++i) {
			declare(new Symbol(VARIABLE, parameters[i], i < count
					? arguments[base + i] : Types.T_UNKNOWN, null));
		}
		++macroDepth;
		Type type = expression(macro.macroBody);
		--macroDepth;
		leave(mark);
		if (parameters.length == 0) {
			macro.type = type;
		}
		return type;
	}

	private void readMacro(Symbol macro) {
		int d = macro.directive;
		int from = t.directiveBodies[d];
		int to = t.directiveEnds[d];
		macro.macroParameters = new int[0];
		if (from < 0 || from >= to) {
			return;
		}
		TokenStream body = TokenStream.fragment(t, from, to);
		int first = 0;
		if (body.size > 0 && body.starts[0] == from &&
				body.kind(0) == TokenStream.PUNCT && body.charAt(0) == '(') {
			// Parameters stand right behind the name.
			int[] parameters = new int[8];
			int size = 0;
			int i = 1;
			for (; i < body.size; ++i) {
				if (body.kind(i) == TokenStream.IDENT) {
					if (size == parameters.length) {
						parameters = Arrays.copyOf(parameters, size * 2);
					}
					parameters[size++] = body.ids[i];
				} else if (body.kind(i) == TokenStream.PUNCT &&
						body.charAt(i) == ')') {
					break;
				}
			}
			macro.macroParameters = Arrays.copyOf(parameters, size);
			first = i + 1;
		}
		Parser parser = new Parser(body);
		parser.from(first);
		macro.macroBody = parser.wholeExpression();
	}

	static Type binary(int op, Type a, Type b) {
		switch (op) {
			case '<':
			case '>':
			case '<' | '=' << 8:
			case '>' | '=' << 8:
			case '=' | '=' << 8:
			case '!' | '=' << 8:
			case '&' | '&' << 8:
			case '|' | '|' << 8:
			case '^' | '^' << 8:
				return Types.T_BOOL;
			default:
				break;
		}
		if (a.kind == Types.UNKNOWN || b.kind == Types.UNKNOWN) {
			Type known = a.kind == Types.UNKNOWN ? b : a;
			// A vector or matrix times something is most often itself;
			// a scalar may be widened by the unknown side.
			return known.kind == Types.VECTOR ? known : Types.T_UNKNOWN;
		}
		if (!a.isNumeric() || !b.isNumeric()) {
			return Types.T_UNKNOWN;
		}
		if (a.kind == Types.SCALAR) {
			return b;
		} else if (b.kind == Types.SCALAR) {
			return a;
		} else if (op == '*' && a.kind == Types.MATRIX &&
				b.kind == Types.VECTOR) {
			return Types.vector(Types.FLOAT, a.rows);
		} else if (op == '*' && a.kind == Types.VECTOR &&
				b.kind == Types.MATRIX) {
			return Types.vector(Types.FLOAT, b.size);
		} else if (op == '*' && a.kind == Types.MATRIX &&
				b.kind == Types.MATRIX) {
			return Types.MATRICES[b.size - 2][a.rows - 2];
		}
		return a;
	}

	// ---- Questions about a position -----------------------------------

	/** The innermost node whose range holds offset, or null. */
	Node nodeAt(int offset) {
		Node best = null;
		Node node = unit;
		while (node != null) {
			Node inner = null;
			for (Node child : children(node)) {
				if (child != null && child.start <= offset &&
						offset < child.end) {
					inner = child;
					break;
				}
			}
			if (inner == null) {
				break;
			}
			best = inner;
			node = inner;
		}
		return best;
	}

	private static Node[] children(Node node) {
		int extra = (node.a != null ? 1 : 0) + (node.b != null ? 1 : 0) +
				(node.c != null ? 1 : 0);
		if (extra == 0) {
			return node.list;
		}
		Node[] all = new Node[extra + node.list.length];
		int i = 0;
		if (node.a != null) {
			all[i++] = node.a;
		}
		if (node.kind == Parser.FUNCTION || node.kind == Parser.FOR ||
				node.kind == Parser.STRUCT) {
			for (Node child : node.list) {
				all[i++] = child;
			}
			if (node.b != null) {
				all[i++] = node.b;
			}
		} else {
			if (node.b != null) {
				all[i++] = node.b;
			}
			for (Node child : node.list) {
				all[i++] = child;
			}
		}
		if (node.c != null) {
			all[i] = node.c;
		}
		return all;
	}

	/** The symbol the name at offset refers to, or null. */
	Symbol symbolAt(int offset) {
		Node node = nodeAt(offset);
		while (node != null) {
			if (node.symbol != null && node.name >= 0 && node.at <= offset &&
					offset <= node.at + t.names.name(node.name).length()) {
				return node.symbol;
			}
			// A type's name inside a declaration.
			if (node.kind == Parser.TYPE && node.symbol != null) {
				return node.symbol;
			}
			return null;
		}
		return null;
	}

	/**
	 * Collect where symbol is named in this text, the declaration
	 * included: pairs of start and end.
	 */
	int[] references(Symbol symbol) {
		int[] out = new int[16];
		int[] size = {0};
		int length = t.names.name(symbol.name).length();
		out = references(unit, symbol, length, out, size);
		return Arrays.copyOf(out, size[0]);
	}

	private int[] references(Node node, Symbol symbol, int length,
			int[] out, int[] size) {
		boolean hit = node.symbol == symbol;
		if (!hit && node.symbol != null && symbol.kind == FUNCTION &&
				node.symbol.kind == FUNCTION &&
				node.symbol.name == symbol.name) {
			// Overloads share a name; a rename moves all of them.
			hit = true;
		}
		if (hit && node.name == symbol.name) {
			if (size[0] + 2 > out.length) {
				out = Arrays.copyOf(out, out.length * 2);
			}
			out[size[0]++] = node.at;
			out[size[0]++] = node.at + length;
		}
		if (node.a != null) {
			out = references(node.a, symbol, length, out, size);
		}
		if (node.b != null) {
			out = references(node.b, symbol, length, out, size);
		}
		if (node.c != null) {
			out = references(node.c, symbol, length, out, size);
		}
		for (Node child : node.list) {
			if (child != null) {
				out = references(child, symbol, length, out, size);
			}
		}
		return out;
	}

	/**
	 * Return the type of expression, which was parsed from text newer
	 * than this analysis (the line being typed), as it would be at
	 * offset of the analysed text: with the parameters and locals that
	 * are in scope there.
	 */
	Type typeAt(Node expression, int offset) {
		int mark = scopeTop;
		for (Node node : unit.list) {
			if (node.kind == Parser.FUNCTION && node.start <= offset &&
					offset <= node.end) {
				for (Node p : node.list) {
					if (p.symbol != null) {
						declare(p.symbol);
					}
				}
				if (node.b != null) {
					declareLocals(node.b, offset);
				}
				break;
			}
		}
		++macroDepth;
		Type type = expression(expression);
		--macroDepth;
		leave(mark);
		return type;
	}

	private void declareLocals(Node node, int offset) {
		if (node == null || node.start > offset) {
			return;
		}
		if (node.kind == Parser.DECLARATION) {
			for (Node d : node.list) {
				if (d.symbol != null && d.at < offset) {
					declare(d.symbol);
				}
			}
			return;
		}
		if ((node.kind == Parser.BLOCK || node.kind == Parser.FOR) &&
				offset > node.end) {
			return;
		}
		for (Node child : node.list) {
			declareLocals(child, offset);
		}
		if (node.kind != Parser.EXPRESSION) {
			declareLocals(node.b, offset);
			declareLocals(node.c, offset);
		}
	}

	/**
	 * Return the type of the variable name as seen from offset, by
	 * walking the function around offset again: for a question about a
	 * text newer than this analysis, where the tree has no node yet.
	 */
	Type typeOfNameAt(int name, int offset) {
		Symbol[] found = {null};
		for (Node node : unit.list) {
			if (node.kind == Parser.FUNCTION && node.start <= offset &&
					offset <= node.end) {
				for (Node p : node.list) {
					if (p.name == name) {
						found[0] = p.symbol;
					}
				}
				if (node.b != null) {
					localAt(node.b, name, offset, found);
				}
			}
		}
		Symbol symbol = found[0] != null ? found[0] : lookup(name);
		if (symbol == null) {
			Type builtin = Builtins.variable(name);
			return builtin != null ? builtin : Types.T_UNKNOWN;
		}
		if (symbol.kind == MACRO) {
			return macro(symbol, 0, 0);
		}
		return symbol.kind == VARIABLE || symbol.kind == PARAMETER
				? symbol.type : Types.T_UNKNOWN;
	}

	private void localAt(Node node, int name, int offset, Symbol[] found) {
		if (node == null || node.start > offset) {
			return;
		}
		if (node.kind == Parser.DECLARATION) {
			for (Node d : node.list) {
				if (d.name == name && d.at < offset) {
					found[0] = d.symbol;
				}
			}
			return;
		}
		boolean scope = node.kind == Parser.BLOCK ||
				node.kind == Parser.FOR;
		if (scope && offset > node.end) {
			// Past this block: what it declared is gone.
			return;
		}
		for (Node child : node.list) {
			localAt(child, name, offset, found);
		}
		if (node.kind != Parser.EXPRESSION) {
			localAt(node.b, name, offset, found);
			localAt(node.c, name, offset, found);
		}
	}
}
