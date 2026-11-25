package de.markusfisch.android.shadereditor.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslWords;
import de.markusfisch.android.shadereditor.syntax.Style;

/**
 * What the names in pieces of the tree mean: for a token, the
 * declaration it names. It follows from the steps the parser wrote
 * ({@link Names}) and the rules of the language: a name means the
 * declaration of it that is nearest around the place and stands
 * before it.
 */
final class Meanings {
	/** Something declared: a variable, a function or a struct. */
	static final class Declared {
		static final byte VARIABLE = 1;
		static final byte FUNCTION = 2;
		static final byte STRUCT = 3;

		final byte kind;
		/** Where its name is written: a compiled token of a piece; null for a struct without a name. */
		final Piece piece;
		final int token;
		/**
		 * A variable's struct or what a function returns: null for a
		 * type of the language, {@link #UNKNOWN} if the text does not
		 * say.
		 */
		Declared type;
		/** A struct's fields, by symbol. */
		HashMap<Integer, Declared> fields;
		/** A function's further heads: prototypes, definitions, other forms. */
		List<Declared> heads;
		/**
		 * For a function with the name of one the language has: what
		 * a call of the name means, {@link #orLanguage}. GLSL ES 1.00
		 * lets a shader add forms to a function of the language, and
		 * which form a call means its arguments say.
		 */
		Declared call;
		/** Whether a call means this function or the language's own. */
		boolean orLanguage;

		Declared(byte kind, Piece piece, int token, Declared type) {
			this.kind = kind;
			this.piece = piece;
			this.token = token;
			this.type = type;
		}
	}

	// In place of a declaration.
	/** A name the language itself has. */
	static final Declared LANGUAGE = new Declared((byte) 0, null, -1, null);
	/** A name nothing before the place declares. */
	static final Declared UNDECLARED = new Declared((byte) 0, null, -1, null);
	/** A member of a value whose type the text does not say. */
	static final Declared UNKNOWN = new Declared((byte) 0, null, -1, null);
	/** A name inside an ERROR node. */
	static final Declared UNREAD = new Declared((byte) 0, null, -1, null);
	/**
	 * A name that one scope declares twice. No text a compiler takes
	 * does; in one with a brace missing, a local may have come to
	 * stand beside a global, and which of them is meant cannot be told.
	 */
	static final Declared REDECLARED = new Declared((byte) 0, null, -1, null);

	private final ArrayList<HashMap<Integer, Declared>> scopes =
			new ArrayList<>();
	private final ArrayList<Declared> structs = new ArrayList<>();

	private Meanings() {
		scopes.add(new HashMap<>());
	}

	/**
	 * What every compiled token of the last of pieces means: its
	 * declaration, one of the four above, or null for a token that is
	 * no name or a word of the language. The pieces before it are
	 * read only for what they declare outside functions.
	 */
	static Declared[] of(List<Piece> pieces) {
		Meanings meanings = new Meanings();
		Declared[] last = null;
		for (int i = 0; i < pieces.size(); ++i) {
			Piece piece = pieces.get(i);
			last = i < pieces.size() - 1 ? null
					: new Declared[piece.tokens.size];
			meanings.read(piece, last);
		}
		return last;
	}

	/** As {@link #of}, for every one of pieces. */
	static List<Declared[]> ofAll(List<Piece> pieces) {
		Meanings meanings = new Meanings();
		List<Declared[]> all = new ArrayList<>();
		for (Piece piece : pieces) {
			Declared[] means = new Declared[piece.tokens.size];
			meanings.read(piece, means);
			all.add(means);
		}
		return all;
	}

	// Takes the steps of a piece. Without means, only what it declares
	// outside functions.
	private void read(Piece piece, Declared[] means) {
		Names names = piece.names;
		int[] symbol = piece.tokens.symbol;
		// The type of the value of each step.
		Declared[] types = new Declared[names.size];
		for (int i = 0; i < names.size; ++i) {
			int a = names.a[i];
			int b = names.b[i];
			Declared is = null;
			switch (names.step[i]) {
				case Names.OPEN:
					if (means == null) {
						i = a;
					} else {
						scopes.add(new HashMap<>());
					}
					continue;
				case Names.CLOSE:
					scopes.remove(scopes.size() - 1);
					continue;
				case Names.VARIABLE:
					is = declare(symbol[a], new Declared(Declared.VARIABLE,
							piece, a, b < 0 ? null : types[b]));
					break;
				case Names.FUNCTION: {
					Declared type = b < 0 ? null : types[b];
					is = new Declared(Declared.FUNCTION, piece, a, type);
					Declared first = scopes.get(0).get(symbol[a]);
					if (first == null) {
						is.heads = new ArrayList<>();
						if (isBuiltin(piece.spelling(a))) {
							is.call = new Declared(Declared.FUNCTION, piece, a,
									UNKNOWN);
							is.call.orLanguage = true;
						}
						scopes.get(0).put(symbol[a], is);
					} else if (first.kind != Declared.FUNCTION) {
						scopes.get(0).put(symbol[a], REDECLARED);
					} else {
						// Every head of a name is one function.
						first.heads.add(is);
						if (first.type != type) {
							first.type = UNKNOWN;
						}
						is = first;
					}
					break;
				}
				case Names.STRUCT:
				case Names.BLOCK:
					is = new Declared(Declared.STRUCT, a < 0 ? null : piece, a,
							null);
					is.fields = new HashMap<>();
					if (a >= 0 && names.step[i] == Names.STRUCT) {
						declare(symbol[a], is);
					}
					structs.add(is);
					types[i] = is;
					break;
				case Names.FIELD:
					is = new Declared(Declared.VARIABLE, piece, a,
							b < 0 ? null : types[b]);
					structs.get(structs.size() - 1).fields.put(symbol[a], is);
					break;
				case Names.END: {
					Declared block = structs.remove(structs.size() - 1);
					if (b == 1) {
						// A block without an instance: its fields are variables.
						for (Declared field : block.fields.values()) {
							declare(symbol[field.token], field);
						}
					}
					continue;
				}
				case Names.TYPE:
					// Only a struct is a type.
					is = find(symbol[a]);
					is = is == REDECLARED || (is != null &&
							is.kind == Declared.STRUCT) ? is : null;
					types[i] = is == null || is == REDECLARED ? UNKNOWN : is;
					break;
				case Names.VALUE:
					is = find(symbol[a]);
					types[i] = is == null ? UNKNOWN
							: is.kind == Declared.VARIABLE ? is.type : UNKNOWN;
					if (is == null && piece.spelling(a).startsWith("gl_")) {
						is = LANGUAGE;
						types[i] = null;
					}
					break;
				case Names.CALL:
					// Only a function or a struct is called: a variable
					// of the name does not hide a function of the language.
					is = find(symbol[a]);
					is = is == REDECLARED || (is != null &&
							is.kind != Declared.VARIABLE) ? is : null;
					is = is != null && is.call != null ? is.call : is;
					types[i] = is == null || is == REDECLARED ? UNKNOWN
							: is.kind == Declared.FUNCTION ? is.type : is;
					if (is == null && isBuiltin(piece.spelling(a))) {
						is = LANGUAGE;
						types[i] = null;
					}
					break;
				case Names.MEMBER: {
					Declared of = types[b];
					if (of == null) {
						is = LANGUAGE;
					} else if (of == UNKNOWN || of.fields == null) {
						is = UNKNOWN;
						types[i] = UNKNOWN;
					} else {
						is = of.fields.get(symbol[a]);
						types[i] = is == null ? UNKNOWN : is.type;
					}
					break;
				}
				case Names.EITHER:
					types[i] = types[a] == types[b] ? types[a] : UNKNOWN;
					continue;
				default:
					is = UNREAD;
					break;
			}
			if (means != null && a >= 0) {
				means[a] = is == null ? UNDECLARED : is;
			}
		}
	}

	private Declared declare(int symbol, Declared declared) {
		HashMap<Integer, Declared> scope = scopes.get(scopes.size() - 1);
		scope.put(symbol, scope.containsKey(symbol) ? REDECLARED : declared);
		return declared;
	}

	private Declared find(int symbol) {
		for (int i = scopes.size() - 1; i >= 0; --i) {
			Declared declared = scopes.get(i).get(symbol);
			if (declared != null) {
				return declared;
			}
		}
		return null;
	}

	private static boolean isBuiltin(String name) {
		return name.startsWith("gl_") || GlslWords.style(name, 0,
				name.length()) == Style.BUILTIN_FUNCTION;
	}
}
