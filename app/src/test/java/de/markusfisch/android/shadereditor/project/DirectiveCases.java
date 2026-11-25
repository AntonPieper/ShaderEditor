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
public final class DirectiveCases {
	static final int GENERATED = 600;
	/**
	 * One compiled program: its text, the 0-based lines BuiltLines says
	 * are off, and the states of all lines.
	 */
	public record Case(String name, int version, String text, List<Integer> off,
			List<DirectiveRun.State> states) {
	}

	private DirectiveCases() {
	}

	public static List<Case> all() {
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
		File probes = probes();
		for (String name : sorted(probes.list())) {
			add(cases, "probes/" + name, read(new File(probes, name)));
		}
		for (int seed = 0; seed < GENERATED; ++seed) {
			add(cases, "generated/" + seed, generate(seed));
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
		Map<ProjectPath, List<Integer>> offs = new java.util.HashMap<>();
		for (int p = 0; p < plan.programs().size(); ++p) {
			RenderPlan.Program program = plan.programs().get(p);
			CompiledSource compiled = CompiledSource.of(program.lines(),
					program.head(), program.sourceMap());
			String text = compiled.text();
			DirectiveRun run = DirectiveRun.of(text, compiled.version());
			String[] lines = text.split("\n", -1);
			List<Integer> off = new ArrayList<>();
			List<DirectiveRun.State> states = new ArrayList<>();
			for (int n = 0; n < lines.length; ++n) {
				states.add(run.state(n));
				SourceLocation at = compiled.sourceMap().locate(n + 1);
				if (at == null || at.isGenerated() ||
						at.includedFrom() != null) {
					continue;
				}
				List<Integer> file = offs.get(at.file());
				if (file == null) {
					file = built.off(at.file(), 3);
					offs.put(at.file(), file);
				}
				if (file.contains(at.line() - 1)) {
					off.add(n);
				}
			}
			cases.add(new Case(name + "#" + p, compiled.version(), text,
					off, states));
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

	public static File probes() {
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
						"#define " + name + " // note \\",
						"#define " + name +
								" 0 /* low,\n\tsays the note */ + 1"}));
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
			int kind = random.nextInt(11);
			// A comment a directive leaves open goes on with it.
			String open = " /* why,\n\tand why not */ " +
					pick(new String[]{"&& 1", "|| 1", "+ 1"});
			line(kind < 2 ? "#if " + random.nextInt(2)
					: kind < 4 ? "#ifdef " + pick(NAMES)
					: kind < 5 ? "#ifndef " + pick(NAMES)
					: kind < 6 ? "#if " + condition(2) + " \\\n\t" +
					pick(new String[]{"&& 1", "|| 0", "+ 1"})
					: kind < 7 ? "#if " + condition(2) + open
					: "#if " + condition(3));
			items(depth + 1, 1 + random.nextInt(3), code);
			for (int i = random.nextInt(3); i-- > 0; ) {
				line("#elif " + condition(3) + (kind == 10 ? open : ""));
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
