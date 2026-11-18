package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The directives of one text as the driver gets it, run in order as its
 * preprocessor runs them: which lines are compiled, and what a macro use
 * expands to. Every answer is one of three: yes, no, or cannot tell.
 * <p>
 * A name the text does not define is not defined, but for the driver's
 * own names, those with GL_ or two underscores, of which only GL_ES,
 * __VERSION__ and, from ES 3.00 on, GL_FRAGMENT_PRECISION_HIGH are known.
 * Where drivers may differ, as on a name in #if that is not defined, a
 * value beyond 32 bits or a backslash in ES 1.00, it cannot tell; where
 * the text is broken, as by an #else after #else, it cannot tell anything.
 * Comments and directive lines are those of {@link PreprocessorScan}.
 */
public final class DirectiveRun {
	public enum State {ON, OFF, UNKNOWN}

	// More steps of expansion than this, and it cannot tell.
	private static final int STEPS = 4096;
	// Deeper nesting of arguments or parentheses, and it cannot tell.
	private static final int DEEPEST = 64;
	private static final int LONGEST = 1000;
	private static final Macro UNKNOWN = new Macro(null, null);
	// With "//" and "/*", which are no tokens, for spelling.
	private static final String[] PUNCTUATORS = {"//", "/*", "<<=", ">>=",
			"...", "##",
			"++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "^^",
			"+=", "-=", "*=", "/=", "%=", "&=", "|=", "^="};
	// The binary operators of #if, by precedence, lowest first.
	private static final List<List<String>> BINARY = List.of(List.of("||"),
			List.of("&&"), List.of("|"), List.of("^"), List.of("&"),
			List.of("==", "!="), List.of("<", ">", "<=", ">="),
			List.of("<<", ">>"), List.of("+", "-"), List.of("*", "/", "%"));

	private final List<String> code;
	private final State[] states;
	private final boolean[] directives;
	private final Map<String, Macro> start = new HashMap<>();
	// What #define and #undef did, in order: the line, the name, and its
	// macro, null when it is undefined.
	private final List<Integer> lines = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private final List<Macro> macros = new ArrayList<>();
	private final boolean broken;
	// Whether a macro opens or closes a paren that it does not close or
	// open, so that the parens of the code do not tell its calls.
	private boolean crooked;
	private Map<String, Macro> table;
	private int steps;
	private int depth;

	private record Tok(String text, boolean space, Set<String> hide) {
		boolean is(String s) {
			return text.equals(s);
		}

		boolean name() {
			char c = text.charAt(0);
			return word(c) && !Character.isDigit(c);
		}
	}

	// A macro, with params null when it is object-like.
	private record Macro(List<String> params, List<Tok> body) {
	}

	private DirectiveRun(String text, int version) {
		List<String> raw = Arrays.asList(text.split("\n", -1));
		PreprocessorScan scan = PreprocessorScan.scan(raw, null);
		code = scan.code();
		states = new State[raw.size()];
		directives = new boolean[raw.size()];
		start.put("GL_ES", new Macro(null, lex("1")));
		start.put("__VERSION__", new Macro(null, lex("" + version)));
		start.put("GL_FRAGMENT_PRECISION_HIGH",
				version >= 300 ? new Macro(null, lex("1")) : UNKNOWN);
		table = new HashMap<>(start);
		broken = !run(raw, scan, version);
		if (broken) {
			Arrays.fill(states, State.UNKNOWN);
		}
	}

	/**
	 * Run the directives of text, which the engine compiles, in the
	 * language version.
	 */
	public static DirectiveRun of(String text, int version) {
		return new DirectiveRun(text, version);
	}

	/** Return whether the 0-based line is compiled. */
	public State state(int line) {
		return states[line];
	}

	/**
	 * Return the code that the use of the macro name at the 0-based line
	 * and column becomes, or null when it cannot tell: when the use is not
	 * compiled for sure, is an argument of another macro, reaches a macro
	 * that is not known, or takes part of what follows it.
	 */
	public synchronized String expansion(int line, int column, String name) {
		if (broken || crooked || states[line] != State.ON ||
				directives[line] || !code.get(line).startsWith(name, column)) {
			return null;
		}
		table = new HashMap<>(start);
		for (int i = 0; i < lines.size() && lines.get(i) < line; ++i) {
			table.put(names.get(i), macros.get(i));
		}
		// No paren still open before the use may be a macro's. A line that
		// may be compiled is "?", and may hold no paren.
		List<Tok> before = new ArrayList<>();
		for (int n = 0; n <= line; ++n) {
			if (directives[n] || states[n] == State.OFF) {
				continue;
			}
			List<Tok> tokens = lex(n < line ? code.get(n)
					: code.get(n).substring(0, column));
			if (states[n] == State.ON) {
				before.addAll(tokens);
			} else if (code.get(n).matches(".*[()].*")) {
				return null;
			} else if (!tokens.isEmpty()) {
				before.add(new Tok("?", false, Set.of()));
			}
		}
		ArrayDeque<Integer> open = new ArrayDeque<>();
		for (int i = 0; i < before.size(); ++i) {
			if (before.get(i).is("(")) {
				open.push(i);
			} else if (before.get(i).is(")") && !open.isEmpty()) {
				open.pop();
			}
		}
		for (int i : open) {
			Tok called = i > 0 ? before.get(i - 1) : null;
			if (called != null && (called.is("?") || called.name() &&
					(names.contains(called.text) ||
							lookup(called.text) != null))) {
				return null;
			}
		}
		List<Tok> use = lex(code.get(line).substring(column));
		Macro macro = !use.get(0).is(name) || column > 0 &&
				word(code.get(line).charAt(column - 1))
				? null
				: lookup(name);
		if (macro == null || macro == UNKNOWN) {
			return null;
		}
		// The use, and the token after it, from the lines that follow.
		int end = macro.params == null ? 1 : close(use);
		for (int n = line + 1; end != -2 && (end < 0 || use.size() <= end) &&
				n < code.size() && use.size() < STEPS; ++n) {
			if (states[n] != State.ON || directives[n]) {
				return null;
			}
			use.addAll(lex(code.get(n)));
			end = macro.params == null ? 1 : close(use);
		}
		if (end < 0 || use.size() < end) {
			return null;
		}
		steps = depth = 0;
		List<Tok> out = expand(use.subList(0, end));
		Tok last = out == null || out.isEmpty() ? null : out.get(out.size() - 1);
		if (out == null || last != null && use.size() > end &&
				use.get(end).is("(") && last.name() &&
				!last.hide.contains(last.text) && lookup(last.text) != null) {
			return null;
		}
		String text = spell(out);
		return text.isEmpty() || text.length() > LONGEST ? null : text;
	}

	// The index after the ")" of the use at the start of tokens, -1 when
	// it is not there yet, -2 when it is no call.
	private static int close(List<Tok> tokens) {
		if (tokens.size() > 1 && !tokens.get(1).is("(")) {
			return -2;
		}
		for (int i = 1, depth = 0; i < tokens.size(); ++i) {
			depth += tokens.get(i).is("(") ? 1 : tokens.get(i).is(")") ? -1 : 0;
			if (depth == 0) {
				return i + 1;
			}
		}
		return -1;
	}

	// Runs the directives; returns false when the text is broken.
	private boolean run(List<String> raw, PreprocessorScan scan,
			int version) {
		PreprocessorScan.Directive[] at =
				new PreprocessorScan.Directive[raw.size()];
		for (PreprocessorScan.Directive d : scan.directives()) {
			at[d.line() - 1] = d;
		}
		// Per open group: the state around it, whether an arm was taken
		// (0 no, 1 maybe, 2 yes), and whether #else was seen (1).
		ArrayDeque<int[]> groups = new ArrayDeque<>();
		State current = State.ON;
		for (int i = 0; i < raw.size(); ++i) {
			if (splits(raw.get(i), code.get(i), scan.openComments().get(i),
					version, at[i] != null) ||
					at[i] != null && strings(raw.get(i))) {
				return false;
			}
			states[i] = current;
			PreprocessorScan.Directive d = at[i];
			if (d == null) {
				continue;
			}
			// A directive goes on in the lines its backslashes join.
			int first = i;
			directives[i] = true;
			StringBuilder joined = new StringBuilder(d.argument());
			while (joined.length() > 0 &&
					joined.charAt(joined.length() - 1) == '\\') {
				joined.setLength(joined.length() - 1);
				if (++i >= raw.size() || splits(raw.get(i), code.get(i),
						scan.openComments().get(i), version, true) ||
						strings(raw.get(i))) {
					return false;
				}
				directives[i] = true;
				joined.append(code.get(i).stripTrailing());
			}
			String argument = joined.toString().strip();
			List<Tok> tokens = lex(argument);
			int[] group = groups.peek();
			switch (d.kind()) {
				case IF, IFDEF, IFNDEF -> {
					State own = current == State.OFF
							? State.OFF
							: d.kind() == PreprocessorScan.Kind.IF
							? condition(tokens)
							: defined(tokens, d.kind() ==
									PreprocessorScan.Kind.IFNDEF);
					group = new int[]{current.ordinal(), 0, 0};
					groups.push(group);
					current = arm(group, own);
				}
				case ELIF, ELSE -> {
					boolean last = d.kind() == PreprocessorScan.Kind.ELSE;
					if (group == null || group[2] == 1 ||
							last && !argument.isEmpty()) {
						return false;
					}
					group[2] = last ? 1 : 0;
					states[first] = State.values()[group[0]];
					current = arm(group, group[1] == 2 ||
							group[0] == State.OFF.ordinal()
							? State.OFF
							: last ? State.ON : condition(tokens));
				}
				case ENDIF -> {
					if (group == null || !argument.isEmpty()) {
						return false;
					}
					groups.pop();
					current = State.values()[group[0]];
					states[first] = current;
				}
				case OTHER -> {
					boolean define = d.name().equals("define");
					if (current != State.OFF && (define ||
							d.name().equals("undef")) && !define(tokens,
							define, current == State.ON, first)) {
						return false;
					}
				}
				default -> {
				}
			}
			Arrays.fill(states, first + 1, i + 1, states[first]);
		}
		return groups.isEmpty();
	}

	// Whether drivers may read a backslash that ends the line in different
	// ways: ES 1.00 joins no lines, none joins behind white space, and
	// joining may end a block comment or join code to a line with "#".
	private static boolean splits(String line, String code, boolean open,
			int version, boolean directive) {
		return line.stripTrailing().endsWith("\\") && (version < 300 ||
				!line.endsWith("\\") || open ||
				code.endsWith("\\") && !directive);
	}

	// The state of the next arm of group, whose own condition is own.
	private static State arm(int[] group, State own) {
		State around = State.values()[group[0]];
		State arm = own == State.OFF || around == State.OFF
				? State.OFF
				: own == State.UNKNOWN || around == State.UNKNOWN ||
						group[1] == 1
				? State.UNKNOWN
				: State.ON;
		if (own == State.ON) {
			group[1] = 2;
		} else if (own == State.UNKNOWN && group[1] == 0) {
			group[1] = 1;
		}
		return arm;
	}

	// Whether a quoted part of a directive line holds what starts or ends
	// a comment, which a driver may read as one.
	private static boolean strings(String line) {
		int first = line.indexOf('"');
		int last = line.lastIndexOf('"');
		String quoted = first < last ? line.substring(first, last) : "";
		return quoted.contains("//") || quoted.contains("/*") ||
				quoted.contains("*/");
	}

	// Runs #define, or #undef, in an arm that is on for sure or that may
	// be; returns false when the text is broken.
	private boolean define(List<Tok> tokens, boolean define, boolean sure,
			int line) {
		if (tokens.isEmpty() || !tokens.get(0).name()) {
			return true;
		}
		String name = tokens.get(0).text;
		if (name.startsWith("GL_") || name.equals("defined") ||
				start.containsKey(name)) {
			return false;
		}
		Macro macro = define ? parse(tokens) : null;
		int parens = 0;
		for (Tok t : tokens) {
			parens += t.is("(") ? 1 : t.is(")") ? -1 : 0;
			crooked |= parens < 0;
		}
		crooked |= parens != 0;
		Macro old = table.get(name);
		if (!sure || name.contains("__") || !define && tokens.size() > 1 ||
				macro != null && old != null && !old.equals(macro)) {
			macro = UNKNOWN;
		}
		table.put(name, macro);
		lines.add(line);
		names.add(name);
		macros.add(macro);
		return true;
	}

	// The macro a #define makes, UNKNOWN when drivers may differ on it.
	private static Macro parse(List<Tok> tokens) {
		int i = 1;
		List<String> params = null;
		if (i < tokens.size() && tokens.get(i).is("(") &&
				!tokens.get(i).space) {
			params = new ArrayList<>();
			boolean closed = ++i < tokens.size() && tokens.get(i).is(")");
			while (!closed) {
				Tok t = i < tokens.size() ? tokens.get(i++) : null;
				if (t == null || !t.name() || t.is("defined") ||
						params.contains(t.text) || i >= tokens.size()) {
					return UNKNOWN;
				}
				params.add(t.text);
				closed = tokens.get(i).is(")");
				if (!closed && !tokens.get(i++).is(",")) {
					return UNKNOWN;
				}
			}
			++i;
		}
		List<Tok> body = new ArrayList<>();
		for (; i < tokens.size(); ++i) {
			Tok t = tokens.get(i);
			if ("#\"'\\@$`".indexOf(t.text.charAt(0)) >= 0) {
				return UNKNOWN;
			}
			body.add(body.isEmpty() ? new Tok(t.text, false, t.hide) : t);
		}
		return new Macro(params, body);
	}

	// #ifdef or, when not, #ifndef.
	private State defined(List<Tok> tokens, boolean not) {
		if (tokens.size() != 1 || !tokens.get(0).name()) {
			return State.UNKNOWN;
		}
		Macro macro = lookup(tokens.get(0).text);
		return macro == UNKNOWN
				? State.UNKNOWN
				: macro != null ^ not ? State.ON : State.OFF;
	}

	// #if and #elif.
	private State condition(List<Tok> tokens) {
		List<Tok> operands = new ArrayList<>();
		for (int i = 0; i < tokens.size(); ++i) {
			if (!tokens.get(i).is("defined")) {
				operands.add(tokens.get(i));
				continue;
			}
			boolean paren = i + 1 < tokens.size() && tokens.get(i + 1).is("(");
			int at = paren ? i + 2 : i + 1;
			if (at >= tokens.size() || !tokens.get(at).name() ||
					paren && (at + 1 >= tokens.size() ||
							!tokens.get(at + 1).is(")"))) {
				return State.UNKNOWN;
			}
			Macro macro = lookup(tokens.get(at).text);
			if (macro == UNKNOWN) {
				return State.UNKNOWN;
			}
			operands.add(new Tok(macro != null ? "1" : "0", true, Set.of()));
			i = paren ? at + 1 : at;
		}
		steps = depth = 0;
		List<Tok> expanded = expand(operands);
		int[] at = {0};
		Long value = expanded == null ? null : binary(expanded, at, 0);
		return value == null || at[0] != expanded.size()
				? State.UNKNOWN
				: value != 0 ? State.ON : State.OFF;
	}

	// The value of the operators of BINARY from level on, in 32 bits;
	// null when it cannot tell.
	private Long binary(List<Tok> tokens, int[] at, int level) {
		if (level == BINARY.size()) {
			return unary(tokens, at);
		}
		Long left = binary(tokens, at, level + 1);
		while (left != null && at[0] < tokens.size() &&
				BINARY.get(level).contains(tokens.get(at[0]).text)) {
			String op = tokens.get(at[0]++).text;
			Long right = binary(tokens, at, level + 1);
			if (right == null) {
				return null;
			}
			long a = left;
			long b = right;
			left = fits(switch (op) {
				case "||" -> a != 0 || b != 0 ? 1L : 0L;
				case "&&" -> a != 0 && b != 0 ? 1L : 0L;
				case "|" -> a | b;
				case "^" -> a ^ b;
				case "&" -> a & b;
				case "==" -> a == b ? 1L : 0L;
				case "!=" -> a != b ? 1L : 0L;
				case "<" -> a < b ? 1L : 0L;
				case ">" -> a > b ? 1L : 0L;
				case "<=" -> a <= b ? 1L : 0L;
				case ">=" -> a >= b ? 1L : 0L;
				case "<<" -> a < 0 || b < 0 || b > 31 ? null : a << b;
				case ">>" -> a < 0 || b < 0 || b > 31 ? null : a >> b;
				case "+" -> a + b;
				case "-" -> a - b;
				case "*" -> a * b;
				case "/" -> b == 0 ? null : a / b;
				default -> b == 0 ? null : a % b;
			});
		}
		return left;
	}

	private Long unary(List<Tok> tokens, int[] at) {
		if (++depth > DEEPEST) {
			return null;
		}
		Long value = operand(tokens, at);
		--depth;
		return value;
	}

	private Long operand(List<Tok> tokens, int[] at) {
		if (at[0] >= tokens.size()) {
			return null;
		}
		String t = tokens.get(at[0]++).text;
		if (t.equals("(")) {
			Long value = binary(tokens, at, 0);
			return value != null && at[0] < tokens.size() &&
					tokens.get(at[0]++).is(")") ? value : null;
		} else if (t.matches("[-+~!]")) {
			Long value = unary(tokens, at);
			return value == null ? null : fits(switch (t) {
				case "-" -> -value;
				case "~" -> ~value;
				case "!" -> value == 0 ? 1L : 0L;
				default -> value;
			});
		} else if (t.length() < 12 &&
				t.matches("0[0-7]*|[1-9][0-9]*|0[xX][0-9a-fA-F]+")) {
			return fits(t.length() > 1 && (t.charAt(1) | 32) == 'x'
					? Long.parseLong(t.substring(2), 16)
					: Long.parseLong(t, t.startsWith("0") ? 8 : 10));
		}
		return null;
	}

	private static Long fits(Long value) {
		return value == null || value < Integer.MIN_VALUE ||
				value > Integer.MAX_VALUE ? null : value;
	}

	// The tokens expanded with the macros of table; null when it cannot
	// tell.
	private List<Tok> expand(List<Tok> tokens) {
		ArrayDeque<Tok> work = new ArrayDeque<>(tokens);
		List<Tok> out = new ArrayList<>();
		while (!work.isEmpty()) {
			Tok t = work.poll();
			Macro macro = t.name() && !t.hide.contains(t.text)
					? lookup(t.text)
					: null;
			if (macro != null && macro != UNKNOWN && macro.params != null &&
					(work.isEmpty() || !work.peek().is("("))) {
				macro = null;
			}
			if (macro == null) {
				out.add(t);
				continue;
			} else if (macro == UNKNOWN || ++steps > STEPS) {
				return null;
			}
			Set<String> hide = new HashSet<>(t.hide);
			hide.add(t.text);
			List<List<Tok>> args = new ArrayList<>();
			if (macro.params != null) {
				Tok close = arguments(work, args);
				boolean none = macro.params.isEmpty();
				// The ")" must be where the name is, as drivers differ
				// on what may expand again otherwise.
				if (close == null || !close.hide.equals(t.hide) || (none
						? args.size() != 1 || !args.get(0).isEmpty()
						: args.size() != macro.params.size() ||
								args.contains(List.of()))) {
					return null;
				}
				if (++depth > DEEPEST) {
					return null;
				}
				for (int i = 0; i < args.size(); ++i) {
					args.set(i, expand(args.get(i)));
					if (args.get(i) == null) {
						return null;
					}
				}
				--depth;
			}
			List<Tok> body = new ArrayList<>();
			for (Tok b : macro.body) {
				int param = macro.params == null ? -1
						: macro.params.indexOf(b.text);
				List<Tok> part = param < 0 ? List.of(b) : args.get(param);
				for (int i = 0; i < part.size(); ++i) {
					Set<String> marked = new HashSet<>(part.get(i).hide);
					marked.addAll(hide);
					boolean space = body.isEmpty() ? t.space
							: i == 0 ? b.space : part.get(i).space;
					body.add(new Tok(part.get(i).text, space, marked));
				}
			}
			for (int i = body.size(); i-- > 0; ) {
				work.push(body.get(i));
			}
			if (work.size() > STEPS) {
				return null;
			}
		}
		return out;
	}

	// Takes "(" and the arguments up to its ")" from work into args, and
	// returns the ")", null when there is none.
	private static Tok arguments(ArrayDeque<Tok> work, List<List<Tok>> args) {
		work.poll();
		List<Tok> arg = new ArrayList<>();
		for (int depth = 0; !work.isEmpty(); ) {
			Tok t = work.poll();
			if (depth == 0 && (t.is(",") || t.is(")"))) {
				args.add(arg);
				arg = new ArrayList<>();
				if (t.is(")")) {
					return t;
				}
				continue;
			}
			depth += t.is("(") ? 1 : t.is(")") ? -1 : 0;
			arg.add(t);
		}
		return null;
	}

	// The macro of name now, UNKNOWN when it cannot tell, null for none.
	private Macro lookup(String name) {
		Macro macro = table.get(name);
		return macro == null && (name.startsWith("GL_") ||
				name.contains("__")) ? UNKNOWN : macro;
	}

	// The preprocessing tokens of s, which holds no comment.
	private static List<Tok> lex(String s) {
		List<Tok> tokens = new ArrayList<>();
		boolean space = false;
		for (int i = 0, n = s.length(); i < n; ) {
			char c = s.charAt(i);
			int end = i + 1;
			if (c == ' ' || c == '\t') {
				space = true;
				++i;
				continue;
			} else if (word(c) && !Character.isDigit(c)) {
				while (end < n && word(s.charAt(end))) {
					++end;
				}
			} else if (Character.isDigit(c) || c == '.' && end < n &&
					Character.isDigit(s.charAt(end))) {
				while (end < n && (word(s.charAt(end)) || s.charAt(end) ==
						'.' || "+-".indexOf(s.charAt(end)) >= 0 &&
						"eEpP".indexOf(s.charAt(end - 1)) >= 0)) {
					++end;
				}
			} else {
				for (String p : PUNCTUATORS) {
					if (s.startsWith(p, i)) {
						end = i + p.length();
						break;
					}
				}
			}
			tokens.add(new Tok(s.substring(i, end), space, Set.of()));
			space = false;
			i = end;
		}
		return tokens;
	}

	private static boolean word(char c) {
		return c < 128 && (Character.isLetterOrDigit(c) || c == '_');
	}

	// The tokens as code that reads as the same tokens again.
	private static String spell(List<Tok> tokens) {
		StringBuilder sb = new StringBuilder();
		String previous = null;
		for (Tok t : tokens) {
			if (previous != null && (t.space || lex(previous + t.text)
					.get(0).text.length() != previous.length())) {
				sb.append(' ');
			}
			sb.append(t.text);
			previous = t.text;
		}
		return sb.toString();
	}
}
