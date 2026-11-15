package de.markusfisch.android.shadereditor.spike;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * SPIKE, throwaway: the inputs of the code-knowledge study. The bundled
 * shaders and examples as they are on disk, the same repeated to a line
 * count (what LANGUAGE.md measured with), and generated GLSL ES 3.00 of
 * any length in which every function has its own name, so a symbol table
 * has realistic work.
 */
final class Corpus {
	private Corpus() {
	}

	/** Print generated GLSL of args[0] lines, to check it elsewhere. */
	public static void main(String[] args) {
		System.out.print(generated(Integer.parseInt(args[0]), 42));
	}

	/** The shaders in res/raw and the examples, by path. */
	static Map<String, String> bundled() {
		Map<String, String> out = new TreeMap<>();
		for (String base : new String[]{".", "app"}) {
			File raw = new File(base, "src/main/res/raw");
			if (raw.isDirectory()) {
				add(raw, "raw/", out);
				add(new File(base, "src/debug/assets/examples"),
						"examples/", out);
			}
		}
		if (out.isEmpty()) {
			throw new AssertionError("no bundled shaders from " +
					new File(".").getAbsolutePath());
		}
		return out;
	}

	private static void add(File dir, String prefix,
			Map<String, String> out) {
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File f : files) {
			String name = prefix + f.getName();
			if (f.isDirectory()) {
				add(f, name + "/", out);
			} else if (name.endsWith(".glsl") || name.endsWith(".frag")) {
				try {
					out.put(name, new String(Files.readAllBytes(f.toPath()),
							StandardCharsets.UTF_8).replace("\r\n", "\n"));
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			}
		}
	}

	/** The bundled shaders one after the other until lines are reached. */
	static String repeated(int lines) {
		StringBuilder sb = new StringBuilder();
		int count = 0;
		List<String> all = new ArrayList<>(bundled().values());
		for (int i = 0; count < lines; ++i) {
			String s = all.get(i % all.size());
			if (!s.endsWith("\n")) {
				s += "\n";
			}
			sb.append(s);
			count += countLines(s);
		}
		return sb.toString();
	}

	static int countLines(String s) {
		int n = 0;
		for (int i = 0; i < s.length(); ++i) {
			if (s.charAt(i) == '\n') {
				++n;
			}
		}
		return n;
	}

	/**
	 * GLSL ES 3.00 of about lines lines: a header of uniforms, macros
	 * and structs, then functions that call built-ins and each other,
	 * with comments above them, loops, branches, swizzles, struct
	 * fields and now and then a conditional region.
	 */
	static String generated(int lines, long seed) {
		return new Generator(seed).run(lines);
	}

	private static final class Generator {
		private final Random random;
		private final StringBuilder sb = new StringBuilder();
		private final List<String> floatFns = new ArrayList<>();
		private final List<String> vecFns = new ArrayList<>();
		private int lines;
		private int serial;
		private int depth;

		Generator(long seed) {
			random = new Random(seed);
		}

		String run(int target) {
			line("#version 300 es");
			line("precision highp float;");
			line("");
			line("uniform vec2 resolution; // size in pixels");
			line("uniform float time; // seconds since load");
			line("uniform vec3 gravity;");
			line("uniform sampler2D noiseTex;");
			line("out vec4 fragColor;");
			line("");
			line("#define PI 3.14159265");
			line("#define TAU (2.0 * PI)");
			line("#define SAT(x) clamp(x, 0.0, 1.0)");
			line("#define STEPS 48");
			line("#define QUALITY_HIGH");
			line("");
			line("// A ray: where it starts, where it goes, how far it went.");
			line("struct Ray {");
			line("\tvec3 origin;");
			line("\tvec3 dir;");
			line("\tfloat t;");
			line("};");
			line("");
			line("/* What a ray hit. */");
			line("struct Hit {");
			line("\tvec3 pos;");
			line("\tvec3 normal;");
			line("\tfloat dist;");
			line("\tint id;");
			line("};");
			line("");
			line("const vec3 LIGHT = vec3(0.6, 0.7, 0.4);");
			line("const float EPS = 0.001;");
			line("const float WEIGHTS[4] = float[4](0.4, 0.3, 0.2, 0.1);");
			line("");
			while (lines < target - 30) {
				function();
			}
			line("void main() {");
			line("\tvec2 uv = gl_FragCoord.xy / resolution;");
			line("\tRay ray = Ray(vec3(0.0), normalize(vec3(uv, 1.0)), " +
					"0.0);");
			line("\tvec3 col = vec3(0.0);");
			for (int i = 0; i < 8 && !vecFns.isEmpty(); ++i) {
				line("\tcol += " + pick(vecFns) + "(ray.origin + ray.dir * " +
						lit() + ", time) * " + lit() + ";");
			}
			line("\tfragColor = vec4(pow(col, vec3(1.0 / 2.2)), 1.0);");
			line("}");
			return sb.toString();
		}

		private void function() {
			boolean vec = random.nextInt(3) == 0;
			String name = (vec ? "shade" : "field") + serial++;
			boolean conditional = random.nextInt(25) == 0;
			if (conditional) {
				line("#ifdef QUALITY_HIGH");
			}
			int comment = random.nextInt(4);
			if (comment == 0) {
				line("// " + name + ": distance to a shape number " +
						serial + ".");
				line("// p is the position, t the time in seconds.");
			} else if (comment == 1) {
				line("/**");
				line(" * Shades the point p at time t.");
				line(" * Returns a value in 0..1.");
				line(" */");
			} else if (comment == 2) {
				line("// " + name);
			}
			line((vec ? "vec3 " : "float ") + name + "(vec3 p, float t) {");
			depth = 1;
			line("\tvec3 q = p * " + lit() + ";");
			line("\tvec2 uv = q.xy + vec2(" + lit() + ", t);");
			line("\tfloat a = sin(t * " + lit() + ");");
			line("\tfloat b = length(q) * " + lit() + ";");
			line("\tvec3 col = vec3(a, b, " + lit() + ");");
			line("\tvec4 c = texture(noiseTex, uv * " + lit() + ");");
			line("\tmat2 m = mat2(cos(a), -sin(a), sin(a), cos(a));");
			line("\tRay r = Ray(q, normalize(col), b);");
			int statements = 2 + random.nextInt(10);
			for (int i = 0; i < statements; ++i) {
				statement();
			}
			line(vec ? "\treturn col * a + " + v3() + ";"
					: "\treturn a + b * " + f() + ";");
			depth = 0;
			line("}");
			if (conditional) {
				line("#else");
				line((vec ? "vec3 " : "float ") + name +
						"(vec3 p, float t) {");
				line(vec ? "\treturn p * t;" : "\treturn length(p) + t;");
				line("}");
				line("#endif");
			}
			line("");
			(vec ? vecFns : floatFns).add(name);
		}

		private void statement() {
			String tabs = "\t".repeat(depth);
			switch (random.nextInt(depth < 3 ? 12 : 9)) {
				case 0 -> line(tabs + "a = " + f() + ";");
				case 1 -> line(tabs + "b += " + f() + " * " + f() + ";");
				case 2 -> line(tabs + "col = " + v3() + ";");
				case 3 -> line(tabs + "col += " + v3() + " * " + f() + ";");
				case 4 -> line(tabs + "q = " + v3() + " - q.zxy * " + lit() +
						";");
				case 5 -> line(tabs + "uv = m * uv + " + v2() + ";");
				case 6 -> line(tabs + "r.t = " + f() + "; // step");
				case 7 -> line(tabs + "c.rgb = mix(c.rgb, " + v3() + ", " +
						f() + ");");
				case 8 -> line(tabs + "r.origin += r.dir * " + f() + ";");
				case 9 -> {
					line(tabs + "for (int i = 0; i < " +
							(2 + random.nextInt(6)) + "; i++) {");
					block(tabs);
				}
				case 10 -> {
					line(tabs + "if (" + f() + " > " + f() + ") {");
					++depth;
					int n = 1 + random.nextInt(3);
					for (int i = 0; i < n; ++i) {
						statement();
					}
					--depth;
					if (random.nextBoolean()) {
						line(tabs + "} else {");
						++depth;
						statement();
						--depth;
					}
					line(tabs + "}");
				}
				default -> {
					line(tabs + "/* a block of work,");
					line(tabs + "   explained over two lines */");
					line(tabs + "{");
					block(tabs);
				}
			}
		}

		private void block(String tabs) {
			++depth;
			int n = 1 + random.nextInt(4);
			for (int i = 0; i < n; ++i) {
				statement();
			}
			--depth;
			line(tabs + "}");
		}

		private String f() {
			switch (random.nextInt(14)) {
				case 0:
					return lit();
				case 1:
					return "a";
				case 2:
					return "b";
				case 3:
					return "length(" + v3() + ")";
				case 4:
					return "dot(q, " + v3() + ")";
				case 5:
					return "q." + "xyz".charAt(random.nextInt(3));
				case 6:
					return "sin(a * " + lit() + " + t)";
				case 7:
					return "r.t";
				case 8:
					return "SAT(b)";
				case 9:
					return "mix(a, b, " + lit() + ")";
				case 10:
					return floatFns.isEmpty() ? "EPS"
							: pick(floatFns) + "(q, t)";
				case 11:
					return "c.a";
				case 12:
					return "WEIGHTS[" + random.nextInt(4) + "]";
				default:
					return "smoothstep(0.0, 1.0, a) * PI";
			}
		}

		private String v3() {
			switch (random.nextInt(11)) {
				case 0:
					return "vec3(" + lit() + ")";
				case 1:
					return "vec3(a, b, " + lit() + ")";
				case 2:
					return "q";
				case 3:
					return "normalize(q)";
				case 4:
					return "col.zyx";
				case 5:
					return "mix(col, q, " + lit() + ")";
				case 6:
					return "r.origin";
				case 7:
					return "cross(q, LIGHT)";
				case 8:
					return "c.rgb";
				case 9:
					return vecFns.isEmpty() ? "LIGHT"
							: pick(vecFns) + "(q, t)";
				default:
					return "texture(noiseTex, uv).rgb";
			}
		}

		private String v2() {
			switch (random.nextInt(4)) {
				case 0:
					return "q.xy";
				case 1:
					return "vec2(a, b)";
				case 2:
					return "uv * " + lit();
				default:
					return "c.xy";
			}
		}

		private String lit() {
			return String.format(java.util.Locale.ROOT, "%.2f",
					random.nextInt(400) / 100.0);
		}

		private String pick(List<String> names) {
			// Mostly recent functions, as code calls what is near.
			int n = names.size();
			int back = Math.min(n, 1 + random.nextInt(20));
			return names.get(n - back);
		}

		private void line(String s) {
			sb.append(s).append('\n');
			++lines;
		}
	}

	/** Ways a text is broken while it is being typed. */
	enum Break {
		MISSING_BRACE,
		HALF_CALL,
		HALF_MEMBER,
		OPEN_COMMENT,
		HALF_DECLARATION,
		EXTRA_BRACE,
		OPEN_PAREN_GARBAGE
	}

	/**
	 * Break source at the first line at or after fromLine that is a
	 * statement inside a function (starts with a tab and ends in ";"),
	 * the way kind says. Returns null if there is no such line.
	 */
	static String broken(String source, int fromLine, Break kind) {
		String[] lines = source.split("\n", -1);
		int at = -1;
		for (int i = fromLine; i < lines.length; ++i) {
			if (lines[i].startsWith("\t") && lines[i].endsWith(";") &&
					!lines[i].trim().startsWith("return")) {
				at = i;
				break;
			}
		}
		if (at < 0) {
			return null;
		}
		String indent = lines[at].substring(0,
				lines[at].length() - lines[at].stripLeading().length());
		switch (kind) {
			case MISSING_BRACE -> {
				// The brace that closes the function this line is in.
				int close = -1;
				for (int i = at; i < lines.length; ++i) {
					if (lines[i].equals("}")) {
						close = i;
						break;
					}
				}
				if (close < 0) {
					return null;
				}
				lines[close] = "";
			}
			case HALF_CALL -> lines[at] = indent + "float zz = mix(a, ";
			case HALF_MEMBER -> lines[at] = indent + "col.";
			case OPEN_COMMENT -> lines[at] = indent + "/* note";
			case HALF_DECLARATION -> lines[at] = indent + "vec3 ";
			case EXTRA_BRACE -> lines[at] = lines[at] + " }";
			case OPEN_PAREN_GARBAGE -> lines[at] = indent +
					"x = ((foo(1.0, @ $ ;";
		}
		return String.join("\n", lines);
	}
}
