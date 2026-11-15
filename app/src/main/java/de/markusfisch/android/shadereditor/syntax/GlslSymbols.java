package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslStatement.Token;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Kind;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * What a statement of GLSL declares: a function with its parameters,
 * variables, a struct with its fields, or a block.
 *
 * It reads nothing but the tokens it is given and keeps nothing, so the
 * same tokens declare the same symbols wherever they stood. It takes any
 * tokens: what is no declaration declares nothing, and a name needs a
 * type before it to be declared.
 */
final class GlslSymbols {
	/** A symbol while what is read behind it may still change it. */
	static final class Draft {
		String name;
		Kind kind;
		String detail;
		String documentation;
		int start;
		int end;
		int from;
		int to;
		List<Symbol> parts = Collections.emptyList();

		Symbol symbol() {
			return new Symbol(name, kind, detail, documentation, null, start,
					end, from, to, parts);
		}
	}

	static final int MAX_DETAIL = 240;
	private static final int MAX_PARTS = 256;

	private GlslSymbols() {
	}

	/**
	 * Add to out, in the order of the source, what the first size tokens
	 * of statement declare.
	 *
	 * @param end           where the declaration ends in the source
	 * @param documentation the comment that belongs to it, or null
	 */
	static void of(GlslStatement statement, int size, int end,
			String documentation, List<Draft> out) {
		size = Math.min(size, statement.size());
		if (size < 1) {
			return;
		}
		int name = statement.functionName(size);
		if (name >= 0) {
			out.add(function(statement, name, size, end, documentation));
		} else {
			variables(statement, size, end, documentation, out);
		}
	}

	static String limit(String text) {
		return text.length() <= MAX_DETAIL ? text
				: text.substring(0, MAX_DETAIL - 1) + "…";
	}

	private static String detail(GlslStatement s, int from, int to) {
		return limit(s.text(from, to));
	}

	// A function whose head is the first size tokens, with its name at
	// token name; it ends at end until its body does.
	private static Draft function(GlslStatement s, int name, int size,
			int end, String documentation) {
		Draft draft = new Draft();
		draft.name = s.text(name);
		draft.kind = Kind.FUNCTION;
		draft.start = s.start(name);
		draft.end = s.end(name);
		draft.from = s.start(0);
		draft.to = end;
		draft.documentation = documentation;
		int close = s.afterClosing(name + 1, size);
		draft.detail = detail(s, 0, close);
		// Parameters: what stands between the commas.
		int stop = s.is(close - 1, ')') ? close - 1 : close;
		List<Symbol> parameters = new ArrayList<>();
		int at = name + 2;
		int nesting = 0;
		for (int i = at; i <= stop; ++i) {
			char c = i < stop ? s.punct(i) : 0;
			if (c == '(' || c == '[') {
				++nesting;
			} else if (c == ')' || c == ']') {
				--nesting;
			} else if (i == stop || (c == ',' && nesting <= 0)) {
				parameter(s, parameters, at, i);
				at = i + 1;
			}
		}
		draft.parts = Collections.unmodifiableList(parameters);
		return draft;
	}

	// Adds the parameter the tokens from from to to declare, if it has
	// a name: "void" and a type alone have none.
	private static void parameter(GlslStatement s, List<Symbol> parameters,
			int from, int to) {
		int name = s.name(from, to);
		if (name >= 0 && parameters.size() < MAX_PARTS) {
			parameters.add(new Symbol(s.text(name), Kind.PARAMETER,
					detail(s, from, to), null, null, s.start(name),
					s.end(name), s.start(from), s.end(to - 1),
					Collections.emptyList()));
		}
	}

	// The variables, the struct or the block the first size tokens
	// declare, if any.
	private static void variables(GlslStatement s, int size, int end,
			String documentation, List<Draft> out) {
		int i = s.afterQualifiers(size);
		if (i >= size || s.kind(i) == Token.PRECISION) {
			return;
		}
		int from = s.start(0);
		// Where the type ends, and where the names follow it.
		int typeTo;
		int names;
		if (s.kind(i) == Token.STRUCT) {
			int name = i + 1 < size && s.kind(i + 1) == Token.NAME
					? i + 1 : -1;
			typeTo = name < 0 ? i + 1 : name + 1;
			names = typeTo;
			if (typeTo < size && s.is(typeTo, '{')) {
				names = s.afterClosing(typeTo, size);
				if (name >= 0) {
					out.add(struct(s, name, typeTo, names, size, end,
							documentation));
				}
			} else if (name < 0) {
				return;
			}
		} else if (s.kind(i) == Token.NAME && i + 1 < size &&
				s.is(i + 1, '{')) {
			block(s, kind(s, i), i, size, end, documentation, out);
			return;
		} else {
			names = s.name(0, size);
			if (names < 0) {
				return;
			}
			typeTo = names;
		}
		// Declarators: a name, brackets, an initializer, and a comma
		// before the next.
		Kind kind = kind(s, typeTo);
		String type = detail(s, 0, typeTo);
		boolean first = true;
		for (i = names; i < size && s.kind(i) == Token.NAME; ) {
			int next = s.afterDeclarator(i, size);
			out.add(variable(s, kind, i, type + " " + detail(s, i, next),
					first ? from : s.start(i),
					next >= size ? end : s.end(next - 1), documentation));
			first = false;
			i = next + 1;
		}
	}

	// What the qualifiers before token to make of a variable.
	private static Kind kind(GlslStatement s, int to) {
		boolean constant = false;
		for (int i = 0; i < to; ++i) {
			if (s.kind(i) == Token.UNIFORM) {
				return Kind.UNIFORM;
			}
			constant |= s.kind(i) == Token.CONST;
		}
		return constant ? Kind.CONSTANT : Kind.VARIABLE;
	}

	// The block named at token name, whose brace is the next token: its
	// fields are variables, unless one name stands for all of them.
	private static void block(GlslStatement s, Kind kind, int name, int size,
			int end, String documentation, List<Draft> out) {
		int close = s.afterClosing(name + 1, size);
		List<Symbol> fields = fields(s, name + 2, s.is(close - 1, '}')
				? close - 1 : close);
		if (close < size && s.kind(close) == Token.NAME) {
			Draft draft = variable(s, kind, close, detail(s, 0, name + 1) +
					" " + s.text(close), s.start(0), end, documentation);
			draft.parts = fields;
			out.add(draft);
			return;
		}
		for (Symbol field : fields) {
			Draft draft = new Draft();
			draft.name = field.name();
			draft.kind = kind;
			draft.detail = field.detail();
			draft.start = field.start();
			draft.end = field.end();
			draft.from = field.from();
			draft.to = field.to();
			out.add(draft);
		}
	}

	private static Draft variable(GlslStatement s, Kind kind, int name,
			String detail, int from, int to, String documentation) {
		Draft draft = new Draft();
		draft.name = s.text(name);
		draft.kind = kind;
		draft.detail = limit(detail);
		draft.start = s.start(name);
		draft.end = s.end(name);
		draft.from = from;
		draft.to = to;
		draft.documentation = documentation;
		return draft;
	}

	// The struct named at token name, whose brace opens at token open
	// and whose fields end before token close.
	private static Draft struct(GlslStatement s, int name, int open,
			int close, int size, int end, String documentation) {
		boolean closed = s.is(close - 1, '}');
		Draft draft = new Draft();
		draft.name = s.text(name);
		draft.kind = Kind.TYPE;
		draft.detail = detail(s, 0, open);
		draft.start = s.start(name);
		draft.end = s.end(name);
		draft.from = s.start(0);
		// With nothing but the ";" behind it, the struct is the whole
		// declaration.
		draft.to = close >= size ? end : s.end(close - 1);
		draft.documentation = documentation;
		draft.parts = fields(s, open + 1, closed ? close - 1 : close);
		return draft;
	}

	// The fields the tokens from from to to declare: "vec3 a, b[2];".
	private static List<Symbol> fields(GlslStatement s, int from, int to) {
		List<Symbol> fields = new ArrayList<>();
		for (int i = from; i < to; ) {
			// One statement: a type, and declarators up to a ";".
			int stop = i;
			for (int nesting = 0; stop < to; ++stop) {
				char c = s.punct(stop);
				if (c == '(' || c == '[' || c == '{') {
					++nesting;
				} else if (c == ')' || c == ']' || c == '}') {
					--nesting;
				} else if (c == ';' && nesting <= 0) {
					break;
				}
			}
			int name = s.name(i, stop);
			String type = name < 0 ? null : detail(s, i, name);
			for (int at = name; at >= 0 && at < stop &&
					fields.size() < MAX_PARTS; ) {
				int next = s.afterDeclarator(at, stop);
				if (s.kind(at) == Token.NAME) {
					fields.add(new Symbol(s.text(at), Kind.FIELD,
							limit(type + " " + detail(s, at, next)), null,
							null, s.start(at), s.end(at),
							s.start(at == name ? i : at), s.end(next - 1),
							Collections.emptyList()));
				}
				at = next + 1;
			}
			i = stop + 1;
		}
		return Collections.unmodifiableList(fields);
	}
}
