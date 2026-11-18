package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The directives of one text as the driver gets it, run in order, for
 * the shapes shaders use: switches on object-like macros whose values are
 * integers or such macros, #ifdef, #if 0, and comparisons. At the first
 * thing it does not read, it cannot tell anything from that line on: a
 * name in #if that is not such a macro, an operator it does not know, a
 * backslash at a line's end, a quote in a directive, a #define of a name
 * that is defined, of a driver's name, or a broken group.
 */
public final class DirectiveFloor {
	private static final Pattern TOKEN = Pattern.compile(
			"[A-Za-z_][A-Za-z0-9_]*|[0-9][A-Za-z0-9_.]*|&&|\\|\\||[=!<>]=|\\S");
	private static final List<List<String>> BINARY = List.of(List.of("||"),
			List.of("&&"), List.of("==", "!="), List.of("<", ">", "<=", ">="),
			List.of("+", "-"));

	final List<String> code;
	final DirectiveRun.State[] states;
	final boolean[] directives;
	// What follows each defined name in its #define.
	final Map<String, String> table = new HashMap<>();
	final Set<String> defined = new HashSet<>();
	// Whether an ES 3.00 directive goes on in the lines its backslashes
	// join; else a backslash ends what it can tell.
	private final boolean joins;
	private final int version;

	DirectiveFloor(String text, int version, int stop, boolean joins) {
		List<String> raw = Arrays.asList(text.split("\n", -1));
		PreprocessorScan scan = PreprocessorScan.scan(raw, null);
		code = scan.code();
		states = new DirectiveRun.State[raw.size()];
		directives = new boolean[raw.size()];
		this.version = version;
		this.joins = joins;
		table.put("GL_ES", " 1");
		table.put("__VERSION__", " " + version);
		if (version >= 300) {
			table.put("GL_FRAGMENT_PRECISION_HIGH", " 1");
		}
		int lost = run(raw, scan, stop);
		Arrays.fill(states, lost, states.length, DirectiveRun.State.UNKNOWN);
	}

	/**
	 * Run the directives of text, which the engine compiles, in the
	 * language version.
	 */
	public static DirectiveFloor of(String text, int version) {
		return of(text, version, false);
	}

	/** As {@link #of(String, int)}, joining ES 3.00 directives or not. */
	public static DirectiveFloor of(String text, int version,
			boolean joins) {
		return new DirectiveFloor(text, version, Integer.MAX_VALUE, joins);
	}

	/** Return whether the 0-based line is compiled. */
	public DirectiveRun.State state(int line) {
		return states[line];
	}

	// Runs the directives up to line stop; returns the line from which on
	// it cannot tell.
	private int run(List<String> raw, PreprocessorScan scan, int stop) {
		PreprocessorScan.Directive[] at =
				new PreprocessorScan.Directive[raw.size()];
		for (PreprocessorScan.Directive d : scan.directives()) {
			at[d.line() - 1] = d;
		}
		// Per open group: whether it is in compiled code, whether an arm
		// was taken, and whether #else was seen.
		ArrayDeque<boolean[]> groups = new ArrayDeque<>();
		boolean on = true;
		for (int i = 0; i < raw.size() && i < stop; ++i) {
			PreprocessorScan.Directive d = at[i];
			states[i] = on ? DirectiveRun.State.ON : DirectiveRun.State.OFF;
			if (d != null && raw.get(i).indexOf('"') >= 0 ||
					!joining(raw, i, d != null) &&
					raw.get(i).stripTrailing().endsWith("\\")) {
				return i;
			} else if (d == null) {
				continue;
			}
			directives[i] = true;
			String argument = d.argument();
			for (int first = i; argument.endsWith("\\"); ) {
				if (++i >= raw.size() || raw.get(i).indexOf('"') >= 0 ||
						!joining(raw, i, true) &&
						raw.get(i).stripTrailing().endsWith("\\")) {
					return first;
				}
				directives[i] = true;
				states[i] = states[first];
				argument = argument.substring(0, argument.length() - 1) +
						code.get(i).stripTrailing();
			}
			argument = argument.strip();
			boolean[] group = groups.peek();
			switch (d.kind()) {
				case IF, IFDEF, IFNDEF -> {
					Boolean taken = !on ? Boolean.FALSE
							: d.kind() == PreprocessorScan.Kind.IF
							? condition(argument)
							: ifdef(argument, d.kind() ==
									PreprocessorScan.Kind.IFNDEF);
					if (taken == null) {
						return i;
					}
					groups.push(new boolean[]{on, taken, false});
					on = taken;
				}
				case ELIF, ELSE -> {
					boolean last = d.kind() == PreprocessorScan.Kind.ELSE;
					if (group == null || group[2] || last &&
							!argument.isEmpty()) {
						return i;
					}
					states[i] = group[0] ? DirectiveRun.State.ON
							: DirectiveRun.State.OFF;
					Boolean taken = !group[0] || group[1] ? Boolean.FALSE
							: last ? Boolean.TRUE : condition(argument);
					if (taken == null) {
						return i;
					}
					group[1] |= taken;
					group[2] = last;
					on = taken;
				}
				case ENDIF -> {
					if (group == null || !argument.isEmpty()) {
						return i;
					}
					groups.pop();
					on = group[0];
					states[i] = on ? DirectiveRun.State.ON
							: DirectiveRun.State.OFF;
				}
				case OTHER -> {
					if (on && !define(d.name(), argument)) {
						return i;
					}
				}
				default -> {
				}
			}
		}
		return raw.size();
	}

	// Whether line ends in a backslash that joins the next line to the
	// directive of this one, in ES 3.00, behind no comment or space.
	private boolean joining(List<String> raw, int i, boolean directive) {
		return joins && directive && version >= 300 &&
				raw.get(i).endsWith("\\") && code.get(i).endsWith("\\");
	}

	// Runs #define and #undef; returns false when it cannot tell.
	private boolean define(String directive, String argument) {
		Matcher name = TOKEN.matcher(argument);
		boolean define = directive.equals("define");
		if (!define && !directive.equals("undef")) {
			return true;
		} else if (!name.lookingAt() || !name.group().matches("[A-Za-z_].*")) {
			return false;
		}
		String n = name.group();
		String rest = argument.substring(name.end());
		if (n.startsWith("GL_") || n.contains("__") || n.equals("defined") ||
				define && table.containsKey(n) ||
				!define && !rest.isBlank()) {
			return false;
		} else if (define) {
			table.put(n, rest);
			defined.add(n);
		} else {
			table.remove(n);
		}
		return true;
	}

	private Boolean ifdef(String argument, boolean not) {
		if (!argument.matches("[A-Za-z_][A-Za-z0-9_]*") ||
				!table.containsKey(argument) && reserved(argument)) {
			return null;
		}
		return table.containsKey(argument) ^ not;
	}

	private Boolean condition(String argument) {
		List<String> tokens = new ArrayList<>();
		Matcher m = TOKEN.matcher(argument);
		while (m.find()) {
			tokens.add(m.group());
		}
		int[] at = {0};
		Long value = tokens.size() > 256 ? null : binary(tokens, at, 0);
		return value == null || at[0] != tokens.size() ? null : value != 0;
	}

	private Long binary(List<String> tokens, int[] at, int level) {
		if (level == BINARY.size()) {
			return unary(tokens, at);
		}
		Long left = binary(tokens, at, level + 1);
		while (left != null && at[0] < tokens.size() &&
				BINARY.get(level).contains(tokens.get(at[0]))) {
			String op = tokens.get(at[0]++);
			Long right = binary(tokens, at, level + 1);
			if (right == null) {
				return null;
			}
			long a = left;
			long b = right;
			left = fits(switch (op) {
				case "||" -> a != 0 || b != 0 ? 1L : 0L;
				case "&&" -> a != 0 && b != 0 ? 1L : 0L;
				case "==" -> a == b ? 1L : 0L;
				case "!=" -> a != b ? 1L : 0L;
				case "<" -> a < b ? 1L : 0L;
				case ">" -> a > b ? 1L : 0L;
				case "<=" -> a <= b ? 1L : 0L;
				case ">=" -> a >= b ? 1L : 0L;
				case "+" -> a + b;
				default -> a - b;
			});
		}
		return left;
	}

	private Long unary(List<String> tokens, int[] at) {
		String t = at[0] < tokens.size() ? tokens.get(at[0]++) : "";
		if (t.equals("(")) {
			Long value = binary(tokens, at, 0);
			return at[0] < tokens.size() && tokens.get(at[0]++).equals(")")
					? value
					: null;
		} else if (t.equals("!") || t.equals("-")) {
			Long value = unary(tokens, at);
			return value == null ? null
					: t.equals("-") ? fits(-value) : value == 0 ? 1L : 0L;
		} else if (t.equals("defined")) {
			boolean paren = at[0] < tokens.size() &&
					tokens.get(at[0]).equals("(");
			at[0] += paren ? 1 : 0;
			String name = at[0] < tokens.size() ? tokens.get(at[0]++) : "";
			Boolean is = ifdef(name, false);
			return is == null || paren && (at[0] >= tokens.size() ||
					!tokens.get(at[0]++).equals(")")) ? null : is ? 1L : 0L;
		}
		return value(t, 0);
	}

	// The value of a literal, or of a macro that is one or names one.
	private Long value(String t, int depth) {
		if (t.matches("0|[1-9][0-9]{0,8}")) {
			return Long.parseLong(t);
		}
		String body = depth < 8 && t.matches("[A-Za-z_][A-Za-z0-9_]*")
				? table.get(t)
				: null;
		return body != null && body.matches("[ \t]+[A-Za-z0-9_]+[ \t]*")
				? value(body.strip(), depth + 1)
				: null;
	}

	private static Long fits(long value) {
		return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
				? null
				: value;
	}

	static boolean reserved(String name) {
		return name.startsWith("GL_") || name.contains("__");
	}
}
