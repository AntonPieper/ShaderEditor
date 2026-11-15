package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.SourceText;

/**
 * What ShaderToy declares above the code of a tab, written with the
 * app's uniforms: its inputs as variables that a function fills, so
 * that the code is not touched and a name of its own never hides one.
 */
final class ShaderToyHeader {
	// Inputs the app has no uniform for: type, name, value, and what to
	// tell about the value, or null when it is ShaderToy's.
	private static final String[][] CONSTANTS = {
			{"float", "iTimeDelta", "1.0 / 60.0",
					"iTimeDelta is always 1/60 s here, as the app does not " +
							"tell a shader how long a frame took. On a " +
							"screen that is not 60 Hz, what is computed " +
							"from it runs at another speed."},
			{"float", "iFrameRate", "60.0",
					"iFrameRate is always 60 here, as the app does not " +
							"tell a shader its frame rate."},
			{"float", "iSampleRate", "44100.0", null},
	};

	private static final ProjectPath ANY = ProjectPath.parse("any").value();

	private ShaderToyHeader() {
	}

	/**
	 * The uniforms, variables and the function shaderToyInputs() for
	 * the inputs code names; warnings gets what is not ShaderToy's
	 * value, told of file.
	 */
	static String inputs(String code, List<ShaderToyChannel> channels,
			String file, List<String> warnings) {
		Set<String> uniforms = new LinkedHashSet<>();
		StringBuilder variables = new StringBuilder();
		StringBuilder filled = new StringBuilder();
		for (ShaderToyInputs.Input input : ShaderToyInputs.ALL) {
			if (ShaderToyInputs.names(code, input.name())) {
				uniforms.add(input.declaration());
				add(variables, filled, input.type(), input.name(), "",
						input.value());
			}
		}
		for (String[] constant : CONSTANTS) {
			if (ShaderToyInputs.names(code, constant[1])) {
				add(variables, filled, constant[0], constant[1], "",
						constant[2]);
				if (constant[3] != null) {
					warnings.add(file + ": " + constant[3]);
				}
			}
		}
		if (ShaderToyInputs.names(code, "iChannelTime")) {
			add(variables, filled, "float", "iChannelTime", "[4]",
					"float[4](0.0, 0.0, 0.0, 0.0)");
		}
		if (ShaderToyInputs.names(code, "iChannelResolution")) {
			String[] sizes = {"vec3(0.0)", "vec3(0.0)", "vec3(0.0)",
					"vec3(0.0)"};
			for (ShaderToyChannel channel : channels) {
				String size = "textureSize(iChannel" + channel.number() +
						", 0)";
				sizes[channel.number()] =
						channel.sampler().equals("sampler3D")
								? "vec3(" + size + ")"
								: "vec3(vec2(" + size + "), 1.0)";
			}
			add(variables, filled, "vec3", "iChannelResolution", "[4]",
					"vec3[4](" + String.join(", ", sizes) + ")");
		}
		StringBuilder sb = new StringBuilder(
				"// ShaderToy's inputs, filled from the app's in main().\n");
		for (String uniform : uniforms) {
			sb.append(uniform).append('\n');
		}
		return sb.append(uniforms.isEmpty() ? "" : "\n")
				.append(variables)
				.append("\nvoid shaderToyInputs() {\n")
				.append(filled)
				.append("}\n")
				.toString();
	}

	private static void add(StringBuilder variables, StringBuilder filled,
			String type, String name, String size, String value) {
		variables.append(type).append(' ').append(name).append(size)
				.append(";\n");
		filled.append('\t').append(name).append(" = ").append(value)
				.append(";\n");
	}

	/**
	 * The lines that stand between the inputs and the code: what
	 * ShaderToy defines, and another name for whatever the code
	 * declares outside its functions under the name of an app uniform
	 * that the inputs above declared, so that the two do not meet.
	 *
	 * @param code     the code without comments
	 * @param declared the same without its directives
	 */
	static String defines(String code, String declared) {
		StringBuilder sb = new StringBuilder();
		if (ShaderToyInputs.names(code, "HW_PERFORMANCE")) {
			sb.append("// ShaderToy's mark of a slow device.\n" +
					"#define HW_PERFORMANCE 0\n");
		}
		Set<String> uniforms = new LinkedHashSet<>();
		for (ShaderToyInputs.Input input : ShaderToyInputs.ALL) {
			if (ShaderToyInputs.names(code, input.name())) {
				uniforms.add(input.uniform());
			}
		}
		for (String uniform : uniforms) {
			if (declares(declared, uniform) && !Pattern.compile(
					"(?m)^\\s*#\\s*define\\s+" + uniform + "\\b")
					.matcher(code).find()) {
				sb.append("// The shader has a \"").append(uniform)
						.append("\" of its own, which is also the name " +
								"of the app's uniform read above.\n")
						.append("#define ").append(uniform)
						.append(" shaderToyOwn_").append(uniform)
						.append('\n');
			}
		}
		return sb.length() > 0 ? sb.append('\n').toString() : "";
	}

	/**
	 * Whether code, without comments and directives, has name outside
	 * every brace and parenthesis, where only a declaration can stand.
	 */
	static boolean declares(String code, String name) {
		int depth = 0;
		for (int i = 0, length = code.length(); i < length; ++i) {
			char c = code.charAt(i);
			if (c == '{' || c == '(') {
				++depth;
			} else if (c == '}' || c == ')') {
				--depth;
			} else if (Character.isJavaIdentifierStart(c)) {
				int end = i + 1;
				while (end < length &&
						Character.isJavaIdentifierPart(code.charAt(end))) {
					++end;
				}
				if (depth == 0 && code.startsWith(name, i) &&
						end - i == name.length()) {
					return true;
				}
				i = end - 1;
			}
		}
		return false;
	}

	/**
	 * text without its comments, and without its directives when
	 * declarations are looked for, where a name stands for nothing.
	 */
	static String code(String text, boolean declarations) {
		PreprocessorScan scan = PreprocessorScan.scan(
				SourceText.lines(text.replace("\r\n", "\n")
						.replace('\r', '\n')), ANY);
		List<String> lines = new ArrayList<>(scan.code());
		if (declarations) {
			for (PreprocessorScan.Directive directive : scan.directives()) {
				int at = directive.line() - 1;
				do {
					String line = lines.get(at);
					lines.set(at, "");
					if (!line.trim().endsWith("\\")) {
						break;
					}
				} while (++at < lines.size());
			}
		}
		return String.join("\n", lines);
	}

	// On one line, for a comment.
	static String line(String text) {
		return text.replaceAll("\\s+", " ").trim();
	}
}
