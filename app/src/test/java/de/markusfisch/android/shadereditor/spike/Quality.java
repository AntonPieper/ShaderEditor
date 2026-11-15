package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.editor.Analysis;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.spike.Parser.Node;
import de.markusfisch.android.shadereditor.spike.Types.Type;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.Outline;

/**
 * SPIKE, throwaway: what each option answers and where it is wrong.
 * Three parts: how much of the "." receivers and calls of real code
 * each option resolves; what is left after a text is broken the way
 * typing breaks it; and the smallest examples of what cannot be known.
 */
final class Quality {
	private Quality() {
	}

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		coverage();
		broken();
		typing();
		fuzz();
		cases();
	}

	/**
	 * Damage bundled files at random (cut a range, drop in brackets,
	 * quotes, comment marks and keywords) and analyse the result.
	 */
	static void fuzz() {
		System.out.println();
		System.out.println("# Random damage");
		java.util.Random random = new java.util.Random(20261115);
		String[] junk = {"{", "}", "(", ")", "[", "]", ";", ",", ".", "/*",
				"*/", "//", "#", "#if", "#else", "#endif", "\"", "\\\n",
				"struct", "for", "if", "else", "return", "vec3", "=", "?",
				":", "0x", "1e", "\n", "\u00e9", "\ud83d\ude00", "(((((((("};
		List<String> all = new ArrayList<>(Corpus.bundled().values());
		int texts = 0;
		int failures = 0;
		long worst = 0;
		String worstText = "";
		for (int i = 0; i < 20000; ++i) {
			StringBuilder sb = new StringBuilder(all.get(random.nextInt(
					all.size())));
			int edits = 1 + random.nextInt(6);
			for (int k = 0; k < edits && sb.length() > 2; ++k) {
				int at = random.nextInt(sb.length());
				if (random.nextBoolean()) {
					sb.delete(at, Math.min(sb.length(), at + 1 +
							random.nextInt(40)));
				} else {
					sb.insert(at, junk[random.nextInt(junk.length)]);
				}
			}
			String text = sb.toString();
			++texts;
			long begin = System.nanoTime();
			try {
				GlslKnowledge k = GlslKnowledge.of(Text.of(text),
						Knowledge.Scope.EMPTY);
				k.folds();
				k.symbols();
				int at = text.isEmpty() ? 0 : random.nextInt(text.length());
				k.at(at);
				k.callAt(at);
				shallow(text);
			} catch (RuntimeException | StackOverflowError x) {
				++failures;
				if (failures < 3) {
					System.out.println("    FAILED: " + x + " on:\n" + text);
				}
			}
			long took = System.nanoTime() - begin;
			if (i > 2000 && took > worst) {
				worst = took;
				worstText = text.length() + " chars";
			}
		}
		System.out.println(texts + " damaged texts: " + failures +
				" failures; slowest after warm-up " +
				Bench.fmt(worst / 1e6) + " (" + worstText + ")");
	}

	// ---- Coverage ------------------------------------------------------

	private static final class Count {
		int fields;
		int shallow;
		int full;
		int disagree;
		int calls;
		int callsResolved;
		int callsOneSignature;
		int callsByCount;
	}

	static void coverage() {
		System.out.println("# Coverage on real code");
		Count bundled = new Count();
		for (String source : Corpus.bundled().values()) {
			count(source, bundled);
		}
		print("bundled shaders and examples (56 files)", bundled);
		Count generated = new Count();
		count(Corpus.generated(5000, 42), generated);
		print("generated, 5,000 lines", generated);
	}

	private static void print(String what, Count c) {
		System.out.println(what + ": " + c.fields + " member accesses; " +
				"receiver type known to A (declared names only) " +
				c.shallow + " (" + pct(c.shallow, c.fields) + "), to B " +
				c.full + " (" + pct(c.full, c.fields) + "), A and B " +
				"disagree " + c.disagree);
		System.out.println("    " + c.calls + " calls of functions; B " +
				"resolves the overload of " + c.callsResolved + " (" +
				pct(c.callsResolved, c.calls) + "); the callee has one " +
				"signature in " + c.callsOneSignature + " (" +
				pct(c.callsOneSignature, c.calls) + "), the argument " +
				"count alone picks it in " + c.callsByCount + " (" +
				pct(c.callsByCount, c.calls) + ")");
	}

	private static String pct(int a, int b) {
		return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b);
	}

	private static void count(String source, Count c) {
		TokenStream t = TokenStream.of(Text.of(source));
		Node unit = Parser.parse(t);
		Sema sema = Sema.analyse(t, unit);
		Shallow.Outline outline = Shallow.scan(t);
		count(t, sema, outline, unit, c);
	}

	private static void count(TokenStream t, Sema sema,
			Shallow.Outline outline, Node node, Count c) {
		if (node == null) {
			return;
		}
		if (node.kind == Parser.FIELD && node.name >= 0) {
			++c.fields;
			Type full = node.a.type;
			boolean fullKnown = full != null &&
					full.kind != Types.UNKNOWN;
			int dot = t.tokenAt(node.at) - 1;
			Type shallow = outline.receiver(dot);
			if (fullKnown) {
				++c.full;
			}
			if (shallow != null) {
				++c.shallow;
				if (fullKnown && !shallow.toString().equals(
						full.toString())) {
					++c.disagree;
				}
			}
		} else if (node.kind == Parser.CALL && (node.op & 1) == 0) {
			Sema.Symbol symbol = node.symbol;
			if (symbol != null && symbol.kind != Sema.STRUCT &&
					symbol.kind != Sema.MACRO) {
				++c.calls;
				if (node.type != null && node.type.kind != Types.UNKNOWN) {
					++c.callsResolved;
				}
				int signatures = 0;
				int sameCount = 0;
				if (symbol.kind == Sema.FUNCTION) {
					for (Sema.Symbol f = sema.lookup(node.name); f != null;
							f = f.next) {
						++signatures;
						sameCount += f.parameters.length == node.list.length
								? 1 : 0;
					}
				} else {
					Builtins.Function f = Builtins.function(node.name);
					if (f != null) {
						signatures = f.signatures.length;
						for (Builtins.Signature s : f.signatures) {
							sameCount += s.params.length == node.list.length
									? 1 : 0;
						}
					}
				}
				if (signatures == 1) {
					++c.callsOneSignature;
				}
				if (sameCount == 1) {
					++c.callsByCount;
				}
			}
		}
		count(t, sema, outline, node.a, c);
		count(t, sema, outline, node.b, c);
		count(t, sema, outline, node.c, c);
		for (Node child : node.list) {
			count(t, sema, outline, child, c);
		}
	}

	// ---- Broken code ---------------------------------------------------

	private static Set<String> production(String source) {
		Set<String> names = new HashSet<>();
		Analysis a = Analysis.of(GlslSyntax.INSTANCE, Text.of(source), 1,
				null);
		for (Outline.Declaration d : a.outline().declarations()) {
			if (d.kind() == Outline.Kind.FUNCTION) {
				names.add(d.name());
			}
		}
		return names;
	}

	private static Set<String> shallow(String source) {
		Set<String> names = new HashSet<>();
		TokenStream t = TokenStream.of(Text.of(source));
		for (Shallow.Decl d : Shallow.scan(t).declarations) {
			if (d.kind == Shallow.FUNCTION || d.kind == Shallow.PROTOTYPE) {
				names.add(t.names.name(d.name));
			}
		}
		return names;
	}

	private static final class Full {
		final Set<String> names = new HashSet<>();
		int typed;
		int expressions;
		int problems;
	}

	private static Full full(String source) {
		Full out = new Full();
		TokenStream t = TokenStream.of(Text.of(source));
		Parser parser = new Parser(t);
		Sema sema = Sema.analyse(t, parser.unit());
		for (Sema.Symbol s : sema.globals) {
			if (s.kind == Sema.FUNCTION) {
				out.names.add(t.names.name(s.name));
			}
		}
		out.typed = sema.typed;
		out.expressions = sema.expressions;
		out.problems = parser.problemCount;
		return out;
	}

	private static int common(Set<String> found, Set<String> all) {
		int n = 0;
		for (String s : all) {
			if (found.contains(s)) {
				++n;
			}
		}
		return n;
	}

	static void broken() {
		System.out.println();
		System.out.println("# Broken code: one break in the middle of a " +
				"text; functions still known, of those the whole text " +
				"declares");
		List<String> inputs = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		inputs.add(Corpus.generated(1000, 42));
		labels.add("generated 1,000 lines");
		inputs.add(Corpus.generated(5000, 7));
		labels.add("generated 5,000 lines");
		for (int k = 0; k < inputs.size(); ++k) {
			String source = inputs.get(k);
			Set<String> all = full(source).names;
			Full intact = full(source);
			System.out.println(labels.get(k) + ": " + all.size() +
					" functions, " + intact.typed + " typed expressions");
			int lines = Corpus.countLines(source);
			for (Corpus.Break kind : Corpus.Break.values()) {
				String text = Corpus.broken(source, lines / 2, kind);
				if (text == null) {
					continue;
				}
				Full b = full(text);
				System.out.println(String.format(
						"    %-20s today's outline %4d  A %4d  B %4d  " +
								"(B: %d problems, typed %d of %d)",
						kind, common(production(text), all),
						common(shallow(text), all), common(b.names, all),
						b.problems, b.typed, b.expressions));
			}
		}
		// The same on every bundled file, summed.
		int[][] sums = new int[Corpus.Break.values().length][4];
		int files = 0;
		for (String source : Corpus.bundled().values()) {
			Set<String> all = full(source).names;
			if (all.isEmpty()) {
				continue;
			}
			++files;
			int lines = Corpus.countLines(source);
			for (Corpus.Break kind : Corpus.Break.values()) {
				String text = Corpus.broken(source, lines / 2, kind);
				if (text == null) {
					text = Corpus.broken(source, 0, kind);
				}
				if (text == null) {
					continue;
				}
				int[] sum = sums[kind.ordinal()];
				sum[0] += all.size();
				sum[1] += common(production(text), all);
				sum[2] += common(shallow(text), all);
				sum[3] += common(full(text).names, all);
			}
		}
		System.out.println("bundled files (" + files + " with functions), " +
				"each broken once near its middle, sums:");
		for (Corpus.Break kind : Corpus.Break.values()) {
			int[] sum = sums[kind.ordinal()];
			System.out.println(String.format(
					"    %-20s of %d functions: today's outline %d  A %d  " +
							"B %d", kind, sum[0], sum[1], sum[2], sum[3]));
		}
	}

	/**
	 * Type a statement of every bundled file character by character
	 * and analyse after each character, as the editor would.
	 */
	static void typing() {
		System.out.println();
		System.out.println("# Typing: one statement of each bundled file " +
				"typed character by character, analysed after each");
		int texts = 0;
		int failures = 0;
		int lostToday = 0;
		int lostShallow = 0;
		int lostFull = 0;
		int worstTyped = 100;
		String worst = "";
		long typedSum = 0;
		for (Map.Entry<String, String> e : Corpus.bundled().entrySet()) {
			String source = e.getValue();
			String[] lines = source.split("\n", -1);
			int at = -1;
			for (int i = lines.length / 2; i < lines.length; ++i) {
				if (lines[i].startsWith("\t") && lines[i].endsWith(";")) {
					at = i;
					break;
				}
			}
			if (at < 0) {
				continue;
			}
			Full intact = full(source);
			String whole = lines[at];
			int indent = whole.length() - whole.stripLeading().length();
			for (int k = indent; k < whole.length(); ++k) {
				lines[at] = whole.substring(0, k);
				String text = String.join("\n", lines);
				++texts;
				try {
					Full b = full(text);
					Set<String> a = shallow(text);
					Set<String> today = production(text);
					lostFull += intact.names.size() -
							common(b.names, intact.names);
					lostShallow += intact.names.size() -
							common(a, intact.names);
					lostToday += intact.names.size() -
							common(today, intact.names);
					int percent = intact.typed == 0 ? 100
							: 100 * b.typed / intact.typed;
					if (percent < worstTyped) {
						worst = e.getKey() + ": \"" + lines[at].strip() +
								"\" of \"" + whole.strip() + "\"";
					}
					worstTyped = Math.min(worstTyped, percent);
					typedSum += percent;
				} catch (RuntimeException | StackOverflowError x) {
					++failures;
					System.out.println("    FAILED " + e.getKey() + " at " +
							k + ": " + x);
				}
			}
			lines[at] = whole;
		}
		System.out.println(texts + " half-typed texts: " + failures +
				" failures; functions lost in total: today's outline " +
				lostToday + ", A " + lostShallow + ", B " + lostFull +
				"; B types on average " + typedSum / Math.max(1, texts) +
				"% of the expressions the whole text has, at worst " +
				worstTyped + "% (" + worst + ")");
	}

	// ---- The smallest examples ----------------------------------------

	static void cases() {
		System.out.println();
		System.out.println("# Smallest examples: the type before the " +
				"\".\" at the mark, as A and as B see it");
		String head = "struct Ray { vec3 origin; vec3 dir; float t; };\n" +
				"struct Hit { Ray ray; vec2 uv[3]; };\n" +
				"uniform sampler2D tex;\n" +
				"float sd(vec2 p) { return length(p); }\n" +
				"vec3 sd(vec3 p) { return p; }\n";
		String[][] cases = {
				{"a declared local", "void f(){ vec3 p = vec3(0.); p.| }"},
				{"a parameter", "void f(vec4 c){ c.| }"},
				{"a swizzle of a swizzle", "void f(){ vec4 c; c.xyz.zy.| }"},
				{"a swizzle that cannot be", "void f(){ vec3 p; p.xw.| }"},
				{"a struct field", "void f(){ Ray r; r.dir.| }"},
				{"a struct in a struct, an array", "void f(){ Hit h; " +
						"h.uv[1].| }"},
				{"a field of a nested struct", "void f(){ Hit h; " +
						"h.ray.origin.| }"},
				{"an inner name hides an outer", "void f(){ float x; " +
						"{ vec3 x; x.| } }"},
				{"after the inner block", "void f(){ float x; { vec3 x; } " +
						"x.| }"},
				{"a call of a built-in, generic", "void f(){ vec3 a; " +
						"normalize(a).| }"},
				{"an overloaded built-in", "void f(){ vec3 a; vec3 b; " +
						"mix(a, b, 0.5).| }"},
				{"own overloads", "void f(){ vec3 a; sd(a).| }"},
				{"a constructor", "void f(){ vec4(1.0).| }"},
				{"a struct constructor", "void f(){ Ray(vec3(0.), " +
						"vec3(1.), 0.).| }"},
				{"an operator", "void f(){ vec2 a; (a * 2.0 + 1.0).| }"},
				{"a matrix times a vector", "void f(){ mat3 m; vec3 v; " +
						"(m * v).| }"},
				{"a texture read", "void f(){ vec2 uv; texture(tex, uv).| }"},
				{"ES 1.00's texture read", "void f(){ vec2 uv; " +
						"texture2D(tex, uv).| }"},
				{"an index into a matrix", "void f(){ mat4 m; m[2].| }"},
				{"an array's length", "void f(){ float w[4]; w.| }"},
				{"a ternary", "void f(){ vec3 a; vec3 b; (a.x > 0. ? a : b)" +
						".| }"},
				{"a macro that is a value", "#define UP vec3(0., 1., 0.)\n" +
						"void f(){ UP.| }"},
				{"a macro with arguments", "#define SAT(x) clamp(x, 0., 1.)" +
						"\nvoid f(){ vec3 c; SAT(c).| }"},
				{"a macro that is a type", "#define V vec3\n" +
						"void f(){ V p; p.| }"},
				{"a macro that declares", "#define DECL(n) vec3 n = " +
						"vec3(0.);\nvoid f(){ DECL(p) p.| }"},
				{"a macro that is a loop head", "#define REP(n) for (int " +
						"i = 0; i < n; i++)\nvoid f(){ vec3 p; REP(4) { p.| " +
						"} }"},
				{"a name from an include", "#include \"lib/noise.glsl\"\n" +
						"void f(){ vec2 p; noiseGradient(p).| }"},
				{"a name declared in both branches", "#ifdef WIDE\nvec4 c;" +
						"\n#else\nvec3 c;\n#endif\nvoid f(){ c.| }"},
				{"a head in both branches", "#ifdef A\nfloat g(vec3 p) {\n" +
						"#else\nfloat g(vec3 p, float t) {\n#endif\n" +
						"\treturn p.x;\n}\nvoid f(){ vec3 q; q.| }"},
				{"half-typed above", "void f(){ vec3 p; float d = mix(p.x, " +
						"\n\tp.| }"},
				{"a missing brace above", "void g(){ if (true) { \n" +
						"}\nvoid f(){ vec3 p; p.| }"},
		};
		for (String[] c : cases) {
			String source = (c[1].startsWith("#") ? "" : head) + c[1];
			int mark = source.indexOf('|');
			source = source.substring(0, mark) + source.substring(mark + 1);
			int dot = mark - 1;
			TokenStream t = TokenStream.of(Text.of(source));
			Parser parser = new Parser(t);
			Node unit = parser.unit();
			Sema.analyse(t, unit);
			Shallow.Outline outline = Shallow.scan(t);
			int dotToken = t.tokenAt(dot);
			Type a = outline.receiver(dotToken);
			Node field = fieldAt(unit, dot, null);
			Type b = field != null ? field.a.type : null;
			System.out.println(String.format("    %-32s A: %-8s B: %-8s %s",
					c[0], a == null ? "?" : a.toString(),
					b == null || b.kind == Types.UNKNOWN ? "?"
							: b.toString(),
					parser.problemCount > 0 ? "(" + parser.problemCount +
							" parse problems)" : ""));
		}
	}

	// The FIELD node whose "." stands at offset dot.
	private static Node fieldAt(Node node, int dot, Node best) {
		if (node == null) {
			return best;
		}
		if (node.kind == Parser.FIELD && node.a.end <= dot &&
				(node.name < 0 ? node.end > dot : node.at > dot) &&
				(best == null || node.a.end > best.a.end)) {
			best = node;
		}
		best = fieldAt(node.a, dot, best);
		best = fieldAt(node.b, dot, best);
		best = fieldAt(node.c, dot, best);
		for (Node child : node.list) {
			best = fieldAt(child, dot, best);
		}
		return best;
	}
}
