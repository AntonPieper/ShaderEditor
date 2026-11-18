package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.shadertoy.Fixtures;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyProject;

/**
 * The texts the engine compiles for the examples, the bundled shaders,
 * the ShaderToy fixtures, review probes and generated texts, with what
 * {@link BuiltLines} says of their lines: the cases that Mesa's compiler
 * judges in {@link DirectiveOracle}, and the recorded verdicts of which
 * {@link DirectiveRunOracleTest} checks.
 */
final class DirectiveCases {
	static final int GENERATED = 600;
	private static final Pattern NAME = Pattern.compile(
			"[A-Za-z_][A-Za-z0-9_]*");
	private static final Pattern DEFINE = Pattern.compile(
			"#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)");

	/** A use of a macro and what it expands to. */
	record Expansion(int line, int column, String name, String text) {
	}

	/**
	 * One compiled program: its text, the 0-based lines BuiltLines says
	 * are off, the states of all lines, and the expansions it shows.
	 */
	record Case(String name, int version, String text, List<Integer> off,
			List<DirectiveRun.State> states, List<Expansion> expansions) {
	}

	private DirectiveCases() {
	}

	static List<Case> all() {
		List<Case> cases = new ArrayList<>();
		DirectoryProjectFiles examples = new DirectoryProjectFiles(
				ExamplesTest.folder());
		for (ProjectPath shader : ExamplesTest.shaders()) {
			add(cases, "examples/" + shader.toString().replace(' ', '_'),
					examples, shader);
		}
		File raw = new File(ExamplesTest.folder(),
				"../../../main/res/raw");
		for (String name : sorted(raw.list())) {
			if (name.endsWith(".glsl")) {
				add(cases, "raw/" + name, read(new File(raw, name)));
			}
		}
		for (String name : List.of("media.json", "pace.json", "rings.json",
				"ripples.json", "trail.json")) {
			ShaderToyProject.Converted converted =
					ShaderToyProject.ofJson(Fixtures.text(name));
			MemoryProjectFiles files = new MemoryProjectFiles();
			for (Map.Entry<String, String> file :
					converted.files().entrySet()) {
				files.put(file.getKey(), file.getValue());
			}
			add(cases, "shadertoy/" + name, files, ProjectPath.parse(
					ShaderToyProject.ENTRY).value());
		}
		File macros = new File(probes().getParentFile(), "macros");
		for (String name : sorted(macros.list())) {
			if (name.endsWith(".frag")) {
				add(cases, "macros/" + name, read(new File(macros, name)));
			}
		}
		File probes = probes();
		for (String name : sorted(probes.list())) {
			add(cases, "probes/" + name, read(new File(probes, name)));
		}
		for (int seed = 0; seed < GENERATED; ++seed) {
			add(cases, "generated/" + seed, generate(seed));
		}
		for (int seed = 0; seed < GENERATED / 2; ++seed) {
			add(cases, "uses/" + seed, uses(seed));
		}
		return cases;
	}

	private static void add(List<Case> cases, String name, String text) {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("main.frag", text);
		add(cases, name, files, ProjectPath.parse("main.frag").value());
	}

	private static void add(List<Case> cases, String name,
			ProjectFiles files, ProjectPath entry) {
		Preparation preparation = Preparation.prepare(files, entry,
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
		RenderPlan plan = preparation.plan();
		if (plan == null) {
			return;
		}
		BuiltLines built = BuiltLines.of(plan);
		for (int p = 0; p < plan.programs().size(); ++p) {
			RenderPlan.Program program = plan.programs().get(p);
			CompiledSource compiled = CompiledSource.of(program.lines(),
					program.head(), program.sourceMap());
			String text = compiled.text();
			DirectiveRun run = DirectiveRun.of(text, compiled.version());
			String[] lines = text.split("\n", -1);
			List<Integer> off = new ArrayList<>();
			List<DirectiveRun.State> states = new ArrayList<>();
			List<Expansion> expansions = new ArrayList<>();
			List<String> macros = new ArrayList<>();
			Matcher define = DEFINE.matcher(text);
			while (define.find()) {
				macros.add(define.group(1));
			}
			PreprocessorScan scan = PreprocessorScan.scan(
					Arrays.asList(lines), null);
			for (int n = 0; n < lines.length; ++n) {
				states.add(run.state(n));
				SourceLocation at = compiled.sourceMap().locate(n + 1);
				if (at == null || at.isGenerated() ||
						at.includedFrom() != null) {
					continue;
				}
				if (built.off(at.file()).contains(at.line() - 1)) {
					off.add(n);
				}
				Matcher word = NAME.matcher(scan.code().get(n));
				while (word.find()) {
					if (!macros.contains(word.group())) {
						continue;
					}
					String here = built.expansion(at.file(), at.line() - 1,
							word.start(), word.group());
					if (here != null) {
						expansions.add(new Expansion(n, word.start(),
								word.group(), here));
					}
				}
			}
			cases.add(new Case(name + "#" + p, compiled.version(), text,
					off, states, expansions));
		}
	}

	// The first 0-based lines at which a marker line may be put in front
	// of the line: after #version, not behind a backslash, not in a block
	// comment. A marker there is in the arm of the line.
	static boolean markable(String[] lines, PreprocessorScan scan, int n,
			int version) {
		return n > version && n < lines.length && (n == 0 ||
				!lines[n - 1].stripTrailing().endsWith("\\") &&
						!scan.openComments().get(n - 1));
	}

	static File probes() {
		for (String base : new String[]{".", "app"}) {
			File dir = new File(base, "src/test/resources/directives/probes");
			if (dir.isDirectory()) {
				return dir;
			}
		}
		throw new AssertionError("No probes");
	}

	private static List<String> sorted(String[] names) {
		List<String> list = new ArrayList<>(Arrays.asList(names));
		java.util.Collections.sort(list);
		return list;
	}

	private static String read(File file) {
		try {
			return SourceText.decode(Files.readAllBytes(file.toPath()));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// A text of conditionals and macros, the same for the same seed.
	static String generate(long seed) {
		Generator g = new Generator(new Random(seed));
		return g.text();
	}

	// A text whose macros all expand to code that compiles, used across
	// lines and inside each other, under switches it sets itself.
	static String uses(long seed) {
		Random random = new Random(seed);
		StringBuilder sb = new StringBuilder();
		boolean es3 = random.nextBoolean();
		sb.append(es3 ? "#version 300 es\nprecision highp float;\n" +
				"out vec4 fragColor;\n" : "precision mediump float;\n");
		sb.append("#define K0 ").append(random.nextInt(9)).append('\n');
		sb.append("#define K1 (K0 + ").append(random.nextInt(9))
				.append(")\n");
		sb.append("#define TWICE(x) ((x) * 2)\n");
		sb.append("#define ADD(a, b) ((a) + (b))\n");
		sb.append("#define PICK(a, b) ADD(TWICE(a), b)\n");
		sb.append("#define ID ADD\n");
		sb.append("#define NONE() 7\n");
		sb.append(random.nextBoolean() ? "#define AA 2\n" : "// #define AA 2\n");
		sb.append("#if defined(AA) && AA > 1\n#define MODE (K1 - 1)\n" +
				"#elif ").append(random.nextInt(3)).append(" / 2\n" +
				"#define MODE K0\n#else\n#define MODE 9\n#endif\n");
		sb.append("#define WIDE(a, b) ADD(a, \\\n\tb)\n");
		sb.append("void main() {\n\tint v = 0;\n");
		String[] args = {"K0", "K1", "MODE", "1", "(2 + 3)", "TWICE(K0)",
				"ADD(1, K1)", "NONE()", "-4"};
		for (int i = 4 + random.nextInt(8); i-- > 0; ) {
			String a = args[random.nextInt(args.length)];
			String b = args[random.nextInt(args.length)];
			sb.append(switch (random.nextInt(8)) {
				case 0 -> "\tv += TWICE(" + a + ");\n";
				case 1 -> "\tv += ADD(" + a + ",\n\t\t" + b + ");\n";
				case 2 -> "\tv += PICK(" + a + ", " + b + ");\n";
				case 3 -> "\tv += ID(" + a + ", " + b + ");\n";
				case 4 -> "\tv += " + a + " * MODE;\n";
				case 5 -> "\tv += WIDE(" + a + ", " + b + ");\n";
				case 6 -> "#if AA\n\tv += K1;\n#endif\n";
				default -> "\tv += NONE() + " + a + ";\n";
			});
		}
		sb.append(es3 ? "\tfragColor = vec4(float(v));\n}\n"
				: "\tgl_FragColor = vec4(float(v));\n}\n");
		return sb.toString();
	}

	private static final class Generator {
		private static final String[] NAMES = {"A", "B", "C", "AA", "LOW",
				"SOFT", "Q"};
		private static final String[] DRIVER = {"GL_ES", "__VERSION__",
				"GL_FRAGMENT_PRECISION_HIGH", "GL_EXT_shader_texture_lod",
				"SHADER_EDITOR", "__LINE__"};
		private static final String[] OPS = {"+", "-", "*", "/", "%", "<<",
				">>", "<", ">", "<=", ">=", "==", "!=", "&", "|", "^", "&&",
				"||"};
		private final Random random;
		private final StringBuilder sb = new StringBuilder();
		private final boolean es3;
		private final boolean crlf;
		private int uses;

		Generator(Random random) {
			this.random = random;
			es3 = random.nextInt(10) < 7;
			crlf = random.nextInt(10) == 0;
		}

		String text() {
			if (es3) {
				line("#version 300 es");
				line("precision highp float;");
				line("out vec4 fragColor;");
			} else if (random.nextBoolean()) {
				line("#ifdef GL_FRAGMENT_PRECISION_HIGH");
				line("precision highp float;");
				line("#else");
				line("precision mediump float;");
				line("#endif");
			} else {
				line("precision mediump float;");
			}
			line("#define F(x) ((x) * 2)");
			if (random.nextBoolean()) {
				line("#define G(a, b) F(a) + (b)");
			}
			line("#define H() 3");
			items(0, 4 + random.nextInt(10), false);
			line("void main() {");
			line("\tint v = 0;");
			items(0, 3 + random.nextInt(6), true);
			line(es3 ? "\tfragColor = vec4(float(v));"
					: "\tgl_FragColor = vec4(float(v));");
			line("}");
			String text = sb.toString();
			return crlf ? text.replace("\n", "\r\n") : text;
		}

		private void items(int depth, int count, boolean code) {
			for (int i = 0; i < count; ++i) {
				item(depth, code);
			}
		}

		private void item(int depth, boolean code) {
			int kind = random.nextInt(code ? 12 : 10);
			String name = pick(NAMES);
			switch (kind) {
				case 0, 1 -> {
					if (random.nextBoolean()) {
						line("#undef " + name);
					}
					line("#define " + name + " " + value());
				}
				case 2 -> line("#undef " + name);
				case 3 -> line(random.nextBoolean()
						? "// #define " + name + " 1"
						: "/* #define " + name + " 2 */");
				case 4, 5 -> group(depth, code);
				case 6 -> line(pick(new String[]{"#line 40",
						"#pragma optimize(on)", "/*\n#if 1\n*/",
						"#define " + name + " 1 \\\n\t+ 2",
						"#define " + name + "(x) (x \\\n\t+ 1)",
						"#define " + name + " // note \\"}));
				case 7 -> line("#define " + name + "(x, y) ((x) " +
						pick(OPS) + " (y))");
				case 8 -> line("#define " + name + " " + pick(NAMES));
				case 9 -> {
					if (depth > 0) {
						line("#error never");
					}
				}
				default -> line("\tv += " + use() + ";");
			}
		}

		private void group(int depth, boolean code) {
			if (depth > 2) {
				return;
			}
			int kind = random.nextInt(10);
			line(kind < 2 ? "#if " + random.nextInt(2)
					: kind < 4 ? "#ifdef " + pick(NAMES)
					: kind < 5 ? "#ifndef " + pick(NAMES)
					: kind < 6 ? "#if " + condition(2) + " \\\n\t" +
					pick(new String[]{"&& 1", "|| 0", "+ 1"})
					: "#if " + condition(3));
			items(depth + 1, 1 + random.nextInt(3), code);
			for (int i = random.nextInt(3); i-- > 0; ) {
				line("#elif " + condition(3));
				items(depth + 1, 1 + random.nextInt(3), code);
			}
			if (random.nextBoolean()) {
				line("#else");
				items(depth + 1, 1 + random.nextInt(3), code);
			}
			line("#endif");
		}

		private String condition(int depth) {
			String name;
			int kind = random.nextInt(depth > 0 ? 12 : 6);
			return switch (kind) {
				case 0 -> literal();
				case 1 -> random.nextBoolean() ? pick(NAMES)
						: "defined(" + (name = pick(NAMES)) + ") && " + name;
				case 2 -> "defined " + pick(NAMES);
				case 3 -> "defined(" + pick(NAMES) + ")";
				case 4 -> pick(DRIVER);
				case 5 -> pick(new String[]{"F(2)", "H()", "G(1, 2)",
						"F(A)", "H"});
				case 6 -> "!" + condition(depth - 1);
				case 7 -> "(" + condition(depth - 1) + ")";
				case 8 -> "-" + condition(depth - 1);
				default -> condition(depth - 1) + " " + pick(OPS) + " " +
						condition(depth - 1);
			};
		}

		private String value() {
			return switch (random.nextInt(7)) {
				case 0 -> "";
				case 1 -> literal();
				case 2 -> "(" + literal() + " " + pick(OPS) + " " +
						literal() + ")";
				case 3 -> pick(NAMES);
				case 4 -> "F(" + pick(NAMES) + ")";
				case 5 -> "(" + pick(NAMES) + " + " + literal() + ")";
				default -> "-" + literal();
			};
		}

		private String literal() {
			return switch (random.nextInt(6)) {
				case 0 -> "0";
				case 1 -> "0x" + Integer.toHexString(random.nextInt(64))
						.toUpperCase(Locale.ROOT);
				case 2 -> "0" + random.nextInt(8);
				case 3 -> "-" + random.nextInt(9);
				default -> String.valueOf(random.nextInt(12));
			};
		}

		private String use() {
			++uses;
			return switch (random.nextInt(6)) {
				case 0 -> "F(" + pick(NAMES) + ")";
				case 1 -> "F(F(" + random.nextInt(5) + "))";
				case 2 -> "H()";
				case 3 -> pick(NAMES) + " + F(1)";
				case 4 -> "F(\n\t\t" + random.nextInt(5) + ")";
				default -> "int(" + uses + ")";
			};
		}

		private String pick(String[] names) {
			return names[random.nextInt(names.length)];
		}

		private void line(String line) {
			sb.append(line).append('\n');
		}
	}
}
