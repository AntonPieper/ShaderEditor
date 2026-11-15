package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.spike.Types.Type;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Style;

/**
 * SPIKE, throwaway: option A, the stretched tokenizer and outline. No
 * expression is understood. Two parts:
 *
 * the shape of a line (what it adds to the nesting of braces, whether
 * it is a comment or a conditional directive), which follows from the
 * line's tokens alone and so can be kept per line like the lexer state;
 *
 * and a scan of the whole text for declarations with their ranges,
 * parameters, members, the comment above, macros, and the local
 * variables of function bodies with the text of their types.
 */
final class Shallow {
	private Shallow() {
	}

	// ---- The shape of a line -------------------------------------------

	static final int CLOSE_MASK = 0x3f;
	static final int OPEN_SHIFT = 6;
	static final int OPEN_MASK = 0x3f << OPEN_SHIFT;
	static final int COMMENT = 1 << 12;
	static final int IF = 1 << 13;
	static final int ELSE = 1 << 14;
	static final int ENDIF = 1 << 15;

	/**
	 * Return the shape of a line from its tokens: how many braces it
	 * closes that it did not open, how many it opens and leaves open
	 * (each at most 63), whether it holds nothing but a comment, and
	 * whether it is #if, #else or #endif. inComment tells that the
	 * line starts inside a block comment.
	 */
	static short shape(CharSequence line, LineTokens tokens,
			boolean inComment) {
		int open = 0;
		int close = 0;
		int flags = 0;
		int size = tokens.size();
		boolean onlyComment = size > 0 || inComment;
		for (int i = 0; i < size; ++i) {
			Style style = tokens.style(i);
			if (style == Style.COMMENT) {
				continue;
			}
			onlyComment = false;
			if (tokens.inDirective(i)) {
				if (i + 1 < size && style == Style.DIRECTIVE &&
						line.charAt(tokens.start(i)) == '#') {
					int s = tokens.start(i + 1);
					int length = tokens.end(i + 1) - s;
					char c = line.charAt(s);
					if (c == 'i' && length >= 2 && line.charAt(s + 1) == 'f') {
						flags |= IF;
					} else if (c == 'e' && length == 4 &&
							line.charAt(s + 1) == 'l') {
						flags |= ELSE;
					} else if (c == 'e' && length == 5 &&
							line.charAt(s + 1) == 'n') {
						flags |= ENDIF;
					}
				}
				continue;
			}
			if (style == Style.OPERATOR) {
				char c = line.charAt(tokens.start(i));
				if (c == '{') {
					++open;
				} else if (c == '}') {
					if (open > 0) {
						--open;
					} else {
						++close;
					}
				}
			}
		}
		return (short) (Math.min(close, 63) |
				(Math.min(open, 63) << OPEN_SHIFT) |
				(onlyComment ? COMMENT : 0) | flags);
	}

	/**
	 * Return the regions that fold, as pairs of first and last line:
	 * blocks in braces that span lines, runs of two or more comment
	 * lines, and conditionals from #if to #endif. A branch after #else
	 * starts at the nesting its #if started at, so a brace opened in
	 * both branches counts once.
	 */
	static int[] folds(short[] shapes, int lines) {
		int[] out = new int[64];
		int size = 0;
		int[] stack = new int[32];
		int depth = 0;
		int[] ifLines = new int[16];
		int[] ifDepths = new int[16];
		int ifs = 0;
		int commentFrom = -1;
		for (int n = 0; n <= lines; ++n) {
			int shape = n < lines ? shapes[n] & 0xffff : 0;
			if ((shape & COMMENT) != 0) {
				if (commentFrom < 0) {
					commentFrom = n;
				}
				continue;
			}
			if (commentFrom >= 0) {
				if (n - 1 > commentFrom) {
					out = put(out, size, commentFrom, n - 1);
					size += 2;
				}
				commentFrom = -1;
			}
			if (n == lines) {
				break;
			}
			if ((shape & IF) != 0) {
				if (ifs == ifLines.length) {
					ifLines = Arrays.copyOf(ifLines, ifs * 2);
					ifDepths = Arrays.copyOf(ifDepths, ifs * 2);
				}
				ifLines[ifs] = n;
				ifDepths[ifs++] = depth;
			} else if ((shape & ELSE) != 0) {
				if (ifs > 0) {
					depth = Math.min(depth, ifDepths[ifs - 1]);
				}
			} else if ((shape & ENDIF) != 0) {
				if (ifs > 0) {
					--ifs;
					if (n > ifLines[ifs]) {
						out = put(out, size, ifLines[ifs], n);
						size += 2;
					}
				}
			}
			for (int c = shape & CLOSE_MASK; c > 0 && depth > 0; --c) {
				int from = stack[--depth];
				if (n > from) {
					out = put(out, size, from, n);
					size += 2;
				}
			}
			for (int o = (shape & OPEN_MASK) >> OPEN_SHIFT; o > 0; --o) {
				if (depth == stack.length) {
					stack = Arrays.copyOf(stack, depth * 2);
				}
				stack[depth++] = n;
			}
		}
		return Arrays.copyOf(out, size);
	}

	private static int[] put(int[] out, int size, int a, int b) {
		if (size + 2 > out.length) {
			out = Arrays.copyOf(out, out.length * 2);
		}
		out[size] = a;
		out[size + 1] = b;
		return out;
	}

	/**
	 * Return the line that closes the block the last open brace of
	 * line opens, or -1: a walk down the shapes, no text is read.
	 */
	static int blockEnd(short[] shapes, int lines, int line) {
		int depth = ((shapes[line] & 0xffff) & OPEN_MASK) >> OPEN_SHIFT;
		if (depth == 0) {
			return -1;
		}
		depth = 1;
		for (int n = line + 1; n < lines; ++n) {
			int shape = shapes[n] & 0xffff;
			depth -= shape & CLOSE_MASK;
			if (depth <= 0) {
				return n;
			}
			depth += (shape & OPEN_MASK) >> OPEN_SHIFT;
		}
		return -1;
	}

	// ---- The scan for declarations ------------------------------------

	static final int FUNCTION = 0;
	static final int PROTOTYPE = 1;
	static final int VARIABLE = 2;
	static final int UNIFORM = 3;
	static final int CONSTANT = 4;
	static final int MACRO = 5;
	static final int STRUCT = 6;
	static final String[] KINDS = {"function", "prototype", "variable",
			"uniform", "constant", "macro", "struct"};

	/** A top-level declaration, with offsets into the text. */
	static final class Decl {
		int kind;
		int name;
		int nameStart;
		int nameEnd;
		// The whole declaration, the comment that belongs to it or -1,
		// the text of its type, and the body in braces or -1.
		int start;
		int end;
		int docStart = -1;
		int docEnd = -1;
		int typeStart;
		int typeEnd;
		int bodyStart = -1;
		int bodyEnd = -1;
		// Parameters or struct members: name, type start, type end,
		// name start; four numbers each.
		int[] parts = NO_PARTS;
		boolean inactive;
	}

	private static final int[] NO_PARTS = new int[0];

	/** What the scan finds. */
	static final class Outline {
		final TokenStream tokens;
		final List<Decl> declarations = new ArrayList<>();
		// Locals: name, type start, type end, offset of the name,
		// offset where the scope ends; five numbers each, in the order
		// of their names' offsets.
		int[] locals = new int[256];
		int localCount;

		Outline(TokenStream tokens) {
			this.tokens = tokens;
		}

		/** The declaration whose range holds offset, or null. */
		Decl enclosing(int offset) {
			int low = 0;
			int high = declarations.size() - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				Decl d = declarations.get(mid);
				if (d.end <= offset) {
					low = mid + 1;
				} else if (d.start > offset) {
					high = mid - 1;
				} else {
					return d;
				}
			}
			return null;
		}

		/**
		 * Return the type of the name as its declaration writes it, as
		 * seen from offset: a local or parameter of the function
		 * around offset, else a global; or null.
		 */
		Type typeOf(int name, int offset) {
			Decl around = enclosing(offset);
			if (around != null && around.kind == FUNCTION) {
				// The innermost local declared above offset.
				int best = -1;
				for (int i = firstLocalAt(around.bodyStart);
						i < localCount && locals[i * 5 + 3] < offset; ++i) {
					if (locals[i * 5] == name && locals[i * 5 + 4] > offset) {
						best = i;
					}
				}
				if (best >= 0) {
					return arrayAfter(typeFrom(locals[best * 5 + 1],
							locals[best * 5 + 2]), locals[best * 5 + 3]);
				}
				for (int i = 0; i < around.parts.length; i += 4) {
					if (around.parts[i] == name) {
						return arrayAfter(typeFrom(around.parts[i + 1],
								around.parts[i + 2]), around.parts[i + 3]);
					}
				}
			}
			Type found = Builtins.variable(name);
			for (Decl d : declarations) {
				if (d.name == name && d.kind != FUNCTION &&
						d.kind != PROTOTYPE && d.kind != STRUCT &&
						d.kind != MACRO) {
					found = arrayAfter(typeFrom(d.typeStart, d.typeEnd),
							d.nameStart);
					if (!d.inactive) {
						break;
					}
				}
			}
			return found;
		}

		// "float a[3]" writes the brackets behind the name at offset.
		private Type arrayAfter(Type type, int nameOffset) {
			int i = tokens.tokenAt(nameOffset) + 1;
			return type != null && type.kind != Types.ARRAY &&
					i < tokens.size &&
					tokens.kind(i) == TokenStream.PUNCT &&
					tokens.charAt(i) == '[' ? Types.array(type, -1) : type;
		}

		private int firstLocalAt(int offset) {
			int low = 0;
			int high = localCount - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				if (locals[mid * 5 + 3] < offset) {
					low = mid + 1;
				} else {
					high = mid - 1;
				}
			}
			return low;
		}

		// Whether a struct has this name, by the name's number.
		boolean[] structNames = new boolean[64];

		boolean isStruct(int name) {
			return name >= 0 && name < structNames.length &&
					structNames[name];
		}

		/** The struct declared with this name, or null. */
		Decl struct(int name) {
			if (!isStruct(name)) {
				return null;
			}
			for (Decl d : declarations) {
				if (d.kind == STRUCT && d.name == name) {
					return d;
				}
			}
			return null;
		}

		// The type a range of tokens names: the last type word in it,
		// built-in or a struct, as an array if brackets follow.
		Type typeFrom(int start, int end) {
			TokenStream t = tokens;
			Type type = null;
			boolean array = false;
			for (int i = t.tokenAt(start); i < t.size && t.starts[i] < end;
					++i) {
				int kind = t.kind(i);
				if (kind == TokenStream.TYPE) {
					type = Types.byNameId(t.ids[i]);
				} else if (kind == TokenStream.IDENT) {
					Decl s = struct(t.ids[i]);
					if (s != null) {
						type = structType(s);
					}
				} else if (kind == TokenStream.PUNCT && t.charAt(i) == '[') {
					array = true;
				}
			}
			return type == null ? null
					: array ? Types.array(type, -1) : type;
		}

		private Type structType(Decl s) {
			Type type = Types.struct(tokens.names.name(s.name));
			type.declaration = s;
			return type;
		}

		// The type of member of a value of type, where a struct is one
		// this outline found.
		Type member(Type type, int memberToken) {
			TokenStream t = tokens;
			if (type.kind == Types.STRUCT && type.declaration instanceof Decl) {
				Decl s = (Decl) type.declaration;
				for (int i = 0; i < s.parts.length; i += 4) {
					if (s.parts[i] == t.ids[memberToken]) {
						return arrayAfter(typeFrom(s.parts[i + 1],
								s.parts[i + 2]), s.parts[i + 3]);
					}
				}
				return null;
			}
			Type m = Types.member(type, t.source, t.starts[memberToken],
					t.ends[memberToken], t.ids[memberToken]);
			return m == Types.T_UNKNOWN ? null : m;
		}

		// The type of a call of the name at token i, if it is the same
		// whatever the arguments: a constructor, an own function whose
		// definitions all return one type, a built-in whose signatures
		// all do.
		private Type callType(int i) {
			TokenStream t = tokens;
			if (t.kind(i) == TokenStream.TYPE) {
				return Types.byNameId(t.ids[i]);
			} else if (t.kind(i) != TokenStream.IDENT) {
				return null;
			}
			int name = t.ids[i];
			Decl struct = struct(name);
			if (struct != null) {
				return structType(struct);
			}
			Type type = null;
			for (Decl d : declarations) {
				if (d.name == name && (d.kind == FUNCTION ||
						d.kind == PROTOTYPE)) {
					Type r = typeFrom(d.typeStart, d.typeEnd);
					if (r == null || (type != null && !type.toString()
							.equals(r.toString()))) {
						return null;
					}
					type = r;
				}
			}
			if (type != null) {
				return type;
			}
			Builtins.Function f = Builtins.function(name);
			if (f == null) {
				return null;
			}
			for (Builtins.Signature s : f.signatures) {
				if (s.result.generic != Builtins.CONCRETE || (type != null &&
						type != s.result.concrete)) {
					return null;
				}
				type = s.result.concrete;
			}
			return type;
		}

		/**
		 * Return the type of what stands before the "." at token dot,
		 * as far as declarations alone tell: a chain of names, fields,
		 * swizzles and indices that begins with a declared name or with
		 * a call whose type does not depend on its arguments. Null for
		 * anything else: a generic built-in, a parenthesis, an operator.
		 */
		Type receiver(int dot) {
			TokenStream t = tokens;
			// Walk back over the chain to its first name.
			int i = dot - 1;
			Type call = null;
			int callEnd = -1;
			while (i >= 0) {
				if (t.kind(i) == TokenStream.PUNCT && t.charAt(i) == ']') {
					int depth = 0;
					for (; i >= 0; --i) {
						if (t.kind(i) != TokenStream.PUNCT) {
							continue;
						}
						char c = t.charAt(i);
						if (c == ']') {
							++depth;
						} else if (c == '[' && --depth == 0) {
							break;
						}
					}
					--i;
					continue;
				}
				if (t.kind(i) == TokenStream.PUNCT && t.charAt(i) == ')') {
					// A call: its type is known without its arguments
					// only if every signature of the callee gives the
					// same type.
					int close = i;
					int depth = 0;
					for (; i >= 0; --i) {
						if (t.kind(i) != TokenStream.PUNCT) {
							continue;
						}
						char c = t.charAt(i);
						if (c == ')') {
							++depth;
						} else if (c == '(' && --depth == 0) {
							break;
						}
					}
					--i;
					if (i < 0) {
						return null;
					}
					call = callType(i);
					callEnd = close;
					break;
				}
				if (t.kind(i) != TokenStream.IDENT) {
					return null;
				}
				if (i > 0 && t.kind(i - 1) == TokenStream.PUNCT &&
						t.charAt(i - 1) == '.') {
					i -= 2;
					continue;
				}
				break;
			}
			if (i < 0) {
				return null;
			}
			Type type = callEnd >= 0 ? call
					: typeOf(t.ids[i], t.starts[i]);
			if (callEnd >= 0) {
				i = callEnd;
			}
			// And forward again, applying each step.
			for (int k = i + 1; type != null && k < dot; ++k) {
				char c = t.kind(k) == TokenStream.PUNCT ? t.charAt(k) : 0;
				if (c == '.') {
					type = member(type, ++k);
				} else if (c == '[') {
					int depth = 0;
					for (; k < dot; ++k) {
						if (t.kind(k) != TokenStream.PUNCT) {
							continue;
						}
						char b = t.charAt(k);
						if (b == '[') {
							++depth;
						} else if (b == ']' && --depth == 0) {
							break;
						}
					}
					type = type.kind == Types.ARRAY ? type.element
							: type.kind == Types.VECTOR
									? Types.vector(type.base, 1)
							: type.kind == Types.MATRIX
									? Types.vector(Types.FLOAT, type.rows)
							: null;
				} else {
					return null;
				}
			}
			return type;
		}
	}

	/**
	 * Find the call the offset is an argument of, from the tokens
	 * before it alone: returns the token of the callee's name and the
	 * index of the argument in out[0], out[1], or false. It looks back
	 * no further than the statement.
	 */
	static boolean callAt(TokenStream t, int offset, int[] out) {
		int depth = 0;
		int commas = 0;
		for (int i = t.tokenAt(offset) - 1; i >= 0; --i) {
			if (t.kind(i) != TokenStream.PUNCT) {
				continue;
			}
			char c = t.charAt(i);
			if (c == ')' || c == ']') {
				++depth;
			} else if (c == '(' || c == '[') {
				if (depth > 0) {
					--depth;
				} else if (c == '(' && i > 0 &&
						(t.kind(i - 1) == TokenStream.IDENT ||
								t.kind(i - 1) == TokenStream.TYPE)) {
					out[0] = i - 1;
					out[1] = commas;
					return true;
				} else {
					// A parenthesis that groups: the call is further out.
					commas = 0;
				}
			} else if (c == ',' && depth == 0) {
				++commas;
			} else if (c == ';' || c == '{' || c == '}') {
				return false;
			}
		}
		return false;
	}

	static Outline scan(TokenStream t) {
		return new Scan(t).run();
	}

	private static final class Scan {
		private final TokenStream t;
		private final Outline out;
		private final int n;
		private int comment;
		// Locals of the open blocks of the function being read: the
		// index of the first local of each block.
		private int[] blocks = new int[16];

		Scan(TokenStream t) {
			this.t = t;
			out = new Outline(t);
			n = t.size;
		}

		Outline run() {
			int directive = 0;
			int i = 0;
			// Nesting where conditionals started, to begin an #else
			// branch like its #if: both branches of a function head are
			// read as one.
			while (i < n) {
				directive = macros(directive, i);
				i = statement(i);
			}
			macros(directive, n);
			return out;
		}

		// Adds the macros of the directives before token i.
		private int macros(int d, int i) {
			for (; d < t.directives && t.directiveTokens[d] <= i; ++d) {
				if (t.directiveKinds[d] == TokenStream.D_DEFINE &&
						t.directiveNames[d] >= 0) {
					Decl decl = new Decl();
					decl.kind = MACRO;
					decl.name = t.directiveNames[d];
					decl.start = t.directiveStarts[d];
					decl.end = t.directiveEnds[d];
					decl.nameEnd = t.directiveBodies[d];
					decl.nameStart = decl.nameEnd -
							t.names.name(decl.name).length();
					decl.typeStart = decl.typeEnd = decl.nameEnd;
					decl.inactive = !t.directiveActive[d];
					doc(decl);
					insert(decl);
				}
			}
			return d;
		}

		// Macros lie between statements, so the list stays ordered by
		// start except where a directive stands inside a statement.
		private void insert(Decl decl) {
			List<Decl> list = out.declarations;
			int at = list.size();
			while (at > 0 && list.get(at - 1).start > decl.start) {
				--at;
			}
			list.add(at, decl);
		}

		// Reads one top-level statement from token i; returns the token
		// after it.
		private int statement(int i) {
			int first = i;
			int name = -1;
			boolean struct = false;
			boolean uniform = false;
			boolean constant = false;
			for (; i < n; ++i) {
				int kind = t.kind(i);
				if (kind == TokenStream.KEYWORD) {
					int id = t.ids[i];
					struct |= id == Names.STRUCT;
					uniform |= id == Names.UNIFORM;
					constant |= id == Names.CONST;
					if (id == Names.STRUCT) {
						return structure(first, i, uniform);
					}
					continue;
				} else if (kind == TokenStream.IDENT) {
					name = i;
					continue;
				} else if (kind != TokenStream.PUNCT) {
					continue;
				}
				char c = t.charAt(i);
				if (c == '(') {
					if (name >= 0 && name > first) {
						return function(first, name, i);
					}
					i = skip(i, '(', ')');
				} else if (c == '=' || c == ';' || c == ',' || c == '[') {
					if (name >= 0 && name > first) {
						return variables(first, name, i, uniform ? UNIFORM
								: constant ? CONSTANT : VARIABLE,
								first, name);
					}
					return afterStatement(i);
				} else if (c == '{') {
					// Braces nothing declared: skip the block.
					return skip(i, '{', '}') + 1;
				} else if (c == '}') {
					return i + 1;
				}
			}
			return n;
		}

		// Skips to the ";" that ends the statement at token i, or to
		// what starts a new one after an error.
		private int afterStatement(int i) {
			for (; i < n; ++i) {
				if (t.kind(i) != TokenStream.PUNCT) {
					continue;
				}
				char c = t.charAt(i);
				if (c == ';') {
					return i + 1;
				} else if (c == '(') {
					i = skip(i, '(', ')');
				} else if (c == '[') {
					i = skip(i, '[', ']');
				} else if (c == '{') {
					i = skip(i, '{', '}');
				} else if (c == '}') {
					return i + 1;
				}
			}
			return n;
		}

		// Returns the token that closes the bracket at token i, or the
		// last token.
		private int skip(int i, char open, char close) {
			int depth = 0;
			for (; i < n; ++i) {
				if (t.kind(i) != TokenStream.PUNCT) {
					continue;
				}
				char c = t.charAt(i);
				if (c == open) {
					++depth;
				} else if (c == close && --depth == 0) {
					return i;
				}
			}
			return n - 1;
		}

		// Declarators from the one named at token name on: "a = 1, b[2];"
		private int variables(int first, int name, int i, int kind,
				int typeFrom, int typeTo) {
			Decl decl = null;
			while (true) {
				decl = new Decl();
				decl.kind = kind;
				decl.name = t.ids[name];
				decl.nameStart = t.starts[name];
				decl.nameEnd = t.ends[name];
				decl.start = t.starts[first];
				decl.typeStart = t.starts[typeFrom];
				decl.typeEnd = t.starts[typeTo];
				decl.inactive = t.inactive(name);
				out.declarations.add(decl);
				// To the "," or ";" that ends this declarator.
				int end = n;
				for (; i < n; ++i) {
					if (t.kind(i) != TokenStream.PUNCT) {
						if (t.lineFirst(i) && startsStatement(i)) {
							end = i;
							break;
						}
						continue;
					}
					char c = t.charAt(i);
					if (c == ';' || c == ',') {
						end = i;
						break;
					} else if (c == '(') {
						i = skip(i, '(', ')');
					} else if (c == '[') {
						i = skip(i, '[', ']');
					} else if (c == '{') {
						i = skip(i, '{', '}');
					} else if (c == '}') {
						end = i;
						break;
					}
				}
				boolean more = end < n &&
						t.kind(end) == TokenStream.PUNCT &&
						t.charAt(end) == ',' &&
						end + 1 < n && t.kind(end + 1) == TokenStream.IDENT;
				decl.end = end < n ? t.ends[Math.max(first, end - (more ||
						t.kind(end) != TokenStream.PUNCT ||
						t.charAt(end) != ';' ? 1 : 0))] : t.source.length();
				doc(decl);
				if (!more) {
					return end < n && t.kind(end) == TokenStream.PUNCT &&
							t.charAt(end) != '}' ? end + 1 : end;
				}
				name = end + 1;
				i = name + 1;
			}
		}

		// Whether token i, the first of its line, starts a declaration
		// or statement: a type or a keyword. Used to end a statement
		// that lost its ";".
		private boolean startsStatement(int i) {
			int kind = t.kind(i);
			return (kind == TokenStream.TYPE && i + 1 < n &&
					t.kind(i + 1) == TokenStream.IDENT) ||
					(kind == TokenStream.KEYWORD &&
							t.ids[i] != Names.TRUE && t.ids[i] != Names.FALSE);
		}

		private int structure(int first, int keyword, boolean uniform) {
			int i = keyword + 1;
			Decl decl = new Decl();
			decl.kind = STRUCT;
			decl.start = t.starts[first];
			decl.inactive = t.inactive(keyword);
			decl.name = -1;
			if (i < n && t.kind(i) == TokenStream.IDENT) {
				decl.name = t.ids[i];
				decl.nameStart = t.starts[i];
				decl.nameEnd = t.ends[i];
				++i;
			}
			decl.typeStart = t.starts[keyword];
			decl.typeEnd = i < n ? t.starts[i] : t.ends[n - 1];
			if (i >= n || t.kind(i) != TokenStream.PUNCT ||
					t.charAt(i) != '{') {
				// "struct Name x;" declares x, or the struct is not
				// written yet.
				decl.end = t.ends[Math.min(n - 1, i)];
				return i < n && decl.name >= 0 &&
						t.kind(i) == TokenStream.IDENT
						? variables(first, i, i + 1, VARIABLE, keyword, i)
						: afterStatement(Math.min(i, n - 1));
			}
			int close = skip(i, '{', '}');
			decl.bodyStart = t.starts[i];
			decl.bodyEnd = t.ends[close];
			// Members: "type a, b[2];" until the closing brace.
			int[] parts = new int[16];
			int count = 0;
			int typeFrom = i + 1;
			for (int k = i + 1; k < close; ++k) {
				if (t.kind(k) != TokenStream.PUNCT) {
					continue;
				}
				char c = t.charAt(k);
				if (c == '[') {
					int name = k - 1;
					k = skip(k, '[', ']');
					if (t.kind(name) == TokenStream.IDENT &&
							name > typeFrom) {
						parts = part(parts, count++, name, typeFrom, name);
					}
					// Skip to the separator so the name is not read again.
					while (k + 1 < close && !(t.kind(k + 1) ==
							TokenStream.PUNCT && (t.charAt(k + 1) == ';' ||
							t.charAt(k + 1) == ','))) {
						++k;
					}
					++k;
					if (k < close && t.charAt(k) == ';') {
						typeFrom = k + 1;
					}
				} else if (c == ';' || c == ',') {
					int name = k - 1;
					if (t.kind(name) == TokenStream.IDENT &&
							name > typeFrom) {
						parts = part(parts, count++, name, typeFrom, name);
					}
					if (c == ';') {
						typeFrom = k + 1;
					}
				}
			}
			decl.parts = Arrays.copyOf(parts, count * 4);
			decl.end = t.ends[close];
			doc(decl);
			if (decl.name >= 0) {
				out.declarations.add(decl);
				if (decl.name >= out.structNames.length) {
					out.structNames = Arrays.copyOf(out.structNames,
							decl.name * 2);
				}
				out.structNames[decl.name] = true;
			}
			i = close + 1;
			if (i < n && t.kind(i) == TokenStream.IDENT) {
				// "struct S {...} s;"
				return variables(first, i, i + 1, uniform ? UNIFORM
						: VARIABLE, keyword, keyword + 1);
			}
			return i < n && t.kind(i) == TokenStream.PUNCT &&
					t.charAt(i) == ';' ? i + 1 : i;
		}

		// Parts keep the type as a range of tokens that hold the name
		// too where the type is shared ("float a, b;"); typeFrom finds
		// the type word in it.
		private int[] part(int[] parts, int count, int name, int typeFrom,
				int typeTo) {
			if (count * 4 + 4 > parts.length) {
				parts = Arrays.copyOf(parts, parts.length * 2);
			}
			parts[count * 4] = t.ids[name];
			parts[count * 4 + 1] = t.starts[typeFrom];
			parts[count * 4 + 2] = t.starts[typeTo];
			parts[count * 4 + 3] = t.starts[name];
			return parts;
		}

		private int function(int first, int name, int open) {
			Decl decl = new Decl();
			decl.name = t.ids[name];
			decl.nameStart = t.starts[name];
			decl.nameEnd = t.ends[name];
			decl.start = t.starts[first];
			decl.typeStart = t.starts[first];
			decl.typeEnd = t.starts[name];
			decl.inactive = t.inactive(name);
			int close = skip(open, '(', ')');
			// Parameters: split at commas; the last name of each.
			int[] parts = new int[16];
			int count = 0;
			int from = open + 1;
			for (int k = open + 1; k <= close; ++k) {
				boolean end = k == close;
				if (!end && (t.kind(k) != TokenStream.PUNCT ||
						t.charAt(k) != ',')) {
					if (t.kind(k) == TokenStream.PUNCT && t.charAt(k) == '[') {
						k = Math.min(close - 1, skip(k, '[', ']'));
					}
					continue;
				}
				int last = -1;
				for (int p = from; p < k; ++p) {
					if (t.kind(p) == TokenStream.IDENT) {
						last = p;
					}
				}
				if (last > from) {
					parts = part(parts, count++, last, from, last);
				}
				from = k + 1;
			}
			decl.parts = Arrays.copyOf(parts, count * 4);
			int i = close + 1;
			if (i < n && t.kind(i) == TokenStream.PUNCT &&
					t.charAt(i) == '{') {
				decl.kind = FUNCTION;
				decl.bodyStart = t.starts[i];
				out.declarations.add(decl);
				doc(decl);
				i = body(i);
				decl.bodyEnd = decl.end = i > 0 && i <= n
						? t.ends[i - 1] : t.source.length();
				return i;
			}
			decl.kind = PROTOTYPE;
			decl.end = t.ends[Math.min(n - 1, i)];
			out.declarations.add(decl);
			doc(decl);
			return i < n && t.kind(i) == TokenStream.PUNCT &&
					t.charAt(i) == ';' ? i + 1 : i;
		}

		// Reads the body that opens at token i for its locals; returns
		// the token after its closing brace. A function head inside a
		// body cannot be: the body lost its closing brace, and ends
		// there.
		private int body(int i) {
			int depth = 0;
			boolean start = false;
			int parens = 0;
			for (; i < n; ++i) {
				int kind = t.kind(i);
				if (kind == TokenStream.PUNCT) {
					char c = t.charAt(i);
					if (c == '{') {
						if (depth == blocks.length) {
							blocks = Arrays.copyOf(blocks, depth * 2);
						}
						blocks[depth++] = out.localCount;
						start = true;
					} else if (c == '}') {
						--depth;
						closeBlock(blocks[depth], t.ends[i]);
						if (depth == 0) {
							return i + 1;
						}
						start = true;
					} else if (c == ';') {
						start = parens == 0 || start;
						if (parens > 0) {
							// The parts of a "for (;;)".
							start = true;
						}
					} else if (c == '(') {
						++parens;
						start = i > 0 && t.kind(i - 1) == TokenStream.KEYWORD
								&& t.ids[i - 1] == Names.FOR;
					} else if (c == ')') {
						parens = Math.max(0, parens - 1);
						start = false;
					} else {
						start = false;
					}
					continue;
				}
				if (!start) {
					continue;
				}
				start = false;
				// Qualifiers before the type.
				int typeAt = i;
				while (typeAt < n && t.kind(typeAt) == TokenStream.KEYWORD &&
						(t.ids[typeAt] == Names.CONST ||
								t.ids[typeAt] == Names.HIGHP ||
								t.ids[typeAt] == Names.MEDIUMP ||
								t.ids[typeAt] == Names.LOWP)) {
					++typeAt;
				}
				if (typeAt + 1 >= n) {
					continue;
				}
				int tk = t.kind(typeAt);
				boolean type = tk == TokenStream.TYPE ||
						(tk == TokenStream.IDENT &&
								out.isStruct(t.ids[typeAt]));
				if (!type) {
					start = typeAt > i;
					continue;
				}
				int name = typeAt + 1;
				// "float[3] a" writes the brackets before the name.
				if (t.kind(name) == TokenStream.PUNCT &&
						t.charAt(name) == '[') {
					name = skip(name, '[', ']') + 1;
				}
				if (name >= n || t.kind(name) != TokenStream.IDENT ||
						name + 1 >= n) {
					continue;
				}
				char after = t.kind(name + 1) == TokenStream.PUNCT
						? t.charAt(name + 1) : 0;
				if (after == '(' && parens == 0) {
					// The head of the next function.
					for (int d = depth; d-- > 0; ) {
						closeBlock(blocks[d], t.starts[i]);
					}
					return i;
				}
				if (after != '=' && after != ';' && after != ',' &&
						after != '[' && after != ')') {
					continue;
				}
				local(name, i, name);
				// Further declarators: ", b" at this nesting.
				int nesting = 0;
				int k = name + 1;
				for (; k < n; ++k) {
					if (t.kind(k) != TokenStream.PUNCT) {
						if (t.lineFirst(k) && nesting == 0 &&
								startsStatement(k)) {
							break;
						}
						continue;
					}
					char c = t.charAt(k);
					if (c == '(' || c == '[') {
						++nesting;
					} else if (c == ')' || c == ']') {
						if (--nesting < 0) {
							break;
						}
					} else if (c == ';' || c == '{' || c == '}') {
						break;
					} else if (c == ',' && nesting == 0 && k + 1 < n &&
							t.kind(k + 1) == TokenStream.IDENT) {
						local(k + 1, i, name);
					}
				}
				i = k - 1;
			}
			for (int d = depth; d-- > 0; ) {
				closeBlock(blocks[d], t.source.length());
			}
			return n;
		}

		private void local(int name, int typeFrom, int typeTo) {
			if (out.localCount * 5 + 5 > out.locals.length) {
				out.locals = Arrays.copyOf(out.locals,
						out.locals.length * 2);
			}
			int at = out.localCount++ * 5;
			out.locals[at] = t.ids[name];
			out.locals[at + 1] = t.starts[typeFrom];
			out.locals[at + 2] = t.starts[typeTo];
			out.locals[at + 3] = t.starts[name];
			out.locals[at + 4] = -1;
		}

		private void closeBlock(int from, int end) {
			for (int i = from; i < out.localCount; ++i) {
				if (out.locals[i * 5 + 4] < 0) {
					out.locals[i * 5 + 4] = end;
				}
			}
		}

		/**
		 * The comment of a declaration: the comment lines directly
		 * above it, with no empty line between, that stand on lines of
		 * their own; else a comment behind it on its last line.
		 */
		private void doc(Decl decl) {
			// The last comment that ends before the declaration.
			while (comment < t.comments &&
					t.commentEnds[comment] <= decl.start) {
				++comment;
			}
			int line = t.lineOf(decl.start);
			int c = comment - 1;
			int start = -1;
			int end = -1;
			while (c >= 0 && !t.commentAfterCode[c] &&
					t.lineOf(t.commentEnds[c] - 1) == line - 1 &&
					onlySpaceBetween(t.commentEnds[c],
							start < 0 ? decl.start : start)) {
				start = t.commentStarts[c];
				if (end < 0) {
					end = t.commentEnds[c];
				}
				line = t.lineOf(start);
				--c;
			}
			if (start < 0) {
				// Behind it, on the line it ends on.
				int last = t.lineOf(Math.max(decl.start, decl.end - 1));
				for (int k = comment; k < t.comments &&
						t.lineOf(t.commentStarts[k]) <= last; ++k) {
					if (t.commentAfterCode[k] &&
							t.commentStarts[k] >= decl.end &&
							t.lineOf(t.commentStarts[k]) == last) {
						start = t.commentStarts[k];
						end = t.commentEnds[k];
						break;
					}
				}
			}
			decl.docStart = start;
			decl.docEnd = end;
		}

		private boolean onlySpaceBetween(int from, int to) {
			String s = t.source;
			for (int i = from; i < to; ++i) {
				if (!Character.isWhitespace(s.charAt(i))) {
					return false;
				}
			}
			return true;
		}
	}

	/** The text of a comment without its marks, lines joined. */
	static String docText(String source, int start, int end) {
		if (start < 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (String line : source.substring(start, end).split("\n")) {
			String s = line.strip();
			if (s.startsWith("//")) {
				s = s.substring(2);
			}
			if (s.startsWith("/**")) {
				s = s.substring(3);
			} else if (s.startsWith("/*")) {
				s = s.substring(2);
			}
			if (s.endsWith("*/")) {
				s = s.substring(0, s.length() - 2);
			}
			s = s.strip();
			if (s.startsWith("*")) {
				s = s.substring(1).strip();
			}
			if (!s.isEmpty()) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(s);
			}
		}
		return sb.toString();
	}
}
