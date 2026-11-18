package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one macro use becomes, asked for one use at a time, where that
 * needs no judgment: a use on one line, outside the arguments of any
 * macro, of macros with no "#" in them that never reach themselves, and
 * whose code takes nothing of what follows. Otherwise it cannot tell.
 */
public final class ExpansionFloor {
	private static final Pattern TOKEN = Pattern.compile(
			"[ \t]*([A-Za-z_][A-Za-z0-9_]*|\\.?[0-9][A-Za-z0-9_.]*|<<=|>>=|" +
					"[-+*/%<>=!&|^]=|<<|>>|&&|\\|\\||\\+\\+|--|\\S)");

	private record Tok(String text, boolean space) {
		boolean name() {
			return text.matches("[A-Za-z_].*");
		}
	}

	private ExpansionFloor() {
	}

	/**
	 * Return the code the use of the macro name at the 0-based line and
	 * column of text becomes, or null when it cannot tell.
	 */
	public static String of(String text, int version, boolean joins,
			int line, int column, String name) {
		DirectiveFloor whole = DirectiveFloor.of(text, version, joins);
		DirectiveFloor before = new DirectiveFloor(text, version, line, joins);
		String code = before.code.get(line);
		if (whole.state(line) != DirectiveRun.State.ON ||
				whole.directives[line] || !before.table.containsKey(name) ||
				!code.startsWith(name, column) ||
				column > 0 && String.valueOf(code.charAt(column - 1))
						.matches("[A-Za-z0-9_.]")) {
			return null;
		}
		// No paren still open before the use may be a macro's, and no
		// macro may open or close a paren of its own.
		List<Tok> tokens = new ArrayList<>();
		int parens = 0;
		boolean crooked = false;
		for (int n = 0; n <= line; ++n) {
			String part = n < line ? before.code.get(n)
					: code.substring(0, column);
			if (whole.state(n) != DirectiveRun.State.ON) {
				continue;
			} else if (!whole.directives[n]) {
				tokens.addAll(lex(part));
				continue;
			}
			// The parens of a directive, which may go on in lines.
			for (Tok t : lex(part)) {
				parens += t.text.equals("(") ? 1 : t.text.equals(")") ? -1 : 0;
				crooked |= parens < 0;
			}
			if (!part.endsWith("\\") && (crooked || parens != 0)) {
				return null;
			}
		}
		ArrayDeque<Integer> open = new ArrayDeque<>();
		for (int i = 0; i < tokens.size(); ++i) {
			if (tokens.get(i).text.equals("(")) {
				open.push(i);
			} else if (tokens.get(i).text.equals(")") && !open.isEmpty()) {
				open.pop();
			}
		}
		for (int i : open) {
			String called = i > 0 ? tokens.get(i - 1).text : "";
			if (before.defined.contains(called) ||
					DirectiveFloor.reserved(called)) {
				return null;
			}
		}
		List<Tok> use = lex(code.substring(column));
		int end = 1;
		if (function(before.table, name) && (use.size() < 2 ||
				!use.get(1).text.equals("("))) {
			return null;
		} else if (function(before.table, name)) {
			for (int depth = 0; end < use.size() &&
					(depth += use.get(end).text.equals("(") ? 1
							: use.get(end).text.equals(")") ? -1 : 0) > 0; ) {
				++end;
			}
			if (end >= use.size()) {
				return null;
			}
			++end;
		}
		List<Tok> out = expand(use.subList(0, end), before.table,
				new HashSet<>(), new int[]{0});
		if (out == null || out.isEmpty() || function(before.table,
				out.get(out.size() - 1).text) && (end >= use.size() ||
				use.get(end).text.equals("("))) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < out.size(); ++i) {
			String t = out.get(i).text;
			if (i > 0 && (out.get(i).space || lex(out.get(i - 1).text + t)
					.size() < 2 || (out.get(i - 1).text + t).matches(
					".*(//|/\\*).*"))) {
				sb.append(' ');
			}
			sb.append(t);
		}
		return sb.length() > 1000 ? null : sb.toString();
	}

	// Expands tokens with the macros of table, none of active, in at most
	// 256 steps; null when it cannot tell.
	private static List<Tok> expand(List<Tok> tokens, Map<String, String> table,
			Set<String> active, int[] steps) {
		List<Tok> out = new ArrayList<>();
		for (int i = 0; i < tokens.size(); ) {
			Tok t = tokens.get(i);
			String definition = t.name() ? table.get(t.text) : null;
			if (definition == null && t.name() &&
					DirectiveFloor.reserved(t.text)) {
				return null;
			} else if (definition == null || function(table, t.text) &&
					(i + 1 >= tokens.size() ||
							!tokens.get(i + 1).text.equals("("))) {
				out.add(t);
				++i;
				continue;
			} else if (active.contains(t.text) || ++steps[0] > 256 ||
					definition.indexOf('#') >= 0) {
				return null;
			}
			List<String> params = new ArrayList<>();
			List<Tok> body = lex(definition);
			int next = i + 1;
			if (function(table, t.text)) {
				int close = texts(body).indexOf(")");
				for (int p = 1; p < close; p += 2) {
					params.add(body.get(p).text);
				}
				if (close < 0 || String.join("", texts(body.subList(0,
						close + 1))).matches("\\(([A-Za-z_]\\w*(,[A-Za-z_]" +
						"\\w*)*)?\\)") == false) {
					return null;
				}
				body = body.subList(close + 1, body.size());
				List<List<Tok>> args = new ArrayList<>();
				next = arguments(tokens, i + 1, args);
				if (next < 0 || args.size() != Math.max(1, params.size()) ||
						params.isEmpty() != args.get(0).isEmpty() ||
						!params.isEmpty() && args.contains(List.of())) {
					return null;
				}
				for (int a = 0; a < args.size(); ++a) {
					args.set(a, expand(args.get(a), table, active, steps));
					if (args.get(a) == null) {
						return null;
					}
				}
				List<Tok> filled = new ArrayList<>();
				for (Tok b : body) {
					int p = params.indexOf(b.text);
					List<Tok> part = p < 0 ? List.of(b) : args.get(p);
					for (int k = 0; k < part.size(); ++k) {
						filled.add(k == 0 ? new Tok(part.get(k).text, b.space)
								: part.get(k));
					}
				}
				body = filled;
			}
			Set<String> inner = new HashSet<>(active);
			inner.add(t.text);
			List<Tok> result = expand(body, table, inner, steps);
			// What follows may not call what it ends in.
			if (result == null || !result.isEmpty() &&
					next < tokens.size() && tokens.get(next).text.equals("(") &&
					function(table, result.get(result.size() - 1).text)) {
				return null;
			}
			for (int k = 0; k < result.size(); ++k) {
				out.add(k == 0 ? new Tok(result.get(k).text, t.space)
						: result.get(k));
			}
			i = next;
		}
		return out;
	}

	// The arguments of the call whose "(" is at, into args; the index
	// after its ")", -1 when there is none or a bracket of an argument is
	// left open.
	private static int arguments(List<Tok> tokens, int at,
			List<List<Tok>> args) {
		List<Tok> arg = new ArrayList<>();
		int depth = 0;
		int brackets = 0;
		for (int i = at + 1; i < tokens.size(); ++i) {
			String t = tokens.get(i).text;
			if (depth == 0 && (t.equals(",") || t.equals(")"))) {
				if (brackets != 0) {
					return -1;
				}
				args.add(arg);
				arg = new ArrayList<>();
				if (t.equals(")")) {
					return i + 1;
				}
				continue;
			}
			depth += t.equals("(") ? 1 : t.equals(")") ? -1 : 0;
			brackets += "[{".contains(t) ? 1 : "]}".contains(t) ? -1 : 0;
			arg.add(tokens.get(i));
		}
		return -1;
	}

	private static boolean function(Map<String, String> table, String name) {
		String definition = table.get(name);
		return definition != null && definition.startsWith("(");
	}

	private static List<String> texts(List<Tok> tokens) {
		List<String> texts = new ArrayList<>();
		for (Tok t : tokens) {
			texts.add(t.text);
		}
		return texts;
	}

	private static List<Tok> lex(String s) {
		List<Tok> tokens = new ArrayList<>();
		Matcher m = TOKEN.matcher(s);
		while (m.find()) {
			tokens.add(new Tok(m.group(1), m.start(1) > m.start()));
		}
		return tokens;
	}
}
