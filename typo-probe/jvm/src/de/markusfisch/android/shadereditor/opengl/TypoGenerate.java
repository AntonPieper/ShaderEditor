package de.markusfisch.android.shadereditor.opengl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the typo corpus: every program of the examples and the
 * ShaderToy fixtures, assembled as the engine compiles it, unchanged
 * ("o-…") and with one typo of a fixed kind ("K01-…"). Deterministic.
 *
 * Usage: TypoGenerate <examples dir> <shadertoy fixtures dir> <out dir>
 */
final class TypoGenerate {
	static final int PER_KIND = 22;
	static final long SEED = 2026;

	static final Set<String> TYPES = Set.of("void", "bool", "int", "uint",
			"float", "vec2", "vec3", "vec4", "bvec2", "bvec3", "bvec4", "ivec2",
			"ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "mat2", "mat3", "mat4",
			"mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4",
			"mat4x2", "mat4x3", "mat4x4", "sampler2D", "samplerCube",
			"sampler3D", "samplerExternalOES", "sampler2DArray");
	static final Set<String> KEYWORDS = Set.of("attribute", "const",
			"uniform", "varying", "break", "continue", "do", "for", "while",
			"if", "else", "in", "out", "inout", "true", "false", "lowp",
			"mediump", "highp", "precision", "invariant", "discard", "return",
			"struct", "layout", "centroid", "flat", "smooth", "switch", "case",
			"default");
	static final List<String> K09_WORDS = List.of("uniform", "float",
			"return", "void", "const", "if", "for", "else", "precision", "in",
			"out");
	// Built-in functions and the argument counts that are valid.
	static final Map<String, Set<Integer>> BUILTINS = new HashMap<>();

	static {
		for (String f : ("radians degrees sin cos tan asin acos exp log exp2 " +
				"log2 sqrt inversesqrt abs sign floor ceil fract normalize " +
				"length round trunc roundEven sinh cosh tanh asinh acosh atanh " +
				"isnan isinf transpose inverse determinant dFdx dFdy fwidth " +
				"any all not").split(" ")) {
			BUILTINS.put(f, Set.of(1));
		}
		for (String f : ("pow mod min max step distance dot cross reflect " +
				"matrixCompMult lessThan lessThanEqual greaterThan " +
				"greaterThanEqual equal notEqual outerProduct textureCube " +
				"texelFetch textureSize").split(" ")) {
			BUILTINS.put(f, Set.of(2));
		}
		for (String f : "clamp mix smoothstep faceforward refract textureLod texture2DLod textureCubeLod"
				.split(" ")) {
			BUILTINS.put(f, Set.of(3));
		}
		BUILTINS.put("atan", Set.of(1, 2));
		BUILTINS.put("texture2D", Set.of(2, 3));
		BUILTINS.put("texture", Set.of(2, 3));
		BUILTINS.put("textureCube", Set.of(2, 3));
		BUILTINS.put("texture2DProj", Set.of(2, 3));
		BUILTINS.put("texelFetch", Set.of(3));
		BUILTINS.put("textureSize", Set.of(2));
	}

	static final String[] KIND_NAMES = {
			"", "missing semicolon", "parenthesis", "brace", "misspelled name",
			"misspelled built-in", "wrong number of arguments",
			"vec3 for vec2", "missing .0", "keyword typo",
			"unterminated comment", "#endif missing",
			"use before declaration"};

	// --- tokens of one source file --------------------------------------

	static final class Tok {
		final String text;
		final int at, line, col; // line, col 1-based
		final char kind; // I name, N number, P punctuation
		int depth; // brace depth before this token
		boolean body; // inside a function body
		int paren; // parenthesis depth before this token
		int fn = -1; // index of the function body it is in

		Tok(String text, int at, int line, int col, char kind) {
			this.text = text;
			this.at = at;
			this.line = line;
			this.col = col;
			this.kind = kind;
		}

		boolean is(String s) {
			return text.equals(s);
		}

		@Override
		public String toString() {
			return text;
		}
	}

	static final class Source {
		final String path, text;
		final List<Tok> toks = new ArrayList<>();
		final List<int[]> ppLines = new ArrayList<>(); // line, start offset
		final List<String> lines;
		final List<int[]> functions = new ArrayList<>(); // first tok, open, close
		final Set<String> declared = new HashSet<>();
		final Set<Integer> declSites = new HashSet<>(); // tok index of a declared name
		final Map<String, Integer> fnDefs = new HashMap<>();
		final Set<String> prototypes = new HashSet<>();

		Source(String path, String text) {
			this.path = path;
			this.text = text;
			this.lines = List.of(text.split("\n", -1));
			lex();
			structure();
		}

		int lineOf(int at) {
			int line = 1;
			for (int i = 0; i < at; ++i) {
				line += text.charAt(i) == '\n' ? 1 : 0;
			}
			return line;
		}

		int colOf(int at) {
			return at - (text.lastIndexOf('\n', at - 1) + 1) + 1;
		}

		private void lex() {
			int i = 0, n = text.length();
			boolean lineStart = true;
			while (i < n) {
				char c = text.charAt(i);
				if (c == '\n') {
					lineStart = true;
					++i;
					continue;
				}
				if (c == ' ' || c == '\t' || c == '\r') {
					++i;
					continue;
				}
				if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
					while (i < n && text.charAt(i) != '\n') {
						++i;
					}
					continue;
				}
				if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
					int end = text.indexOf("*/", i + 2);
					i = end < 0 ? n : end + 2;
					continue;
				}
				if (c == '#' && lineStart) {
					ppLines.add(new int[]{lineOf(i), i});
					while (i < n && text.charAt(i) != '\n') {
						if (text.charAt(i) == '\\' && i + 1 < n &&
								text.charAt(i + 1) == '\n') {
							++i;
						}
						++i;
					}
					continue;
				}
				lineStart = false;
				int start = i;
				char kind;
				if (Character.isLetter(c) || c == '_') {
					while (i < n && (Character.isLetterOrDigit(text.charAt(i)) ||
							text.charAt(i) == '_')) {
						++i;
					}
					kind = 'I';
				} else if (Character.isDigit(c) || (c == '.' && i + 1 < n &&
						Character.isDigit(text.charAt(i + 1)))) {
					while (i < n && (Character.isLetterOrDigit(text.charAt(i)) ||
							text.charAt(i) == '.' || ((text.charAt(i) == '-' ||
							text.charAt(i) == '+') && (text.charAt(i - 1) == 'e' ||
							text.charAt(i - 1) == 'E')))) {
						++i;
					}
					kind = 'N';
				} else {
					String two = i + 1 < n ? text.substring(i, i + 2) : "";
					if (List.of("<=", ">=", "==", "!=", "&&", "||", "^^", "++",
							"--", "+=", "-=", "*=", "/=", "<<", ">>").contains(two)) {
						i += 2;
					} else {
						++i;
					}
					kind = 'P';
				}
				toks.add(new Tok(text.substring(start, i), start, lineOf(start),
						colOf(start), kind));
			}
		}

		private void structure() {
			int depth = 0, paren = 0, fn = -1, fnDepth = -1;
			for (int i = 0; i < toks.size(); ++i) {
				Tok t = toks.get(i);
				t.depth = depth;
				t.paren = paren;
				t.body = fn >= 0;
				t.fn = fn;
				switch (t.text) {
					case "(" -> ++paren;
					case ")" -> --paren;
					case "{" -> {
						if (depth == 0 && i > 0 && toks.get(i - 1).is(")")) {
							int head = i - 1, d = 0;
							for (; head >= 0; --head) {
								if (toks.get(head).is(")")) {
									++d;
								} else if (toks.get(head).is("(")) {
									if (--d == 0) {
										break;
									}
								}
							}
							functions.add(new int[]{head - 2, i, -1});
							fn = functions.size() - 1;
							fnDepth = 0;
							fnDefs.put(toks.get(head - 1).text, fn);
						}
						++depth;
					}
					case "}" -> {
						--depth;
						if (fn >= 0 && depth == fnDepth) {
							functions.get(fn)[2] = i;
							fn = -1;
						}
					}
					default -> {
					}
				}
			}
			// Declared names: a name behind a type (or a struct name).
			for (int i = 1; i < toks.size(); ++i) {
				Tok t = toks.get(i), prev = toks.get(i - 1);
				if (t.kind == 'I' && TYPES.contains(prev.text) &&
						!TYPES.contains(t.text) && !KEYWORDS.contains(t.text)) {
					declared.add(t.text);
					declSites.add(i);
					if (i + 1 < toks.size() && toks.get(i + 1).is("(") &&
							t.depth == 0) {
						// A prototype: the head ends in ';'.
						int d = 0;
						for (int j = i + 1; j < toks.size(); ++j) {
							if (toks.get(j).is("(")) {
								++d;
							} else if (toks.get(j).is(")") && --d == 0) {
								if (j + 1 < toks.size() && toks.get(j + 1).is(";")) {
									prototypes.add(t.text);
								}
								break;
							}
						}
					}
				}
			}
		}

		Tok prevTok(int i) {
			return i > 0 ? toks.get(i - 1) : null;
		}

		Tok nextTok(int i) {
			return i + 1 < toks.size() ? toks.get(i + 1) : null;
		}
	}

	// --- a mutation ------------------------------------------------------

	static final class Mutation {
		String file, kind, sub, before, after;
		int line, col;
		List<Integer> targets = new ArrayList<>();
		String text; // the whole mutated file

		static Mutation replace(Source s, int at, int end, String with,
				String kind, String sub) {
			Mutation m = new Mutation();
			m.file = s.path;
			m.kind = kind;
			m.sub = sub;
			m.before = s.text.substring(at, end);
			m.after = with;
			m.line = s.lineOf(at);
			m.col = s.colOf(at);
			m.text = s.text.substring(0, at) + with + s.text.substring(end);
			m.targets.add(m.line);
			return m;
		}
	}

	static final Set<String> BINOPS = Set.of("+", "-", "*", "/", "<", ">",
			"<=", ">=", "==", "!=");
	static final Set<String> ASSIGN = Set.of("=", "+=", "-=", "*=", "/=");
	static final String[] QWERTY = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

	static char neighbour(char c, Random r) {
		char lower = Character.toLowerCase(c);
		for (int row = 0; row < 3; ++row) {
			int k = QWERTY[row].indexOf(lower);
			if (k >= 0) {
				List<Character> near = new ArrayList<>();
				if (k > 0) {
					near.add(QWERTY[row].charAt(k - 1));
				}
				if (k + 1 < QWERTY[row].length()) {
					near.add(QWERTY[row].charAt(k + 1));
				}
				char x = near.get(r.nextInt(near.size()));
				return Character.isUpperCase(c) ? Character.toUpperCase(x) : x;
			}
		}
		return c;
	}

	/** One letter off: a neighbour key, a letter dropped, two swapped. */
	static String misspell(String word, Random r, Set<String> taken) {
		for (int attempt = 0; attempt < 30; ++attempt) {
			int op = r.nextInt(3);
			String w = word;
			if (op == 0) {
				List<Integer> letters = new ArrayList<>();
				for (int i = 0; i < w.length(); ++i) {
					if (Character.isLetter(w.charAt(i))) {
						letters.add(i);
					}
				}
				int i = letters.get(r.nextInt(letters.size()));
				w = w.substring(0, i) + neighbour(w.charAt(i), r) +
						w.substring(i + 1);
			} else if (op == 1 && w.length() > 2) {
				int i = 1 + r.nextInt(w.length() - 1);
				w = w.substring(0, i) + w.substring(i + 1);
			} else if (op == 2 && w.length() > 2) {
				int i = 1 + r.nextInt(w.length() - 2);
				w = w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) +
						w.substring(i + 2);
			}
			if (!w.equals(word) && w.matches("[A-Za-z_][A-Za-z0-9_]*") &&
					!taken.contains(w) && !TYPES.contains(w) &&
					!KEYWORDS.contains(w) && !BUILTINS.containsKey(w) &&
					!w.startsWith("gl_")) {
				return w;
			}
		}
		return null;
	}

	// --- candidates by kind ---------------------------------------------

	static List<Mutation> candidates(int kind, Source s, Random r,
			Set<String> taken) {
		List<Mutation> out = new ArrayList<>();
		List<Tok> toks = s.toks;
		String k = String.format("K%02d", kind);
		for (int i = 0; i < toks.size(); ++i) {
			Tok t = toks.get(i), prev = s.prevTok(i), next = s.nextTok(i);
			switch (kind) {
				case 1 -> {
					if (t.is(";") && t.body && t.paren == 0) {
						out.add(Mutation.replace(s, t.at, t.at + 1, "", k, ""));
					}
				}
				case 2 -> {
					if (t.is(")") && t.body) {
						out.add(Mutation.replace(s, t.at, t.at + 1, "", k,
								"missing )"));
						out.add(Mutation.replace(s, t.at, t.at + 1, "))", k,
								"extra )"));
					}
				}
				case 3 -> {
					if (t.is("}") && (t.body || (t.depth == 1 && isFnClose(s, i)))) {
						out.add(Mutation.replace(s, t.at, t.at + 1, "", k,
								"missing }"));
						out.add(Mutation.replace(s, t.at, t.at + 1, "}}", k,
								"extra }"));
					}
				}
				case 4 -> {
					if (t.kind == 'I' && s.declared.contains(t.text) &&
							!s.declSites.contains(i) && !t.text.equals("main") &&
							(prev == null || !prev.is("."))) {
						String w = misspell(t.text, r, taken);
						if (w != null) {
							out.add(Mutation.replace(s, t.at, t.at +
									t.text.length(), w, k, ""));
						}
					}
				}
				case 5 -> {
					if (t.kind == 'I' && BUILTINS.containsKey(t.text) &&
							next != null && next.is("(") &&
							!s.declared.contains(t.text)) {
						String w = misspell(t.text, r, taken);
						if (w != null) {
							out.add(Mutation.replace(s, t.at, t.at +
									t.text.length(), w, k, ""));
						}
					}
				}
				case 6 -> {
					if (t.kind == 'I' && next != null && next.is("(") &&
							t.body && (prev == null || !TYPES.contains(prev.text)) &&
							(BUILTINS.containsKey(t.text) ||
									s.fnDefs.containsKey(t.text)) &&
							!TYPES.contains(t.text)) {
						List<int[]> args = args(s, i + 1);
						if (args == null || args.size() < 2) {
							break;
						}
						Set<Integer> valid = BUILTINS.getOrDefault(t.text,
								Set.of(args.size()));
						int[] last = args.get(args.size() - 1);
						int[] before = args.get(args.size() - 2);
						int close = last[1];
						String lastText = s.text.substring(last[0], last[1]).trim();
						if (!valid.contains(args.size() - 1)) {
							Mutation m = Mutation.replace(s, before[1], close, "",
									k, "argument dropped");
							out.add(m);
						}
						if (!valid.contains(args.size() + 1)) {
							out.add(Mutation.replace(s, close, close, ", " +
									lastText, k, "argument doubled"));
						}
					}
				}
				case 7 -> {
					if (t.body && t.text.matches("vec[234]") && next != null &&
							next.kind == 'I' && i + 2 < toks.size() &&
							toks.get(i + 2).is("=") && prev != null &&
							(prev.is(";") || prev.is("{") || prev.is("}") ||
									prev.is("const"))) {
						int n = t.text.charAt(3) - '0';
						String w = "vec" + (n == 4 ? 3 : n + 1);
						out.add(Mutation.replace(s, t.at, t.at + 4, w, k, ""));
					}
				}
				case 8 -> {
					if (t.kind == 'N' && t.text.matches("\\d+\\.0*") &&
							prev != null && next != null) {
						Tok pp = i > 1 ? toks.get(i - 2) : null;
						boolean prevBinary = (BINOPS.contains(prev.text) &&
								pp != null && (pp.kind == 'I' || pp.kind == 'N' ||
								pp.is(")") || pp.is("]"))) ||
								ASSIGN.contains(prev.text);
						if (BINOPS.contains(next.text) || prevBinary) {
							String w = t.text.substring(0, t.text.indexOf('.'));
							out.add(Mutation.replace(s, t.at, t.at +
									t.text.length(), w, k, ""));
						}
					}
				}
				case 9 -> {
					if (t.kind == 'I' && K09_WORDS.contains(t.text)) {
						String w = misspell(t.text, r, taken);
						if (w != null) {
							out.add(Mutation.replace(s, t.at, t.at +
									t.text.length(), w, k, ""));
						}
					}
				}
				default -> {
				}
			}
		}
		if (kind == 10) {
			Set<Integer> seen = new HashSet<>();
			for (Tok t : toks) {
				if (t.body && !t.is("}") && seen.add(t.line) &&
						t.col == firstCol(s, t.line)) {
					out.add(Mutation.replace(s, t.at, t.at, "/* ", k, ""));
				}
			}
		}
		if (kind == 11) {
			Pattern endif = Pattern.compile("^\\s*#\\s*endif\\b.*$");
			Pattern open = Pattern.compile("^\\s*#\\s*if(n?def)?\\b.*$");
			List<Integer> stack = new ArrayList<>();
			for (int ln = 1; ln <= s.lines.size(); ++ln) {
				String line = s.lines.get(ln - 1);
				if (open.matcher(line).matches()) {
					stack.add(ln);
				} else if (endif.matcher(line).matches()) {
					int ifLine = stack.remove(stack.size() - 1);
					int at = lineStart(s, ln);
					int end = at + line.length() + 1;
					Mutation m = Mutation.replace(s, at, Math.min(end,
							s.text.length()), "", k, "");
					m.targets.add(ifLine);
					out.add(m);
				}
			}
		}
		if (kind == 12) {
			out.addAll(localsMoved(s));
			out.addAll(functionsMoved(s));
		}
		return out;
	}

	static boolean isFnClose(Source s, int i) {
		for (int[] f : s.functions) {
			if (f[2] == i) {
				return true;
			}
		}
		return false;
	}

	static int firstCol(Source s, int line) {
		String l = s.lines.get(line - 1);
		int c = 0;
		while (c < l.length() && (l.charAt(c) == ' ' || l.charAt(c) == '\t')) {
			++c;
		}
		return c + 1;
	}

	static int lineStart(Source s, int line) {
		int at = 0;
		for (int i = 1; i < line; ++i) {
			at = s.text.indexOf('\n', at) + 1;
		}
		return at;
	}

	// The [start, end) offsets of each argument of the call whose '(' is
	// token open; the end of the last is the offset of ')'.
	static List<int[]> args(Source s, int open) {
		List<int[]> args = new ArrayList<>();
		int d = 0, start = s.toks.get(open).at + 1;
		for (int j = open; j < s.toks.size(); ++j) {
			Tok t = s.toks.get(j);
			if (t.is("(") || t.is("[")) {
				++d;
			} else if (t.is(")") || t.is("]")) {
				if (--d == 0) {
					if (j == open + 1) {
						return args;
					}
					args.add(new int[]{start, t.at});
					return args;
				}
			} else if (t.is(",") && d == 1) {
				args.add(new int[]{start, t.at});
				start = t.at + 1;
			}
		}
		return null;
	}

	static final Pattern LOCAL = Pattern.compile(
			"^(\\s*)(const\\s+)?(float|int|bool|vec[234]|mat[234]|ivec[234])" +
					"\\s+([A-Za-z_]\\w*)\\s*=[^;]*;\\s*(//.*)?$");

	static List<Mutation> localsMoved(Source s) {
		List<Mutation> out = new ArrayList<>();
		for (int ln = 1; ln <= s.lines.size(); ++ln) {
			Matcher m = LOCAL.matcher(s.lines.get(ln - 1));
			if (!m.matches()) {
				continue;
			}
			Tok first = firstTokOn(s, ln);
			if (first == null || !first.body || first.paren != 0) {
				continue;
			}
			String name = m.group(4);
			int fn = first.fn;
			int use = -1;
			for (Tok t : s.toks) {
				if (t.line > ln && t.fn == fn && t.is(name)) {
					use = t.line;
					break;
				}
			}
			if (use < 0) {
				continue;
			}
			String useLine = s.lines.get(use - 1).replaceAll("//.*", "").trim();
			if (!useLine.endsWith(";") && !useLine.endsWith("{")) {
				continue;
			}
			List<String> lines = new ArrayList<>(s.lines);
			String decl = lines.remove(ln - 1);
			lines.add(use - 1, decl);
			Mutation mu = new Mutation();
			mu.file = s.path;
			mu.kind = "K12";
			mu.sub = "local moved below its first use";
			mu.before = "line " + ln + ": " + decl.trim();
			mu.after = "moved below line " + use;
			mu.line = use - 1;
			mu.col = 1;
			mu.text = String.join("\n", lines);
			mu.targets.add(use - 1);
			mu.targets.add(use);
			out.add(mu);
		}
		return out;
	}

	static Tok firstTokOn(Source s, int line) {
		for (Tok t : s.toks) {
			if (t.line == line) {
				return t;
			}
		}
		return null;
	}

	static List<Mutation> functionsMoved(Source s) {
		List<Mutation> out = new ArrayList<>();
		for (int f = 0; f < s.functions.size(); ++f) {
			int[] fr = s.functions.get(f);
			if (fr[0] < 0 || fr[2] < 0) {
				continue;
			}
			String name = s.toks.get(fr[0] + 1).text;
			if (name.equals("main") || s.prototypes.contains(name)) {
				continue;
			}
			int caller = -1, callLine = -1;
			for (int g = f + 1; g < s.functions.size() && caller < 0; ++g) {
				int[] gr = s.functions.get(g);
				for (int j = gr[1]; j <= gr[2]; ++j) {
					if (s.toks.get(j).is(name) && j + 1 < s.toks.size() &&
							s.toks.get(j + 1).is("(")) {
						caller = g;
						callLine = s.toks.get(j).line;
						break;
					}
				}
			}
			if (caller < 0) {
				continue;
			}
			int from = s.toks.get(fr[0]).line, to = s.toks.get(fr[2]).line;
			if (firstCol(s, from) != s.toks.get(fr[0]).col ||
					!s.lines.get(to - 1).trim().equals("}")) {
				continue;
			}
			int gEnd = s.toks.get(s.functions.get(caller)[2]).line;
			List<String> lines = new ArrayList<>(s.lines);
			List<String> moved = new ArrayList<>(lines.subList(from - 1, to));
			lines.subList(from - 1, to).clear();
			int count = to - from + 1;
			int insertAt = gEnd - count; // after g's closing line, 0-based index
			List<String> block = new ArrayList<>();
			block.add("");
			block.addAll(moved);
			lines.addAll(insertAt, block);
			Mutation mu = new Mutation();
			mu.file = s.path;
			mu.kind = "K12";
			mu.sub = "function moved below its first caller";
			mu.before = "lines " + from + "-" + to + ": " +
					s.lines.get(from - 1).trim();
			mu.after = "moved below line " + gEnd;
			mu.line = callLine - count;
			mu.col = 1;
			mu.text = String.join("\n", lines);
			mu.targets.add(callLine - count);
			mu.targets.add(insertAt + 2); // the moved head, 1-based
			out.add(mu);
		}
		return out;
	}

	// --- main ------------------------------------------------------------

	record Program(String project, String entry, String file,
			TypoCorpus.Project p) {
	}

	public static void main(String[] args) throws Exception {
		File examples = new File(args[0]), fixtures = new File(args[1]),
				out = new File(args[2]);
		File scratch = new File(out, "tmp");
		scratch.mkdirs();
		File texts = new File(out, "texts");
		texts.mkdirs();
		List<TypoCorpus.Project> projects = TypoCorpus.projects(examples,
				fixtures);
		// Every program, and for every file the program that compiles it.
		List<Program> programs = new ArrayList<>();
		Map<String, Program> hostOf = new LinkedHashMap<>(); // project/file
		for (TypoCorpus.Project p : projects) {
			List<String> entries = new ArrayList<>();
			if (p.entry != null) {
				entries.add(p.entry);
			} else {
				for (String f : p.files.keySet()) {
					String e = TypoCorpus.entryFor(p, f);
					if (e != null && !entries.contains(e) && f.endsWith(".frag")) {
						entries.add(e);
					}
				}
			}
			for (String entry : entries) {
				var prep = de.markusfisch.android.shadereditor.project.Preparation
						.prepare(new de.markusfisch.android.shadereditor.project
										.DirectoryProjectFiles(write(p, scratch)),
								de.markusfisch.android.shadereditor.project
										.ProjectPath.parse(entry).value(),
								de.markusfisch.android.shadereditor.project
										.ProjectLimits.DEFAULT,
								UniformTable.builtins());
				if (prep.plan() == null) {
					throw new IllegalStateException(p.name + " " + entry + ": " +
							prep.diagnostics());
				}
				for (var prog : prep.plan().programs()) {
					String file = prog.file().toString();
					Program pr = new Program(p.name, entry, file, p);
					if (programs.stream().anyMatch(x -> x.project.equals(p.name) &&
							x.file.equals(file))) {
						continue;
					}
					programs.add(pr);
					for (var at : prog.sourceMap().lines()) {
						if (at != null && !at.isGenerated()) {
							hostOf.putIfAbsent(p.name + "/" + at.file(), pr);
						}
					}
				}
			}
		}
		StringBuilder index = new StringBuilder();
		int n = 0;
		// Originals.
		Map<Program, TypoCorpus.Assembled> originals = new HashMap<>();
		for (Program pr : programs) {
			TypoCorpus.Assembled a = TypoCorpus.assemble(pr.p, pr.p.files, pr.entry,
					pr.file, 3, scratch);
			originals.put(pr, a);
			String id = String.format("o-%02d", ++n);
			emit(index, texts, id, pr, a, null);
		}
		// Every file a program compiles, read once.
		Map<String, Source> sources = new TreeMap<>();
		Map<String, Set<String>> takenByProject = new HashMap<>();
		for (String key : hostOf.keySet()) {
			Program pr = hostOf.get(key);
			String file = key.substring(pr.project.length() + 1);
			sources.put(key, new Source(file, pr.p.files.get(file)));
		}
		for (Program pr : programs) {
			takenByProject.computeIfAbsent(pr.project, x -> new HashSet<>());
		}
		for (Map.Entry<String, Source> e : sources.entrySet()) {
			Program pr = hostOf.get(e.getKey());
			Set<String> taken = takenByProject.get(pr.project);
			for (Tok t : e.getValue().toks) {
				if (t.kind == 'I') {
					taken.add(t.text);
				}
			}
		}
		Set<String> texts_seen = new HashSet<>();
		for (int kind = 1; kind <= 12; ++kind) {
			Random r = new Random(SEED + kind);
			// Candidates by file, by sub-kind.
			Map<String, Map<String, List<Mutation>>> bySub = new TreeMap<>();
			for (Map.Entry<String, Source> e : sources.entrySet()) {
				Program pr = hostOf.get(e.getKey());
				for (Mutation m : candidates(kind, e.getValue(), r,
						takenByProject.get(pr.project))) {
					bySub.computeIfAbsent(m.sub, x -> new TreeMap<>())
							.computeIfAbsent(e.getKey(), x -> new ArrayList<>())
							.add(m);
				}
			}
			List<String> subs = new ArrayList<>(bySub.keySet());
			int made = 0, round = 0;
			Map<String, List<String>> fileOrder = new HashMap<>();
			for (String sub : subs) {
				List<String> files = new ArrayList<>(bySub.get(sub).keySet());
				Collections.shuffle(files, r);
				fileOrder.put(sub, files);
			}
			Map<String, Integer> cursor = new HashMap<>();
			int stall = 0;
			while (made < PER_KIND && stall < 10000) {
				String sub = subs.get(round++ % subs.size());
				List<String> files = fileOrder.get(sub);
				if (files.isEmpty()) {
					++stall;
					continue;
				}
				int c = cursor.merge(sub, 1, Integer::sum) - 1;
				String key = files.get(c % files.size());
				List<Mutation> cands = bySub.get(sub).get(key);
				if (cands.isEmpty()) {
					++stall;
					continue;
				}
				Mutation m = cands.remove(r.nextInt(cands.size()));
				if (!texts_seen.add(key + "\n" + m.text)) {
					continue;
				}
				Program pr = hostOf.get(key);
				Map<String, String> files2 = new TreeMap<>(pr.p.files);
				files2.put(m.file, m.text);
				TypoCorpus.Assembled a = TypoCorpus.assemble(pr.p, files2, pr.entry,
						pr.file, 3, scratch);
				if (a.fragment == null) {
					// The app stops before the driver; the driver still gets
					// the original program with the same edit, line for line.
					a.probeOnly = derive(originals.get(pr),
							pr.p.files.get(m.file), m);
				}
				String id = String.format("K%02d-%02d", kind, ++made);
				emit(index, texts, id, pr, a, m);
				Files.writeString(new File(texts, id + ".src").toPath(), m.text);
			}
			System.out.println("K" + kind + " " + KIND_NAMES[kind] + ": " +
					made + " made; candidates per sub " + subCounts(bySub));
		}
		Files.writeString(new File(out, "index.jsonl").toPath(),
				index.toString());
		TypoCorpus.deleteTree(scratch);
	}

	// The original's assembled text with the lines of the mutated file
	// replaced; a deleted line becomes an empty one, so lines still map.
	static TypoCorpus.Assembled derive(TypoCorpus.Assembled o, String before,
			Mutation m) {
		List<String> was = List.of(before.split("\n", -1));
		List<String> now = new ArrayList<>(List.of(m.text.split("\n", -1)));
		if (now.size() == was.size() - 1) {
			int d = 0;
			while (d < now.size() && now.get(d).equals(was.get(d))) {
				++d;
			}
			now.add(d, "");
		}
		if (now.size() != was.size()) {
			return null;
		}
		List<String> lines = new ArrayList<>(List.of(o.fragment.split("\n", -1)));
		for (int i = 0; i < o.map.size() && i < lines.size(); ++i) {
			String at = o.map.get(i);
			if (at.startsWith(m.file + ":")) {
				int ln = Integer.parseInt(at.substring(m.file.length() + 1));
				lines.set(i, now.get(ln - 1));
			}
		}
		TypoCorpus.Assembled d = new TypoCorpus.Assembled();
		d.fragment = String.join("\n", lines);
		d.vertex = o.vertex;
		d.map = o.map;
		d.version = o.version;
		return d;
	}

	static String subCounts(Map<String, Map<String, List<Mutation>>> bySub) {
		StringBuilder sb = new StringBuilder();
		for (var e : bySub.entrySet()) {
			sb.append("[").append(e.getKey()).append(": ").append(
					e.getValue().size()).append(" files] ");
		}
		return sb.toString();
	}

	static File write(TypoCorpus.Project p, File scratch) throws Exception {
		File dir = Files.createTempDirectory(scratch.toPath(), "e").toFile();
		for (Map.Entry<String, String> e : p.files.entrySet()) {
			File f = new File(dir, e.getKey());
			f.getParentFile().mkdirs();
			Files.writeString(f.toPath(), e.getValue());
		}
		TypoCorpus.writeOther(p, dir);
		return dir;
	}

	static void emit(StringBuilder index, File texts, String id, Program pr,
			TypoCorpus.Assembled a, Mutation m) throws Exception {
		TypoCorpus.Assembled t = a.fragment != null ? a : a.probeOnly;
		if (t != null) {
			Files.writeString(new File(texts, id + ".frag").toPath(),
					t.fragment, StandardCharsets.UTF_8);
			Files.writeString(new File(texts, id + ".vert").toPath(),
					t.vertex, StandardCharsets.UTF_8);
		}
		StringBuilder j = new StringBuilder("{");
		field(j, "id", id).append(',');
		field(j, "project", pr.project).append(',');
		field(j, "entry", pr.entry).append(',');
		field(j, "program", pr.file).append(',');
		j.append("\"version\":").append(t != null ? t.version : a.version)
				.append(',');
		j.append("\"compiled\":").append(a.fragment != null).append(',');
		j.append("\"probeOnly\":").append(a.fragment == null && t != null)
				.append(',');
		j.append("\"appErrors\":").append(array(a.appErrors)).append(',');
		j.append("\"map\":").append(array(t != null ? t.map : a.map));
		if (m != null) {
			j.append(',');
			field(j, "kind", m.kind).append(',');
			field(j, "sub", m.sub).append(',');
			field(j, "file", m.file).append(',');
			j.append("\"line\":").append(m.line).append(',');
			j.append("\"col\":").append(m.col).append(',');
			field(j, "before", m.before).append(',');
			field(j, "after", m.after).append(',');
			j.append("\"targets\":").append(m.targets);
		}
		index.append(j).append("}\n");
	}

	static StringBuilder field(StringBuilder j, String k, String v) {
		return j.append('"').append(k).append("\":").append(quote(v));
	}

	static String array(List<String> list) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); ++i) {
			sb.append(i > 0 ? "," : "").append(quote(list.get(i)));
		}
		return sb.append(']').toString();
	}

	static String quote(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\t' -> sb.append("\\t");
				case '\r' -> sb.append("\\r");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}
}
