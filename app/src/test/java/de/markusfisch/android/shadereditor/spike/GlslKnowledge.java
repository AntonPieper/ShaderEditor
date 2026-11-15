package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.spike.Parser.Node;
import de.markusfisch.android.shadereditor.spike.Types.Type;

/**
 * SPIKE, throwaway: GLSL behind the proposed seam, from option B's
 * tree. It also shows what stays inside the language: the members that
 * may follow a "." on a line typed after this knowledge was made.
 */
final class GlslKnowledge implements Knowledge {
	final TokenStream t;
	final Node unit;
	final Sema sema;
	final int problems;
	private final List<Symbol> outside;
	private List<Symbol> symbols;
	private int[] folds;

	private GlslKnowledge(TokenStream t, Node unit, Sema sema, int problems,
			List<Symbol> outside) {
		this.t = t;
		this.unit = unit;
		this.sema = sema;
		this.problems = problems;
		this.outside = outside;
	}

	/** Read text; outside holds the names other files and the host add. */
	static GlslKnowledge of(Text text, Scope outside) {
		TokenStream t = TokenStream.of(text);
		Parser parser = new Parser(t);
		Node unit = parser.unit();
		// What other files declare is read as their declarations, in
		// front of this text: the detail of a symbol is its declaration.
		Node prelude = null;
		List<Symbol> names = outside.symbols();
		if (!names.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (Symbol s : names) {
				if (s.kind() == Kind.FUNCTION || s.kind() == Kind.VARIABLE ||
						s.kind() == Kind.CONSTANT || s.kind() == Kind.TYPE) {
					sb.append(s.detail()).append(";\n");
				}
			}
			prelude = new Parser(TokenStream.ofText(t, sb.toString()))
					.unit();
		}
		Sema sema = Sema.analyse(t, unit, prelude);
		return new GlslKnowledge(t, unit, sema, parser.problemCount, names);
	}

	// ---- The seam -------------------------------------------------------

	@Override
	public List<Symbol> symbols() {
		if (symbols == null) {
			List<Symbol> list = new ArrayList<>();
			for (Sema.Symbol s : sema.globals) {
				Symbol symbol = symbol(s);
				if (symbol != null) {
					list.add(symbol);
				}
			}
			symbols = list;
		}
		return symbols;
	}

	@Override
	public Symbol at(int offset) {
		Sema.Symbol s = sema.symbolAt(offset);
		if (s != null) {
			Symbol symbol = symbol(s);
			if (symbol != null) {
				return symbol;
			}
		}
		// A file named in a directive.
		for (int d = 0; d < t.directives; ++d) {
			if (t.directiveStarts[d] <= offset &&
					offset <= t.directiveEnds[d]) {
				int quote = t.source.indexOf('"', t.directiveStarts[d]);
				int close = quote < 0 ? -1 : t.source.indexOf('"', quote + 1);
				if (quote >= 0 && close > quote && quote <= offset &&
						offset <= close && close <= t.directiveEnds[d]) {
					String path = t.source.substring(quote + 1, close);
					return new Symbol(path, Kind.FILE, path, null, path, -1,
							-1, 0, 0);
				}
				return null;
			}
		}
		// A word no declaration of this text explains: a built-in, or
		// a name from outside.
		int i = t.tokenAt(offset + 1) - 1;
		if (i < 0 || i >= t.size || t.starts[i] > offset ||
				offset > t.ends[i] || t.ids[i] < 0) {
			return null;
		}
		return named(t.ids[i]);
	}

	private Symbol named(int id) {
		Builtins.Function f = Builtins.function(id);
		if (f != null) {
			StringBuilder sb = new StringBuilder();
			for (Builtins.Signature s : f.signatures) {
				sb.append(sb.length() > 0 ? "\n" : "").append(s.text);
			}
			return new Symbol(f.name, Kind.FUNCTION, sb.toString(), f.doc,
					null, -1, -1, 0, 0);
		}
		String name = t.names.name(id);
		for (Symbol s : outside) {
			if (s.name().equals(name)) {
				return s;
			}
		}
		Type variable = Builtins.variable(id);
		return variable == null ? null : new Symbol(name, Kind.VARIABLE,
				variable + " " + name, null, null, -1, -1, 0, 0);
	}

	@Override
	public Call callAt(int offset) {
		int[] found = new int[2];
		if (!Shallow.callAt(t, offset, found)) {
			return null;
		}
		int id = t.ids[found[0]];
		List<Symbol> signatures = new ArrayList<>();
		List<int[]> parameters = new ArrayList<>();
		// The types of the arguments written so far, if the tree has
		// this call.
		Node call = callNode(unit, t.starts[found[0]]);
		int count = call == null ? 0 : Math.min(found[1] + 1,
				call.list.length);
		Type[] args = new Type[count];
		for (int i = 0; i < count; ++i) {
			args[i] = call.list[i].type;
		}
		int active = -1;
		Sema.Symbol own = sema.lookup(id);
		if (own != null && own.kind == Sema.FUNCTION) {
			for (Sema.Symbol f = own; f != null; f = f.next) {
				Symbol symbol = symbol(f);
				if (active < 0 && f.parameters.length > found[1]) {
					active = signatures.size();
				}
				signatures.add(symbol);
				parameters.add(parameterRanges(symbol.detail()));
			}
		} else {
			Builtins.Function f = Builtins.function(id);
			if (f == null) {
				return null;
			}
			for (Builtins.Signature s : f.signatures) {
				if (active < 0 && s.params.length > found[1] &&
						s.apply(args, count, true) != null) {
					active = signatures.size();
				}
				signatures.add(new Symbol(f.name, Kind.FUNCTION, s.text,
						f.doc, null, -1, -1, 0, 0));
				parameters.add(parameterRanges(s.text));
			}
		}
		return new Call(signatures, parameters, Math.max(0, active),
				found[1]);
	}

	private static Node callNode(Node node, int at) {
		if (node == null || node.start > at || node.end < at) {
			return null;
		}
		for (Node child : new Node[]{node.a, node.b, node.c}) {
			Node found = callNode(child, at);
			if (found != null) {
				return found;
			}
		}
		for (Node child : node.list) {
			Node found = callNode(child, at);
			if (found != null) {
				return found;
			}
		}
		return node.kind == Parser.CALL && node.at == at ? node : null;
	}

	// The offsets of the parameters in a signature, as pairs.
	private static int[] parameterRanges(String signature) {
		int open = signature.indexOf('(');
		int close = signature.lastIndexOf(')');
		if (open < 0 || close <= open + 1) {
			return new int[0];
		}
		List<Integer> out = new ArrayList<>();
		int from = open + 1;
		for (int i = open + 1; i <= close; ++i) {
			char c = signature.charAt(i);
			if (c == ',' || i == close) {
				int a = from;
				while (a < i && signature.charAt(a) == ' ') {
					++a;
				}
				out.add(a);
				out.add(i);
				from = i + 1;
			}
		}
		int[] ranges = new int[out.size()];
		for (int i = 0; i < ranges.length; ++i) {
			ranges[i] = out.get(i);
		}
		return ranges;
	}

	@Override
	public int[] occurrences(Symbol symbol) {
		if (symbol.file() == null && symbol.start() >= 0) {
			Sema.Symbol s = sema.symbolAt(symbol.start());
			return s == null ? new int[0] : sema.references(s);
		}
		// A name from elsewhere: where this text means the global.
		int id = t.names.find(symbol.name());
		if (id < 0) {
			return new int[0];
		}
		int[] out = new int[16];
		int[] size = {0};
		out = global(unit, id, symbol.name().length(), out, size);
		return Arrays.copyOf(out, size[0]);
	}

	private int[] global(Node node, int id, int length, int[] out,
			int[] size) {
		if (node == null) {
			return out;
		}
		if (node.name == id && (node.kind == Parser.NAME ||
				node.kind == Parser.CALL) && (node.symbol == null ||
				node.symbol.outside ||
				node.symbol.kind == Sema.FUNCTION ||
				node.symbol.kind == Sema.STRUCT ||
				node.symbol.kind == Sema.MACRO ||
				sema.lookup(id) == node.symbol)) {
			if (size[0] + 2 > out.length) {
				out = Arrays.copyOf(out, out.length * 2);
			}
			out[size[0]++] = node.at;
			out[size[0]++] = node.at + length;
		}
		out = global(node.a, id, length, out, size);
		out = global(node.b, id, length, out, size);
		out = global(node.c, id, length, out, size);
		for (Node child : node.list) {
			out = global(child, id, length, out, size);
		}
		return out;
	}

	@Override
	public int[] folds() {
		if (folds == null) {
			int[] out = new int[64];
			int[] size = {0};
			out = blocks(unit, out, size);
			// Comments of two lines or more, and runs of line comments.
			int from = -1;
			int last = -1;
			for (int c = 0; c <= t.comments; ++c) {
				int a = c < t.comments ? t.lineOf(t.commentStarts[c]) : -9;
				int b = c < t.comments
						? t.lineOf(t.commentEnds[c] - 1) : -9;
				boolean own = c < t.comments && !t.commentAfterCode[c];
				if (own && from >= 0 && a == last + 1) {
					last = b;
					continue;
				}
				if (from >= 0 && last > from) {
					out = add(out, size, from, last);
				}
				from = own ? a : -1;
				last = b;
			}
			// Conditionals.
			int[] open = new int[16];
			int depth = 0;
			for (int d = 0; d < t.directives; ++d) {
				if (t.directiveKinds[d] == TokenStream.D_IF) {
					if (depth == open.length) {
						open = Arrays.copyOf(open, depth * 2);
					}
					open[depth++] = t.directiveLines[d];
				} else if (t.directiveKinds[d] == TokenStream.D_ENDIF &&
						depth > 0) {
					out = add(out, size, open[--depth], t.directiveLines[d]);
				}
			}
			folds = Arrays.copyOf(out, size[0]);
		}
		return folds;
	}

	private int[] blocks(Node node, int[] out, int[] size) {
		if (node == null) {
			return out;
		}
		if (node.kind == Parser.BLOCK || node.kind == Parser.STRUCT) {
			int a = t.lineOf(node.start);
			int b = t.lineOf(Math.max(node.start, node.end - 1));
			if (b > a) {
				out = add(out, size, a, b);
			}
		}
		if (node.kind >= Parser.NAME) {
			return out;
		}
		out = blocks(node.a, out, size);
		out = blocks(node.b, out, size);
		out = blocks(node.c, out, size);
		for (Node child : node.list) {
			out = blocks(child, out, size);
		}
		return out;
	}

	private static int[] add(int[] out, int[] size, int a, int b) {
		if (size[0] + 2 > out.length) {
			out = Arrays.copyOf(out, out.length * 2);
		}
		out[size[0]++] = a;
		out[size[0]++] = b;
		return out;
	}

	// ---- Inside the language --------------------------------------------

	/**
	 * Return what may follow the "." before column of line, a line of
	 * a text newer than this knowledge, where offset is the place in
	 * the text this knowledge was made of that the line's caret maps
	 * to: the members of the type of what stands before the ".". The
	 * expression is read from the line, the names from this knowledge.
	 */
	List<String> membersAt(String line, int column, int offset) {
		Type type = typeBefore(line, column, offset);
		return type == null ? new ArrayList<>()
				: Types.members(type, t.names);
	}

	Type typeBefore(String line, int column, int offset) {
		int dot = column - 1;
		while (dot >= 0 && line.charAt(dot) != '.') {
			// The start of a member being typed: "p.xy".
			if (!Character.isLetterOrDigit(line.charAt(dot)) &&
					line.charAt(dot) != '_') {
				return null;
			}
			--dot;
		}
		if (dot < 1) {
			return null;
		}
		// Walk back over what stands before the dot: names, members,
		// and whatever brackets close there.
		int start = dot;
		int nesting = 0;
		while (start > 0) {
			char c = line.charAt(start - 1);
			if (c == ')' || c == ']') {
				++nesting;
			} else if (c == '(' || c == '[') {
				if (nesting == 0) {
					break;
				}
				--nesting;
			} else if (nesting == 0 && !Character.isLetterOrDigit(c) &&
					c != '_' && c != '.') {
				break;
			}
			--start;
		}
		if (start >= dot) {
			return null;
		}
		TokenStream receiver = TokenStream.ofText(t,
				line.substring(start, dot));
		Node expression = new Parser(receiver).wholeExpression();
		if (expression == null) {
			return null;
		}
		Type type = sema.typeAt(expression, offset);
		return type.kind == Types.UNKNOWN ? null : type;
	}

	// ---- Symbols ---------------------------------------------------------

	private Symbol symbol(Sema.Symbol s) {
		String name = t.names.name(s.name);
		Node node = s.declaration;
		if (s.outside) {
			return prelude(name);
		}
		switch (s.kind) {
			case Sema.FUNCTION: {
				if (node == null || node.start < 0 || node.op > node.end ||
						node.symbol != s) {
					return prelude(name);
				}
				return new Symbol(name, Kind.FUNCTION, clean(t.source
						.substring(node.start, Math.max(node.start, node.op))),
						doc(node.start, node.end), null, node.at,
						node.at + name.length(), node.start, node.end);
			}
			case Sema.VARIABLE:
			case Sema.PARAMETER:
			case Sema.FIELD: {
				if (node == null || node.symbol != s) {
					return prelude(name);
				}
				return new Symbol(name, s.kind == Sema.PARAMETER
						? Kind.PARAMETER : s.kind == Sema.FIELD ? Kind.FIELD
						: Kind.VARIABLE, s.type + " " + name,
						doc(node.at, node.end), null, node.at,
						node.at + name.length(), node.start, node.end);
			}
			case Sema.STRUCT: {
				if (node == null || node.symbol != s) {
					return prelude(name);
				}
				return new Symbol(name, Kind.TYPE, clean(t.source.substring(
						node.start, node.end)), doc(node.start, node.end),
						null, node.at, node.at + name.length(), node.start,
						node.end);
			}
			case Sema.MACRO: {
				int d = s.directive;
				return new Symbol(name, Kind.MACRO, clean(t.source.substring(
						t.directiveStarts[d], t.directiveEnds[d])),
						doc(t.directiveStarts[d], t.directiveEnds[d]), null,
						t.directiveBodies[d] - name.length(),
						t.directiveBodies[d], t.directiveStarts[d],
						t.directiveEnds[d]);
			}
			default:
				return named(s.name);
		}
	}

	// A symbol that came in from outside: hand back the one given.
	private Symbol prelude(String name) {
		for (Symbol s : outside) {
			if (s.name().equals(name)) {
				return s;
			}
		}
		return null;
	}

	private static String clean(String s) {
		return s.replaceAll("\\s+", " ").trim();
	}

	/**
	 * The comment of the declaration from start to end: the comment
	 * lines directly above it, on lines of their own, without an empty
	 * line between; else a comment behind it on the line it ends on.
	 */
	String doc(int start, int end) {
		int low = 0;
		int high = t.comments - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (t.commentEnds[mid] <= start) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		int line = t.lineOf(start);
		int from = -1;
		int to = -1;
		for (int c = high; c >= 0 && !t.commentAfterCode[c] &&
				t.lineOf(t.commentEnds[c] - 1) == line - 1; --c) {
			from = t.commentStarts[c];
			if (to < 0) {
				to = t.commentEnds[c];
			}
			line = t.lineOf(from);
		}
		if (from < 0) {
			int last = t.lineOf(Math.max(start, end - 1));
			for (int c = low; c < t.comments &&
					t.lineOf(t.commentStarts[c]) <= last; ++c) {
				if (t.commentAfterCode[c] && t.commentStarts[c] >= end &&
						t.lineOf(t.commentStarts[c]) == last) {
					from = t.commentStarts[c];
					to = t.commentEnds[c];
					break;
				}
			}
		}
		return Shallow.docText(t.source, from, to);
	}
}
