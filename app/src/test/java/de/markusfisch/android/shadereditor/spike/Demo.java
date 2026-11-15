package de.markusfisch.android.shadereditor.spike;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import de.markusfisch.android.shadereditor.editor.Text;

/**
 * SPIKE, throwaway: the proposed seam answering on a bundled example,
 * across its files, and what each question costs.
 */
final class Demo {
	private Demo() {
	}

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		answers();
		json();
		costs();
		project();
		table();
	}

	private static String show(Knowledge.Symbol s) {
		if (s == null) {
			return "nothing";
		}
		return s.kind() + " " + s.name() + " | " +
				s.detail().replace("\n", " ; ") + " | doc: " +
				s.documentation() + (s.file() != null ? " | in " + s.file()
				: "") + (s.start() >= 0 ? " | at " + s.start() : "");
	}

	static void answers() {
		System.out.println("# The seam on examples/Ink/ink.frag");
		Map<String, String> sources = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : Corpus.bundled().entrySet()) {
			if (e.getKey().startsWith("examples/")) {
				sources.put(e.getKey(), e.getValue());
			}
		}
		// Alone, then with the other files.
		int alone = 0;
		for (String source : sources.values()) {
			alone += GlslKnowledge.of(Text.of(source),
					Knowledge.Scope.EMPTY).sema.unresolved;
		}
		Project project = new Project(sources);
		project.index();
		int together = 0;
		for (GlslKnowledge k : project.knowledge.values()) {
			together += k.sema.unresolved;
		}
		System.out.println("names no declaration explains, over " +
				sources.size() + " example files: " + alone +
				" when each file is read alone, " + together +
				" when a file is read after its includes");

		String path = "examples/Ink/ink.frag";
		String source = sources.get(path);
		GlslKnowledge k = project.knowledge.get(path);
		String[] asks = {"valueNoise(p +", "flow(2.0", "resolution.x, r",
				"texture2D(ink", "lib/noise.glsl", "gravity.xy", "down.y",
				"frameRate / 15"};
		for (String ask : asks) {
			int at = source.indexOf(ask);
			System.out.println("at \"" + ask + "\": " +
					show(project.definition(path, at)));
		}
		int call = source.indexOf("max(1.0, floor(") + "max(1.0, ".length();
		Knowledge.Call c = k.callAt(call);
		System.out.println("call at \"max(1.0, |floor\": argument " +
				c.argument() + ", " + c.signatures().size() +
				" signatures, fits: " + c.signatures().get(c.active())
				.detail());
		call = source.indexOf("floor(frameRate") + "floor(".length();
		c = k.callAt(call);
		System.out.println("call at \"floor(|frameRate\": argument " +
				c.argument() + ", fits: " + c.signatures().get(c.active())
				.detail() + ", " + c.signatures().get(c.active())
				.documentation());
		// A line typed after the knowledge was made.
		int inMain = source.indexOf("float between");
		for (String line : new String[]{"\tvec2 w = gravity.",
				"\tfloat q = texture2D(ink, p).", "\tw = flow(p * 2.0).",
				"\tw = (down + v * 0.5).x", "\tw = pointers[2]."}) {
			System.out.println("members after \"" + line.strip() + "\": " +
					k.typeBefore(line, line.length(), inMain) + " -> " +
					k.membersAt(line, line.length(), inMain));
		}
		Knowledge.Symbol down = k.at(source.indexOf("down.y"));
		System.out.println("occurrences of down: " +
				k.occurrences(down).length / 2);
		Knowledge.Symbol noise = project.definition(path,
				source.indexOf("valueNoise(p +"));
		Map<String, int[]> usages = project.usages(noise, noise.file());
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, int[]> e : usages.entrySet()) {
			sb.append(e.getKey()).append(" x").append(
					e.getValue().length / 2).append("  ");
		}
		System.out.println("usages of valueNoise across the files: " + sb);
		System.out.println("folds: " + k.folds().length / 2 +
				"; outline: " + k.symbols().size() + " symbols, the first: " +
				show(k.symbols().get(0)));
	}

	static void json() {
		System.out.println();
		System.out.println("# The seam on a manifest");
		String source = "{\n\t\"projectVersion\": 1,\n\t\"entry\": " +
				"\"main.frag\"\n}\n";
		JsonKnowledge k = JsonKnowledge.of(Text.of(source), true);
		System.out.println("folds " + k.folds().length / 2 + ", outline " +
				k.symbols().size() + " keys; at entry: " +
				show(k.at(source.indexOf("entry"))) + "; at its value: " +
				show(k.at(source.indexOf("main.frag"))) + "; a call: " +
				k.callAt(10));
		System.out.println("plain text: " + Knowledge.NONE.symbols().size() +
				" symbols, at: " + Knowledge.NONE.at(3) + ", folds: " +
				Knowledge.NONE.folds().length);
	}

	static void costs() {
		System.out.println();
		System.out.println("# What a question costs once the knowledge " +
				"is there");
		for (int size : new int[]{5000, 50000}) {
			String source = Corpus.generated(size, 42);
			Text text = Text.of(source);
			GlslKnowledge k = GlslKnowledge.of(text, Knowledge.Scope.EMPTY);
			// Offsets of names all over the text.
			Random random = new Random(1);
			int[] offsets = new int[512];
			for (int i = 0; i < offsets.length; ) {
				int token = random.nextInt(k.t.size);
				if (k.t.kind(token) == TokenStream.IDENT) {
					offsets[i++] = k.t.starts[token];
				}
			}
			int[] next = {0};
			Bench.Stats at = Bench.timeEach(() -> Bench.sink = k.at(
					offsets[next[0]++ & 511]), 512, 500, 40);
			Bench.Stats call = Bench.timeEach(() -> Bench.sink = k.callAt(
					offsets[next[0]++ & 511]), 512, 500, 40);
			int middle = k.t.starts[k.t.size / 2];
			String line = "\tcol = mix(col, r.dir, a).";
			String simple = "\tb = q.";
			Bench.Stats members = Bench.timeEach(() -> Bench.sink =
					k.membersAt(line, line.length(), middle), 200, 500, 40);
			Bench.Stats member = Bench.timeEach(() -> Bench.sink =
					k.membersAt(simple, simple.length(), middle), 200, 500,
					40);
			Knowledge.Symbol local = null;
			for (int i = 0; local == null; ++i) {
				Knowledge.Symbol s = k.at(offsets[i]);
				if (s != null && s.kind() == Knowledge.Kind.VARIABLE &&
						s.start() >= 0) {
					local = s;
				}
			}
			Knowledge.Symbol l = local;
			Bench.Stats occurrences = Bench.time(() -> k.occurrences(l),
					500, 30);
			Bench.Stats symbols = Bench.time(() -> GlslKnowledge.of(text,
					Knowledge.Scope.EMPTY).symbols(), 1000, 20);
			Bench.Stats whole = Bench.time(() -> GlslKnowledge.of(text,
					Knowledge.Scope.EMPTY), 1000, 20);
			Bench.Stats folds = Bench.time(() -> GlslKnowledge.of(text,
					Knowledge.Scope.EMPTY).folds(), 1000, 20);
			System.out.println(size + " lines: at(offset) " + at.us() +
					"; callAt " + call.us() + "; members after \".\" on a " +
					"line just typed: a name " + member.us() +
					", a call with arguments " + members.us() +
					"; occurrences of a local " + occurrences.us() +
					"; outline with comments, first time +" +
					Bench.fmt(Math.max(0, symbols.medianMs() -
							whole.medianMs())) + "; folds, first time +" +
					Bench.fmt(Math.max(0, folds.medianMs() -
							whole.medianMs())) + "; " + k.folds().length / 2 +
					" folds, " + k.symbols().size() + " symbols");
		}
	}

	static void project() {
		System.out.println();
		System.out.println("# A project of 50 files");
		Map<String, String> sources = new LinkedHashMap<>();
		int lines = 0;
		String[] first = new String[10];
		for (int i = 0; i < 50; ++i) {
			// Ten small libraries, forty files that include two each.
			String source = Corpus.generated(i < 10 ? 150 : 300, 100 + i);
			if (i < 10) {
				// A library has no main and no version line.
				source = source.substring(source.indexOf("#define PI"),
						source.indexOf("void main()"))
						.replace("field", "lib" + i + "_field")
						.replace("shade", "lib" + i + "_shade")
						.replace("Ray", "Ray" + i).replace("Hit", "Hit" + i)
						.replace("LIGHT", "LIGHT" + i)
						.replace("EPS", "EPS" + i)
						.replace("WEIGHTS", "WEIGHTS" + i);
				source = "uniform sampler2D noiseTex;\n" + source;
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
						"(lib\\d+_\\w+)\\(vec3 p").matcher(source);
				m.find();
				first[i] = m.group(1);
			} else {
				int a = i % 10;
				int b = (i * 7) % 10;
				source = source.replace("out vec4 fragColor;",
						"out vec4 fragColor;\n#include \"lib/l" + a +
								".glsl\"\n" + (a == b ? "" : "#include " +
								"\"lib/l" + b + ".glsl\"\n"))
						.replace("fragColor = vec4(", "col += vec3(" +
								first[a] + "(col, time));\n\tfragColor = " +
								"vec4(");
			}
			lines += Corpus.countLines(source);
			sources.put(i < 10 ? "lib/l" + i + ".glsl" : "pass" + i +
					".frag", source);
		}
		Bench.Stats index = Bench.time(() -> {
			Project p = new Project(sources);
			p.index();
			return p;
		}, 2000, 30);
		Project project = new Project(sources);
		project.index();
		int unresolved = 0;
		int problems = 0;
		for (GlslKnowledge k : project.knowledge.values()) {
			unresolved += k.sema.unresolved;
			problems += k.problems;
		}
		long kept = Bench.retained(() -> {
			Project p = new Project(sources);
			p.index();
			return p.knowledge;
		}, 3);
		// What stays if only what other files need is kept per file.
		long lean = Bench.retained(() -> {
			Project p = new Project(sources);
			p.index();
			Object[] out = new Object[p.knowledge.size()];
			int i = 0;
			for (GlslKnowledge k : p.knowledge.values()) {
				out[i++] = k.symbols();
			}
			return out;
		}, 3);
		String pass = "pass10.frag";
		String source = sources.get(pass);
		int at = source.indexOf(first[0] + "(col, time)");
		Knowledge.Symbol s = project.definition(pass, at);
		Bench.Stats definition = Bench.timeEach(() -> Bench.sink =
				project.definition(pass, at), 100, 300, 30);
		Bench.Stats usages = Bench.time(() -> project.usages(s, s.file()),
				500, 30);
		Map<String, int[]> found = project.usages(s, s.file());
		int places = 0;
		for (int[] p : found.values()) {
			places += p.length / 2;
		}
		System.out.println("50 files, " + lines + " lines: read all " +
				index.ms() + ", " + problems + " parse problems, " +
				unresolved + " names unexplained; keeps " + kept / 1024 +
				" KB with every tree, " + lean / 1024 + " KB with the " +
				"declarations only");
		System.out.println("definition across files: " + show(s) + ", " +
				definition.us() + "; usages across all 50 files: " + places +
				" places in " + found.size() + " files, " + usages.us());
	}

	static void table() {
		System.out.println();
		System.out.println("# The table of built-ins");
		Bench.Stats parse = Bench.time(() -> {
			Object[] out = new Object[Builtins.TABLE.length];
			for (int i = 0; i < out.length; ++i) {
				out[i] = new Builtins.Function(Builtins.TABLE[i]);
			}
			return out;
		}, 500, 50);
		long kept = Bench.retained(() -> {
			Object[] out = new Object[Builtins.TABLE.length];
			for (int i = 0; i < out.length; ++i) {
				out[i] = new Builtins.Function(Builtins.TABLE[i]);
			}
			return out;
		}, 8);
		Names names = new Names("");
		int id = names.find("smoothstep");
		Bench.Stats lookup = Bench.timeEach(() -> Bench.sink =
				Builtins.function(id), 1000, 300, 30);
		System.out.println(Builtins.TABLE.length + " functions, " +
				Builtins.signatureCount() + " signatures, " +
				Builtins.tableChars() + " characters; parsing every row: " +
				parse.us() + ", " + Bench.fmtUs(parse.medianMs() /
				Builtins.TABLE.length) + " a function, done the first " +
				"time a function is asked for; parsed they keep " +
				kept / 1024 + " KB; a lookup " + lookup.us());
	}
}
