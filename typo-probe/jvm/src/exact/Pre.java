package exact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lexer and preprocessor: the text the driver gets, to the tokens the
 * grammar is applied to. Every token keeps where it is written. What it
 * cannot run exactly ends in Fail, never in a guess.
 */
final class Pre {
	static final int ID = 1, NUM = 2, PUNCT = 3;

	static class Fail extends RuntimeException {
		final int at;

		Fail(String why, int at) {
			super(why);
			this.at = at;
		}
	}

	/** Not a fault of the program: a limit of this reader. */
	static final class Refuse extends Fail {
		Refuse(String why, int at) {
			super(why, at);
		}
	}

	static final class Tok {
		final int kind;
		final String text;
		/** Offset of its spelling in the program text. */
		final int at;
		/** Offset of the outermost invocation it came through, or -1. */
		final int via;
		final boolean space;
		final Set<String> hide;

		Tok(int kind, String text, int at, int via, boolean space,
				Set<String> hide) {
			this.kind = kind;
			this.text = text;
			this.at = at;
			this.via = via;
			this.space = space;
			this.hide = hide;
		}

		boolean is(String s) {
			return kind != NUM && text.equals(s);
		}

		@Override
		public String toString() {
			return text;
		}
	}

	static final class Macro {
		String name;
		List<Tok> params;
		List<Tok> body;
		boolean pastes;
	}

	/** A name of the preprocessor's own namespace, where it is written. */
	record Ref(int at, String name, char role) {
	}

	private static final String[] PUNCTS = {"<<=", ">>=", "++", "--", "<=",
			">=", "==", "!=", "&&", "||", "^^", "+=", "-=", "*=", "/=", "%=",
			"&=", "|=", "^=", "<<", ">>", "##"};
	private static final int MAX_TOKENS = 2_000_000;
	private static final int MAX_DEPTH = 64;

	final String src;
	final int version;
	final Map<String, Boolean> config;
	/** The driver's names a condition asked about. */
	final Set<String> asked = new HashSet<>();
	final List<Tok> out = new ArrayList<>();
	final List<Ref> refs = new ArrayList<>();
	/** A macro's parameter and a place of it in the macro's text. */
	final List<int[]> links = new ArrayList<>();
	/** Where every directive stands, compiled or not. */
	final List<Integer> directives = new ArrayList<>();
	private final Map<String, Macro> macros = new HashMap<>();
	// parentActive, taken, active, sawElse for each open conditional.
	private final ArrayList<boolean[]> conds = new ArrayList<>();
	private int depth;

	Pre(String src, int version, Map<String, Boolean> config) {
		this.src = src;
		this.version = version;
		this.config = config;
	}

	/** Every name written in code or a directive, comments left out. */
	static List<Tok> words(String src) {
		List<Tok> words = new ArrayList<>();
		for (List<Tok> line : new Pre(src, 300, Map.of()).lex(true)) {
			for (Tok t : line) {
				if (t.kind == ID) {
					words.add(t);
				}
			}
		}
		return words;
	}

	void run() {
		List<Tok> run = new ArrayList<>();
		for (List<Tok> line : lex(false)) {
			if (!line.isEmpty() && line.get(0).is("#")) {
				flush(run);
				directives.add(line.get(0).at);
				directive(line);
			} else if (active()) {
				run.addAll(line);
			}
		}
		flush(run);
		if (!conds.isEmpty()) {
			throw new Fail("a conditional is not closed", src.length());
		}
	}

	private boolean active() {
		return conds.isEmpty() || conds.get(conds.size() - 1)[2];
	}

	// Lexing. Lenient reads on where exact reading would stop: it is
	// for listing words only.
	private List<List<Tok>> lex(boolean lenient) {
		List<List<Tok>> lines = new ArrayList<>();
		List<Tok> cur = new ArrayList<>();
		boolean space = false;
		int n = src.length();
		for (int i = 0; i < n; ) {
			char c = src.charAt(i);
			char d = i + 1 < n ? src.charAt(i + 1) : 0;
			if (c == '\\' && d == '\n') {
				if (version < 300 && !lenient) {
					throw new Refuse("a line continues behind a backslash, " +
							"which compilers of version 100 read differently",
							i);
				}
				i += 2;
			} else if (c == '\n') {
				lines.add(cur);
				cur = new ArrayList<>();
				space = false;
				++i;
			} else if (c == ' ' || c == '\t' || c == '\r') {
				space = true;
				++i;
			} else if (c == '/' && d == '/') {
				while (i < n && src.charAt(i) != '\n') {
					if (src.charAt(i) == '\\' && i + 1 < n &&
							src.charAt(i + 1) == '\n') {
						if (version < 300 && !lenient) {
							throw new Refuse("a comment continues behind a " +
									"backslash in version 100", i);
						}
						++i;
					}
					++i;
				}
				space = true;
			} else if (c == '/' && d == '*') {
				int end = src.indexOf("*/", i + 2);
				if (end < 0) {
					if (lenient) {
						break;
					}
					throw new Fail("a comment is not closed", i);
				}
				if (!lenient && !cur.isEmpty() && cur.get(0).is("#") &&
						src.substring(i, end).indexOf('\n') >= 0) {
					throw new Refuse("a comment goes over the end of a " +
							"directive's line", i);
				}
				space = true;
				i = end + 2;
			} else if (Character.isLetter(c) && c < 128 || c == '_') {
				int e = i + 1;
				while (e < n && word(src.charAt(e))) {
					++e;
				}
				if (!lenient && e + 1 < n && src.charAt(e) == '\\' &&
						src.charAt(e + 1) == '\n' && e + 2 < n &&
						word(src.charAt(e + 2))) {
					throw new Refuse("a name is written across two lines", i);
				}
				cur.add(new Tok(ID, src.substring(i, e), i, -1, space, null));
				space = false;
				i = e;
			} else if (c >= '0' && c <= '9' ||
					c == '.' && d >= '0' && d <= '9') {
				int e = i + 1;
				boolean hex = c == '0' && (d == 'x' || d == 'X');
				while (e < n) {
					char x = src.charAt(e);
					char p = src.charAt(e - 1);
					if (word(x) || x == '.' || !hex && (x == '+' || x == '-')
							&& (p == 'e' || p == 'E')) {
						++e;
					} else {
						break;
					}
				}
				cur.add(new Tok(NUM, src.substring(i, e), i, -1, space, null));
				space = false;
				i = e;
			} else {
				String p = String.valueOf(c);
				for (String candidate : PUNCTS) {
					if (src.startsWith(candidate, i)) {
						p = candidate;
						break;
					}
				}
				cur.add(new Tok(PUNCT, p, i, -1, space, null));
				space = false;
				i += p.length();
			}
		}
		lines.add(cur);
		return lines;
	}

	private static boolean word(char c) {
		return c == '_' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z' ||
				c >= 'A' && c <= 'Z';
	}

	// Directives.
	private void directive(List<Tok> line) {
		if (line.size() < 2) {
			return;
		}
		Tok command = line.get(1);
		String name = command.text;
		boolean on = active();
		switch (name) {
			case "if", "ifdef", "ifndef" -> {
				boolean value = false;
				if (on) {
					value = name.equals("if")
							? eval(line.subList(2, line.size()), command.at)
							: defined(operand(line)) == name.equals("ifdef");
				}
				conds.add(new boolean[]{on, value, on && value, false});
			}
			case "elif", "else" -> {
				if (conds.isEmpty()) {
					throw new Fail("#" + name + " without #if", command.at);
				}
				boolean[] top = conds.get(conds.size() - 1);
				if (top[3]) {
					throw new Fail("#" + name + " behind #else", command.at);
				}
				top[3] = name.equals("else");
				// An arm behind one that was taken is not read at all.
				boolean value = top[0] && !top[1] && (top[3] ||
						eval(line.subList(2, line.size()), command.at));
				top[2] = value;
				top[1] |= value;
			}
			case "endif" -> {
				if (conds.isEmpty()) {
					throw new Fail("#endif without #if", command.at);
				}
				conds.remove(conds.size() - 1);
			}
			case "define" -> {
				if (on) {
					define(line);
				}
			}
			case "undef" -> {
				if (on) {
					Tok t = operand(line);
					macros.remove(t.text);
				}
			}
			case "error" -> {
				if (on) {
					throw new Fail("#error is compiled", command.at);
				}
			}
			case "line" -> {
				if (on) {
					expand(line.subList(2, line.size()), false);
				}
			}
			case "pragma", "extension", "version" -> {
			}
			default -> {
				if (on) {
					throw new Fail("unknown directive #" + name, command.at);
				}
			}
		}
	}

	private Tok operand(List<Tok> line) {
		if (line.size() != 3 || line.get(2).kind != ID) {
			throw new Fail("a directive needs one name", line.get(1).at);
		}
		Tok t = line.get(2);
		refs.add(new Ref(t.at, t.text, line.get(1).text.equals("undef")
				? 'U' : 'T'));
		return t;
	}

	private void define(List<Tok> line) {
		if (line.size() < 3 || line.get(2).kind != ID) {
			throw new Fail("#define needs a name", line.get(1).at);
		}
		Tok name = line.get(2);
		if (name.text.startsWith("GL_") || name.text.equals("defined")) {
			throw new Fail("a name of the language is defined", name.at);
		}
		Macro m = new Macro();
		m.name = name.text;
		int i = 3;
		if (i < line.size() && line.get(i).is("(") && !line.get(i).space) {
			m.params = new ArrayList<>();
			++i;
			while (i < line.size() && !line.get(i).is(")")) {
				Tok p = line.get(i);
				if (p.kind != ID) {
					throw new Fail("a macro's parameter is no name", p.at);
				}
				m.params.add(p);
				++i;
				if (i < line.size() && line.get(i).is(",")) {
					++i;
				}
			}
			if (i == line.size()) {
				throw new Fail("a macro's parameters are not closed", name.at);
			}
			++i;
		}
		m.body = new ArrayList<>(line.subList(i, line.size()));
		for (Tok b : m.body) {
			if (b.is("#") || b.is("##")) {
				m.pastes = true;
			}
			int p = param(m, b);
			if (p >= 0) {
				links.add(new int[]{m.params.get(p).at, b.at});
			}
		}
		Macro old = macros.get(m.name);
		if (old != null) {
			if (!same(old, m)) {
				throw new Fail("a macro is defined again with another text",
						name.at);
			}
			// Two definitions must stay the same text: their names are
			// renamed together.
			for (int k = 0; k < m.body.size(); ++k) {
				if (m.body.get(k).kind == ID) {
					links.add(new int[]{old.body.get(k).at, m.body.get(k).at});
				}
			}
			for (int k = 0; m.params != null && k < m.params.size(); ++k) {
				links.add(new int[]{old.params.get(k).at,
						m.params.get(k).at});
			}
		}
		macros.put(m.name, m);
		refs.add(new Ref(name.at, name.text, 'D'));
	}

	private static int param(Macro m, Tok t) {
		if (m.params != null && t.kind == ID) {
			for (int i = 0; i < m.params.size(); ++i) {
				if (m.params.get(i).text.equals(t.text)) {
					return i;
				}
			}
		}
		return -1;
	}

	private static boolean same(Macro a, Macro b) {
		return (a.params == null) == (b.params == null) &&
				texts(a.body).equals(texts(b.body)) &&
				(a.params == null || texts(a.params).equals(texts(b.params)));
	}

	private static List<String> texts(List<Tok> tokens) {
		List<String> texts = new ArrayList<>();
		for (Tok t : tokens) {
			texts.add(t.text);
		}
		return texts;
	}

	// Whether the driver or the program defines a name. What only the
	// driver decides is asked of the configuration and noted.
	private boolean defined(Tok t) {
		String name = t.text;
		if (macros.containsKey(name)) {
			return true;
		}
		return driver(name) != null;
	}

	// The number a name of the driver stands for, or null.
	private String driver(String name) {
		if (name.equals("GL_ES")) {
			return "1";
		} else if (name.equals("__VERSION__")) {
			return String.valueOf(version);
		} else if (name.equals("__LINE__") || name.equals("__FILE__")) {
			return "0";
		} else if (name.equals("GL_FRAGMENT_PRECISION_HIGH") &&
				version >= 300) {
			return "1";
		} else if (name.startsWith("GL_") || name.contains("__")) {
			asked.add(name);
			return Boolean.TRUE.equals(config.get(name)) ? "1" : null;
		}
		return null;
	}

	// Expansion.
	private void flush(List<Tok> run) {
		out.addAll(expand(run, true));
		run.clear();
		if (out.size() > MAX_TOKENS) {
			throw new Refuse("the program is too large", 0);
		}
	}

	private List<Tok> expand(List<Tok> tokens, boolean whole) {
		if (++depth > MAX_DEPTH) {
			throw new Refuse("macros are nested too deeply", 0);
		}
		List<Tok> result = new ArrayList<>();
		ArrayDeque<Tok> in = new ArrayDeque<>(tokens);
		int steps = 0;
		while (!in.isEmpty()) {
			Tok t = in.pollFirst();
			if (t.kind != ID) {
				result.add(t);
				continue;
			}
			Macro m = macros.get(t.text);
			if (m == null) {
				String number = driver(t.text);
				if (number != null) {
					if (t.text.startsWith("__L") || t.text.startsWith("__F")) {
						throw new Refuse("__LINE__ or __FILE__ is used", t.at);
					}
					result.add(new Tok(NUM, number, t.at, t.via, t.space,
							null));
				} else {
					result.add(t);
				}
				continue;
			}
			if (t.hide != null && t.hide.contains(t.text)) {
				// Its own name in its text is a plain name, as in C.
				result.add(t);
				continue;
			}
			if (m.pastes) {
				throw new Refuse("a macro uses # or ##, which the language " +
						"does not have", t.at);
			}
			if (++steps > MAX_TOKENS) {
				throw new Refuse("the program is too large", t.at);
			}
			Set<String> hide = new HashSet<>();
			if (t.hide != null) {
				hide.addAll(t.hide);
			}
			hide.add(m.name);
			int via = t.via >= 0 ? t.via : t.at;
			List<Tok> body = new ArrayList<>();
			if (m.params == null) {
				for (Tok b : m.body) {
					body.add(new Tok(b.kind, b.text, b.at, via, b.space, hide));
				}
			} else {
				if (in.isEmpty() || !in.peekFirst().is("(")) {
					// Its name alone invokes nothing.
					result.add(t);
					continue;
				}
				in.pollFirst();
				List<List<Tok>> args = new ArrayList<>();
				List<Tok> arg = new ArrayList<>();
				int parens = 0;
				while (true) {
					if (in.isEmpty()) {
						throw new Fail("a macro's arguments are not closed " +
								"before the next directive", t.at);
					}
					Tok a = in.pollFirst();
					if (a.is("(")) {
						++parens;
					} else if (a.is(")") && parens-- == 0) {
						break;
					} else if (a.is(",") && parens == 0) {
						args.add(arg);
						arg = new ArrayList<>();
						continue;
					}
					arg.add(a);
				}
				if (!arg.isEmpty() || !args.isEmpty()) {
					args.add(arg);
				}
				if (args.isEmpty() && m.params.size() == 1) {
					args.add(arg);
				}
				if (args.size() != m.params.size()) {
					throw new Fail("a macro is invoked with " + args.size() +
							" arguments and has " + m.params.size(), t.at);
				}
				List<List<Tok>> expanded = new ArrayList<>();
				for (List<Tok> a : args) {
					expanded.add(expand(a, false));
				}
				for (Tok b : m.body) {
					int p = param(m, b);
					if (p < 0) {
						body.add(new Tok(b.kind, b.text, b.at, via, b.space,
								hide));
						continue;
					}
					for (Tok a : expanded.get(p)) {
						// What an argument wrote keeps its own place.
						body.add(new Tok(a.kind, a.text, a.at,
								a.via >= 0 ? a.via : via, a.space, a.hide));
					}
				}
			}
			refs.add(new Ref(t.at, t.text, 'I'));
			for (int i = body.size(); i-- > 0; ) {
				in.addFirst(body.get(i));
			}
		}
		--depth;
		return result;
	}

	// The condition of #if and #elif.
	private List<Tok> expr;
	private int pos;

	private boolean eval(List<Tok> tokens, int at) {
		List<Tok> plain = new ArrayList<>();
		for (int i = 0; i < tokens.size(); ++i) {
			Tok t = tokens.get(i);
			if (!t.is("defined")) {
				plain.add(t);
				continue;
			}
			boolean paren = i + 1 < tokens.size() && tokens.get(i + 1).is("(");
			int k = i + (paren ? 2 : 1);
			if (k >= tokens.size() || tokens.get(k).kind != ID ||
					paren && (k + 1 >= tokens.size() ||
							!tokens.get(k + 1).is(")"))) {
				throw new Fail("defined needs a name", t.at);
			}
			Tok name = tokens.get(k);
			refs.add(new Ref(name.at, name.text, 'T'));
			plain.add(new Tok(NUM, defined(name) ? "1" : "0", name.at, -1,
					true, null));
			i = k + (paren ? 1 : 0);
		}
		expr = expand(plain, false);
		pos = 0;
		depth = 0;
		long value = binary(0, at);
		if (pos != expr.size()) {
			throw new Fail("a condition cannot be read", at);
		}
		depth = 0;
		return value != 0;
	}

	private static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"},
			{"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"},
			{"+", "-"}, {"*", "/", "%"}};

	private long binary(int level, int at) {
		if (level == LEVELS.length) {
			return unary(at);
		}
		long a = binary(level + 1, at);
		while (pos < expr.size()) {
			String op = null;
			for (String candidate : LEVELS[level]) {
				if (expr.get(pos).is(candidate)) {
					op = candidate;
				}
			}
			if (op == null) {
				break;
			}
			++pos;
			long b = binary(level + 1, at);
			if ((op.equals("/") || op.equals("%")) && b == 0) {
				throw new Fail("a condition divides by zero", at);
			}
			a = switch (op) {
				case "||" -> a != 0 || b != 0 ? 1 : 0;
				case "&&" -> a != 0 && b != 0 ? 1 : 0;
				case "|" -> a | b;
				case "^" -> a ^ b;
				case "&" -> a & b;
				case "==" -> a == b ? 1 : 0;
				case "!=" -> a != b ? 1 : 0;
				case "<" -> a < b ? 1 : 0;
				case ">" -> a > b ? 1 : 0;
				case "<=" -> a <= b ? 1 : 0;
				case ">=" -> a >= b ? 1 : 0;
				case "<<" -> a << b;
				case ">>" -> a >> b;
				case "+" -> a + b;
				case "-" -> a - b;
				case "*" -> a * b;
				case "/" -> a / b;
				default -> a % b;
			};
		}
		return a;
	}

	private long unary(int at) {
		// Signs before a value are counted, not called for.
		int not = 0;
		boolean minus = false, flip = false;
		while (pos < expr.size()) {
			Tok t = expr.get(pos);
			if (t.is("!")) {
				if (minus || flip) {
					throw new Fail("a condition mixes signs before !", at);
				}
				++not;
			} else if (t.is("-") || t.is("~")) {
				if (not > 0 || minus || flip) {
					throw new Fail("a condition mixes signs", at);
				}
				minus = t.is("-");
				flip = !minus;
			} else if (!t.is("+")) {
				break;
			}
			++pos;
		}
		if (pos == expr.size()) {
			throw new Fail("a condition ends early", at);
		}
		Tok t = expr.get(pos++);
		long value;
		if (t.is("(")) {
			if (++depth > MAX_DEPTH) {
				throw new Refuse("a condition is nested too deeply", at);
			}
			value = binary(0, at);
			--depth;
			if (pos == expr.size() || !expr.get(pos++).is(")")) {
				throw new Fail("a condition's parenthesis is not closed", at);
			}
		} else if (t.kind == NUM) {
			try {
				String s = t.text.replaceAll("[uU]$", "");
				value = Long.decode(s);
			} catch (NumberFormatException e) {
				throw new Fail("a condition has a number that is no int",
						t.at);
			}
		} else {
			throw new Fail("a condition names " + t.text +
					", which is not defined", t.at);
		}
		if (not > 0) {
			value = (value != 0) == (not % 2 == 0) ? 1 : 0;
		}
		if (flip) {
			value = ~value;
		}
		return minus ? -value : value;
	}
}
