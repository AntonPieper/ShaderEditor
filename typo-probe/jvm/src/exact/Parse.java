package exact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import exact.Pre.Fail;
import exact.Pre.Tok;

/**
 * Strict parser of GLSL ES 1.00 and 3.00 over expanded tokens. It builds
 * no tree: it keeps the scopes and writes down, for every name, the
 * token that declares what it means. Anything the grammar does not
 * allow ends in Fail.
 */
final class Parse {
	/** A name of the language or a member of a vector: no declaration. */
	static final int NONE = -1;
	/** A member behind a value whose type declarations do not tell. */
	static final int UNKNOWN = -2;
	/** Not a name. */
	static final int NOT = -3;
	/** A word in a function's body that could not be read. */
	static final int UNREAD = -4;

	private static final int VAR = 0, FUNC = 1, STRUCT = 2;

	private static final class Sym {
		final int kind;
		final int decl;
		/** A variable's struct, what a function returns; null: none. */
		Sym type;
		Map<String, Sym> fields;

		Sym(int kind, int decl, Sym type) {
			this.kind = kind;
			this.decl = decl;
			this.type = type;
		}
	}

	private static final Sym UNK = new Sym(STRUCT, -1, null);

	private static Set<String> set(String words) {
		return new HashSet<>(Arrays.asList(words.split(" ")));
	}

	private static final Set<String> TYPES_100 = set("void float int bool " +
			"vec2 vec3 vec4 ivec2 ivec3 ivec4 bvec2 bvec3 bvec4 mat2 mat3 " +
			"mat4 sampler2D samplerCube samplerExternalOES");
	private static final Set<String> TYPES_300 = set("uint uvec2 uvec3 " +
			"uvec4 mat2x2 mat2x3 mat2x4 mat3x2 mat3x3 mat3x4 mat4x2 mat4x3 " +
			"mat4x4 sampler3D sampler2DShadow samplerCubeShadow " +
			"sampler2DArray sampler2DArrayShadow isampler2D isampler3D " +
			"isamplerCube isampler2DArray usampler2D usampler3D " +
			"usamplerCube usampler2DArray");
	private static final Set<String> QUALS_100 = set("const in out inout " +
			"uniform attribute varying invariant highp mediump lowp");
	private static final Set<String> QUALS_300 = set("const in out inout " +
			"uniform centroid flat smooth invariant highp mediump lowp " +
			"layout");
	private static final Set<String> WORDS = set("precision struct if else " +
			"for while do return break continue discard true false");
	private static final Set<String> WORDS_300 = set("switch case default");
	static final Set<String> FUNCTIONS = set("radians degrees sin cos tan " +
			"asin acos atan sinh cosh tanh asinh acosh atanh pow exp log " +
			"exp2 log2 sqrt inversesqrt abs sign floor trunc round " +
			"roundEven ceil fract mod modf min max clamp mix step " +
			"smoothstep isnan isinf floatBitsToInt floatBitsToUint " +
			"intBitsToFloat uintBitsToFloat packSnorm2x16 unpackSnorm2x16 " +
			"packUnorm2x16 unpackUnorm2x16 packHalf2x16 unpackHalf2x16 " +
			"length distance dot cross normalize faceforward reflect " +
			"refract matrixCompMult outerProduct transpose determinant " +
			"inverse lessThan lessThanEqual greaterThan greaterThanEqual " +
			"equal notEqual any all not textureSize texture textureProj " +
			"textureLod textureOffset texelFetch texelFetchOffset " +
			"textureProjOffset textureLodOffset textureProjLod " +
			"textureProjLodOffset textureGrad textureGradOffset " +
			"textureProjGrad textureProjGradOffset dFdx dFdy fwidth " +
			"texture2D texture2DProj texture2DLod texture2DProjLod " +
			"textureCube textureCubeLod texture2DLodEXT " +
			"texture2DProjLodEXT textureCubeLodEXT texture2DGradEXT " +
			"texture2DProjGradEXT textureCubeGradEXT");
	private static final String[][] BINARY = {{"||"}, {"^^"}, {"&&"}, {"|"},
			{"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"},
			{"+", "-"}, {"*", "/", "%"}};
	private static final Set<String> ASSIGN = set("= += -= *= /= %= <<= " +
			">>= &= |= ^=");
	private static final int MAX_DEPTH = 200;

	private final List<Tok> toks;
	private final Set<String> types = new HashSet<>(TYPES_100);
	private final Set<String> quals;
	private final Set<String> words = new HashSet<>(WORDS);
	private final ArrayList<Map<String, Sym>> scopes = new ArrayList<>();
	final int[] bind;
	/** Two declaring tokens of one thing: heads of a function. */
	final List<int[]> same = new ArrayList<>();
	/** For a declaring token: g, p, l, f, s or m; and its function. */
	final char[] declared;
	final int[] functionOf;
	/** First and last token of every function that has a body. */
	final List<int[]> functions = new ArrayList<>();
	private int function = -1;
	private boolean parameters;
	/** Whether a body that cannot be read is skipped to its brace. */
	boolean isolate;
	/** A body that was skipped: its first and last token, why, where. */
	final List<Object[]> skipped = new ArrayList<>();
	private int p;
	private int depth;

	Parse(List<Tok> toks, int version) {
		this.toks = toks;
		declared = new char[toks.size()];
		functionOf = new int[toks.size()];
		bind = new int[toks.size()];
		// Every word means something: a declaration, or the language
		// until the grammar says which declaration.
		for (int i = 0; i < bind.length; ++i) {
			bind[i] = toks.get(i).kind == Pre.ID ? NONE : NOT;
		}
		if (version >= 300) {
			types.addAll(TYPES_300);
			words.addAll(WORDS_300);
		}
		quals = version >= 300 ? QUALS_300 : QUALS_100;
		scopes.add(new HashMap<>());
	}

	void run() {
		while (p < toks.size()) {
			external();
		}
	}

	// Tokens.
	private Fail fail(String why) {
		return new Fail(why, p < toks.size() ? toks.get(p).at
				: toks.isEmpty() ? 0 : toks.get(toks.size() - 1).at);
	}

	private boolean is(String s) {
		return p < toks.size() && toks.get(p).is(s);
	}

	private boolean is(int ahead, String s) {
		return p + ahead < toks.size() && toks.get(p + ahead).is(s);
	}

	private boolean accept(String s) {
		if (is(s)) {
			++p;
			return true;
		}
		return false;
	}

	private void expect(String s) {
		if (!accept(s)) {
			throw fail("expected " + s + (p < toks.size()
					? " before " + toks.get(p).text : " at the end"));
		}
	}

	private boolean name(int at) {
		if (at >= toks.size() || toks.get(at).kind != Pre.ID) {
			return false;
		}
		String s = toks.get(at).text;
		return !types.contains(s) && !quals.contains(s) && !words.contains(s);
	}

	private int name() {
		if (!name(p)) {
			throw fail("expected a name");
		}
		return p++;
	}

	private void deeper() {
		if (++depth > MAX_DEPTH) {
			throw new Pre.Refuse("nested too deeply", toks.get(
					Math.min(p, toks.size() - 1)).at);
		}
	}

	// Scopes.
	private Sym find(String name) {
		for (int i = scopes.size(); i-- > 0; ) {
			Sym s = scopes.get(i).get(name);
			if (s != null) {
				return s;
			}
		}
		return null;
	}

	private Sym declare(int at, int kind, Sym type) {
		Sym s = new Sym(kind, at, type);
		String name = toks.get(at).text;
		if (name.startsWith("gl_")) {
			throw new Fail("a name of the language is declared",
					toks.get(at).at);
		}
		if (scopes.get(scopes.size() - 1).put(name, s) != null) {
			throw new Fail(name + " is declared twice in one scope",
					toks.get(at).at);
		}
		bind[at] = at;
		declared[at] = kind == FUNC ? 'f' : kind == STRUCT ? 's'
				: scopes.size() == 1 ? 'g' : parameters ? 'p' : 'l';
		functionOf[at] = function;
		return s;
	}

	// Declarations.
	private void external() {
		if (accept(";")) {
			return;
		}
		declaration(true);
	}

	// Whether a declaration starts here, in a statement.
	private boolean declares() {
		if (p >= toks.size()) {
			return false;
		}
		String s = toks.get(p).text;
		if (toks.get(p).kind != Pre.ID) {
			return false;
		}
		if (quals.contains(s) || s.equals("precision") ||
				s.equals("struct")) {
			return true;
		}
		Sym sym = types.contains(s) ? null : find(s);
		if (!types.contains(s) && (sym == null || sym.kind != STRUCT)) {
			return false;
		}
		// A type before a name declares; before ( it builds a value.
		int q = p + 1;
		while (q < toks.size() && toks.get(q).is("[")) {
			int open = 1;
			for (++q; q < toks.size() && open > 0; ++q) {
				open += toks.get(q).is("[") ? 1 : toks.get(q).is("]") ? -1 : 0;
			}
		}
		return name(q);
	}

	private void declaration(boolean global) {
		if (accept("precision")) {
			qualifiers();
			type();
			expect(";");
			return;
		}
		boolean qualified = qualifiers();
		if (qualified && accept(";")) {
			return;
		}
		if (qualified && name(p) && is(1, "{")) {
			block();
			return;
		}
		if (qualified && name(p) && (find(toks.get(p).text) == null ||
				find(toks.get(p).text).kind != STRUCT)) {
			// invariant a, b;
			do {
				use(name());
			} while (accept(","));
			expect(";");
			return;
		}
		Sym type = type();
		if (accept(";")) {
			return;
		}
		int name = name();
		if (is("(")) {
			if (!global) {
				throw fail("a function is declared inside a function");
			}
			function(name, type);
			return;
		}
		declarators(name, type);
		expect(";");
	}

	private void declarators(int name, Sym type) {
		while (true) {
			arrays();
			if (accept("=")) {
				assignment();
			}
			// A name is seen behind its own initializer.
			declare(name, VAR, type);
			if (!accept(",")) {
				return;
			}
			name = name();
		}
	}

	private boolean arrays() {
		boolean any = false;
		while (accept("[")) {
			any = true;
			if (!is("]")) {
				expression();
			}
			expect("]");
		}
		return any;
	}

	private boolean qualifiers() {
		boolean any = false;
		while (p < toks.size() && toks.get(p).kind == Pre.ID &&
				quals.contains(toks.get(p).text)) {
			any = true;
			if (accept("layout")) {
				expect("(");
				do {
					if (p >= toks.size() || toks.get(p).kind != Pre.ID) {
						throw fail("expected a layout word");
					}
					bind[p++] = NONE;
					if (accept("=")) {
						conditional();
					}
				} while (accept(","));
				expect(")");
			} else {
				++p;
			}
		}
		return any;
	}

	// A type: null for one of the language, else the struct.
	private Sym type() {
		Sym type = null;
		if (accept("struct")) {
			int name = name(p) ? p++ : -1;
			type = new Sym(STRUCT, name, null);
			if (name >= 0) {
				type = declare(name, STRUCT, null);
			}
			type.fields = new HashMap<>();
			members(type.fields);
		} else if (p < toks.size() && toks.get(p).kind == Pre.ID &&
				types.contains(toks.get(p).text)) {
			++p;
		} else if (name(p)) {
			type = find(toks.get(p).text);
			if (type == null || type.kind != STRUCT) {
				throw fail(toks.get(p).text + " is no type");
			}
			bind[p++] = type.decl;
		} else {
			throw fail("expected a type");
		}
		arrays();
		return type;
	}

	private void members(Map<String, Sym> fields) {
		expect("{");
		while (!accept("}")) {
			qualifiers();
			Sym type = type();
			do {
				int name = name();
				arrays();
				if (fields.put(toks.get(name).text,
						new Sym(VAR, name, type)) != null) {
					throw new Fail("a field is declared twice",
							toks.get(name).at);
				}
				bind[name] = name;
				declared[name] = 'm';
			} while (accept(","));
			expect(";");
		}
	}

	private void block() {
		int name = name();
		// The name of a block is no name of a value.
		bind[name] = name;
		Map<String, Sym> fields = new HashMap<>();
		members(fields);
		if (name(p)) {
			Sym type = new Sym(STRUCT, name, null);
			type.fields = fields;
			int instance = name();
			arrays();
			declare(instance, VAR, type);
		} else {
			// Without an instance its members are globals.
			for (Sym field : fields.values()) {
				bind[field.decl] = NOT;
				declare(field.decl, VAR, field.type);
			}
		}
		expect(";");
	}

	private void function(int name, Sym returns) {
		String text = toks.get(name).text;
		Sym f = scopes.get(0).get(text);
		if (f == null) {
			if (FUNCTIONS.contains(text)) {
				throw new Fail("a function of the language is declared",
						toks.get(name).at);
			}
			f = declare(name, FUNC, returns);
		} else if (f.kind != FUNC) {
			throw new Fail(text + " is declared twice", toks.get(name).at);
		} else {
			// Every head of a name is one thing: forms are not told apart.
			bind[name] = name;
			same.add(new int[]{name, f.decl});
			if (f.type != returns) {
				f.type = UNK;
			}
		}
		expect("(");
		scopes.add(new HashMap<>());
		int first = p;
		function = functions.size();
		parameters = true;
		if (!(is("void") && is(1, ")"))) {
			while (!is(")")) {
				qualifiers();
				Sym type = type();
				if (name(p)) {
					int parameter = name();
					arrays();
					declare(parameter, VAR, type);
				}
				if (!accept(",")) {
					break;
				}
			}
		} else {
			++p;
		}
		expect(")");
		parameters = false;
		if (!accept(";")) {
			// Parameters and body are one scope.
			expect("{");
			int open = p;
			int level = scopes.size(), deep = depth;
			try {
				statements();
			} catch (Fail e) {
				// The one recovery there is: nothing in a body is seen
				// outside of it, so a body that cannot be read is left
				// out up to its brace, and the rest is read as before.
				int close = open, braces = 1;
				for (; close < toks.size() && braces > 0; ++close) {
					braces += toks.get(close).is("{") ? 1
							: toks.get(close).is("}") ? -1 : 0;
				}
				if (!isolate || braces > 0 || e instanceof Pre.Refuse) {
					throw e;
				}
				for (int i = open; i < close; ++i) {
					bind[i] = toks.get(i).kind == Pre.ID ? UNREAD : NOT;
					declared[i] = 0;
				}
				while (scopes.size() > level) {
					scopes.remove(scopes.size() - 1);
				}
				depth = deep;
				skipped.add(new Object[]{open, close - 1, e.getMessage(),
						e.at});
				p = close;
			}
			functions.add(new int[]{first, p - 1});
		} else {
			for (int i = first; i < p; ++i) {
				functionOf[i] = -1;
			}
		}
		function = -1;
		scopes.remove(scopes.size() - 1);
	}

	// Statements.
	private void statements() {
		while (!accept("}")) {
			if (p >= toks.size()) {
				throw fail("a block is not closed");
			}
			statement();
		}
	}

	private void scoped() {
		scopes.add(new HashMap<>());
		statement();
		scopes.remove(scopes.size() - 1);
	}

	// The statement of a loop: its braces open no scope of their own.
	private void body() {
		if (accept("{")) {
			statements();
		} else {
			statement();
		}
	}

	private void statement() {
		deeper();
		if (accept("{")) {
			scopes.add(new HashMap<>());
			statements();
			scopes.remove(scopes.size() - 1);
		} else if (accept("if")) {
			expect("(");
			expression();
			expect(")");
			scoped();
			if (accept("else")) {
				scoped();
			}
		} else if (words.contains("switch") && accept("switch")) {
			expect("(");
			expression();
			expect(")");
			expect("{");
			scopes.add(new HashMap<>());
			while (!accept("}")) {
				if (accept("case")) {
					expression();
					expect(":");
				} else if (accept("default")) {
					expect(":");
				} else if (p >= toks.size()) {
					throw fail("a switch is not closed");
				} else {
					statement();
				}
			}
			scopes.remove(scopes.size() - 1);
		} else if (accept("while")) {
			scopes.add(new HashMap<>());
			expect("(");
			condition();
			expect(")");
			body();
			scopes.remove(scopes.size() - 1);
		} else if (accept("do")) {
			scoped();
			expect("while");
			expect("(");
			expression();
			expect(")");
			expect(";");
		} else if (accept("for")) {
			scopes.add(new HashMap<>());
			expect("(");
			if (!accept(";")) {
				simple();
			}
			if (!is(";")) {
				condition();
			}
			expect(";");
			if (!is(")")) {
				expression();
			}
			expect(")");
			body();
			scopes.remove(scopes.size() - 1);
		} else if (accept("return")) {
			if (!is(";")) {
				expression();
			}
			expect(";");
		} else if (accept("break") || accept("continue") ||
				accept("discard")) {
			expect(";");
		} else if (!accept(";")) {
			simple();
		}
		--depth;
	}

	private void simple() {
		if (declares()) {
			declaration(false);
		} else {
			expression();
			expect(";");
		}
	}

	private void condition() {
		if (declares()) {
			qualifiers();
			Sym type = type();
			int name = name();
			expect("=");
			assignment();
			declare(name, VAR, type);
		} else {
			expression();
		}
	}

	// Expressions. Each returns the struct its value is of, null for a
	// value that is none, UNK where declarations do not tell.
	private Sym expression() {
		Sym t = assignment();
		while (accept(",")) {
			t = assignment();
		}
		return t;
	}

	private Sym assignment() {
		deeper();
		Sym t = conditional();
		if (p < toks.size() && toks.get(p).kind == Pre.PUNCT &&
				ASSIGN.contains(toks.get(p).text)) {
			++p;
			assignment();
		}
		--depth;
		return t;
	}

	private Sym conditional() {
		Sym t = binary(0);
		if (accept("?")) {
			Sym a = expression();
			expect(":");
			Sym b = assignment();
			return a == b ? a : UNK;
		}
		return t;
	}

	private Sym binary(int level) {
		if (level == BINARY.length) {
			return unary();
		}
		Sym t = binary(level + 1);
		while (true) {
			boolean found = false;
			for (String op : BINARY[level]) {
				found |= accept(op);
			}
			if (!found) {
				return t;
			}
			binary(level + 1);
			// No operator makes a struct.
			t = null;
		}
	}

	private Sym unary() {
		boolean any = false;
		while (accept("+") || accept("-") || accept("!") || accept("~") ||
				accept("++") || accept("--")) {
			any = true;
		}
		Sym t = postfix();
		return any ? null : t;
	}

	private Sym postfix() {
		Sym t = primary();
		while (true) {
			if (accept("[")) {
				expression();
				expect("]");
			} else if (accept(".")) {
				if (p >= toks.size() || toks.get(p).kind != Pre.ID) {
					throw fail("expected a member");
				}
				int member = p++;
				if (toks.get(member).text.equals("length") && is("(") &&
						is(1, ")")) {
					bind[member] = NONE;
					p += 2;
					t = null;
				} else if (t == UNK) {
					bind[member] = UNKNOWN;
				} else if (t == null) {
					bind[member] = NONE;
				} else {
					Sym field = t.fields.get(toks.get(member).text);
					if (field == null) {
						throw new Fail("a struct has no such field",
								toks.get(member).at);
					}
					bind[member] = field.decl;
					t = field.type;
				}
			} else if (accept("++") || accept("--")) {
				t = null;
			} else {
				return t;
			}
		}
	}

	private Sym primary() {
		if (p >= toks.size()) {
			throw fail("expected a value");
		}
		Tok t = toks.get(p);
		if (t.kind == Pre.NUM || t.is("true") || t.is("false")) {
			++p;
			return null;
		}
		if (accept("(")) {
			deeper();
			Sym inner = expression();
			--depth;
			expect(")");
			return inner;
		}
		if (t.kind == Pre.ID && types.contains(t.text)) {
			++p;
			arrays();
			arguments();
			return null;
		}
		if (!name(p)) {
			throw fail("expected a value, not " + t.text);
		}
		Sym s = find(t.text);
		int at = p++;
		if (s != null && s.kind == STRUCT) {
			bind[at] = s.decl;
			arrays();
			arguments();
			return s;
		}
		if (is("(")) {
			if (s == null) {
				if (!FUNCTIONS.contains(t.text)) {
					throw new Fail(t.text + " is not declared", t.at);
				}
				bind[at] = NONE;
				arguments();
				return null;
			} else if (s.kind != FUNC) {
				throw new Fail(t.text + " is no function here", t.at);
			}
			bind[at] = s.decl;
			arguments();
			return s.type;
		}
		use(at);
		return s == null ? null : s.type;
	}

	// A name that must be a value.
	private void use(int at) {
		Tok t = toks.get(at);
		Sym s = find(t.text);
		if (s == null) {
			if (!t.text.startsWith("gl_")) {
				throw new Fail(t.text + " is not declared", t.at);
			}
			bind[at] = NONE;
		} else if (s.kind != VAR) {
			throw new Fail(t.text + " is no value", t.at);
		} else {
			bind[at] = s.decl;
		}
	}

	private void arguments() {
		expect("(");
		if (is("void") && is(1, ")")) {
			++p;
		}
		while (!is(")")) {
			assignment();
			if (!accept(",")) {
				break;
			}
		}
		expect(")");
	}
}
