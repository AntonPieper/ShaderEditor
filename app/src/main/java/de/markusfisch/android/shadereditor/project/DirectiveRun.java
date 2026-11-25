package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The directives of one text as the driver gets it, run in order, for
 * the shapes shaders use: switches on object-like macros, #ifdef, #if 0,
 * and comparisons and sums of integers. At the first thing it does not
 * read, it cannot tell anything from that line on: a backslash at a
 * line's end, a quote or an unclosed block comment in a directive, a
 * #define of a name that is defined or of a driver's name, or a broken
 * group. A condition it cannot decide, as a name only the driver knows,
 * leaves only its group unknown when no arm of it defines anything.
 */
public final class DirectiveRun {
	/** Whether a line is compiled, as far as it can tell. */
	public enum State {
		ON, OFF, UNKNOWN
	}

	private static final Pattern TOKEN = Pattern.compile(
			"[A-Za-z_][A-Za-z0-9_]*|[0-9][A-Za-z0-9_.]*|&&|\\|\\||[=!<>]=|\\S");
	private static final List<List<String>> BINARY = List.of(List.of("||"),
			List.of("&&"), List.of("==", "!="), List.of("<", ">", "<=", ">="),
			List.of("+", "-"));

	private final State[] states;
	// What follows each defined name in its #define.
	private final Map<String, String> table = new HashMap<>();

	private DirectiveRun(String text, int version) {
		List<String> raw = Arrays.asList(text.split("\n", -1));
		states = new State[raw.size()];
		table.put("GL_ES", " 1");
		table.put("__VERSION__", " " + version);
		if (version >= 300) {
			table.put("GL_FRAGMENT_PRECISION_HIGH", " 1");
		}
		Arrays.fill(states, run(raw, PreprocessorScan.scan(raw, null)),
				states.length, State.UNKNOWN);
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

	// Runs the directives; returns the line from which on it cannot tell.
	private int run(List<String> raw, PreprocessorScan scan) {
		PreprocessorScan.Directive[] at =
				new PreprocessorScan.Directive[raw.size()];
		for (PreprocessorScan.Directive d : scan.directives()) {
			at[d.line() - 1] = d;
		}
		// Per open group: whether it is in compiled code, whether an arm
		// was taken, and whether #else was seen.
		ArrayDeque<boolean[]> groups = new ArrayDeque<>();
		boolean on = true;
		for (int i = 0; i < raw.size(); ++i) {
			PreprocessorScan.Directive d = at[i];
			states[i] = on ? State.ON : State.OFF;
			// A comment that is still open goes on with the directive.
			if (d != null && (raw.get(i).indexOf('"') >= 0 ||
					scan.openComments().get(i)) ||
					raw.get(i).stripTrailing().endsWith("\\")) {
				return i;
			} else if (d == null) {
				continue;
			}
			String argument = d.argument().strip();
			boolean[] group = groups.peek();
			Boolean taken = Boolean.FALSE;
			switch (d.kind()) {
				case IF, IFDEF, IFNDEF -> {
					taken = !on ? Boolean.FALSE
							: d.kind() == PreprocessorScan.Kind.IF
							? condition(argument)
							: ifdef(argument, d.kind() ==
									PreprocessorScan.Kind.IFNDEF);
					if (taken != null) {
						groups.push(new boolean[]{on, taken, false});
						on = taken;
					}
				}
				case ELIF, ELSE -> {
					boolean last = d.kind() == PreprocessorScan.Kind.ELSE;
					if (group == null || group[2] || last &&
							!argument.isEmpty()) {
						return i;
					}
					states[i] = group[0] ? State.ON
							: State.OFF;
					taken = !group[0] || group[1] ? Boolean.FALSE
							: last ? Boolean.TRUE : condition(argument);
					if (taken != null) {
						group[1] |= taken;
						group[2] = last;
						on = taken;
					} else {
						groups.pop();
					}
				}
				case ENDIF -> {
					if (group == null || !argument.isEmpty()) {
						return i;
					}
					groups.pop();
					on = group[0];
					states[i] = on ? State.ON
							: State.OFF;
				}
				case OTHER -> {
					if (on && !define(d.name(), argument)) {
						return i;
					}
				}
				default -> {
				}
			}
			if (taken == null) {
				// Only in compiled code is a condition read: the rest of
				// the group is unknown, what follows it compiled.
				int end = close(raw, at, scan.openComments(), i);
				if (end < 0) {
					return i;
				}
				Arrays.fill(states, i + 1, end, State.UNKNOWN);
				states[end] = State.ON;
				on = true;
				i = end;
			}
		}
		return groups.isEmpty() ? raw.size() : 0;
	}

	// The line of the #endif of the group whose arm starts after line i,
	// or -1 where what follows the group may depend on its arms: one of
	// them defines or undefines a name or joins lines, or it has no end.
	private static int close(List<String> raw,
			PreprocessorScan.Directive[] at, List<Boolean> open, int i) {
		for (int n = i + 1, depth = 0; n < raw.size(); ++n) {
			PreprocessorScan.Directive d = at[n];
			if (raw.get(n).stripTrailing().endsWith("\\") || d != null &&
					(open.get(n) || d.kind() == PreprocessorScan.Kind.OTHER &&
					(d.name().equals("define") ||
							d.name().equals("undef")))) {
				return -1;
			} else if (d == null) {
				continue;
			}
			switch (d.kind()) {
				case IF, IFDEF, IFNDEF -> ++depth;
				case ENDIF -> {
					if (depth-- == 0) {
						return n;
					}
				}
				default -> {
				}
			}
		}
		return -1;
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
		if (reserved(n) || n.equals("defined") ||
				define && table.containsKey(n) ||
				!define && !rest.isBlank()) {
			return false;
		} else if (define) {
			table.put(n, rest);
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
		int[] at = {0};
		Long value = !expand(argument, tokens, new ArrayDeque<>()) ||
				tokens.size() > 256 ? null : binary(tokens, at, 0);
		return value == null || at[0] != tokens.size() ? null : value != 0;
	}

	// Puts the tokens of text in tokens, an object-like macro by the tokens
	// of its body as the preprocessor does, but in the operand of defined
	// and in its own body; false where it does not read that.
	private boolean expand(String text, List<String> tokens,
			ArrayDeque<String> in) {
		Matcher m = TOKEN.matcher(text);
		while (m.find()) {
			String t = m.group();
			String body = table.get(t);
			int n = tokens.size();
			if (t.equals("defined") && !in.isEmpty() || in.size() > 16 ||
					tokens.size() > 256) {
				return false;
			} else if (body == null || body.startsWith("(") ||
					in.contains(t) || n > 0 && (tokens.get(n - 1)
					.equals("defined") || n > 1 && tokens.get(n - 1)
					.equals("(") && tokens.get(n - 2).equals("defined"))) {
				tokens.add(t);
			} else {
				in.push(t);
				boolean read = expand(body, tokens, in);
				in.pop();
				if (!read) {
					return false;
				}
			}
		}
		return true;
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
		return t.matches("0|[1-9][0-9]{0,8}") ? Long.parseLong(t) : null;
	}

	private static Long fits(long value) {
		return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
				? null
				: value;
	}

	private static boolean reserved(String name) {
		return name.startsWith("GL_") || name.contains("__");
	}
}
