package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Input;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Kind;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Pass;

/**
 * Reads a shader from ShaderToy's JSON, in the two shapes it has: the
 * answer of its API, {@code {"Shader": {...}}} with inputs that have
 * "ctype" and "src", and the site's own, a list of one shader with
 * inputs that have "type" and "filepath". The shader alone, without
 * either wrapping, is read too, and either spelling in either shape.
 */
public final class ShaderToyJson {
	/**
	 * What reading found: the shader with what to tell about the
	 * reading, or why there is none.
	 */
	public record Read(ShaderToyShader shader, List<String> notes,
			String refusal) {
	}

	// An answer of the API that is an error and nothing else.
	private static final Pattern ERROR = Pattern.compile(
			"\\{\\s*\"Error\"\\s*:");

	private ShaderToyJson() {
	}

	/**
	 * Whether text is ShaderToy's JSON by its content, so that
	 * {@link #read} speaks for it: it begins as JSON does and has the
	 * list of passes, or is an error the API answered. Any other text
	 * is not ShaderToy's, whatever its file was called.
	 */
	public static boolean recognizes(String text) {
		int start = !text.isEmpty() && text.charAt(0) == 0xFEFF ? 1 : 0;
		while (start < text.length() &&
				Character.isWhitespace(text.charAt(start))) {
			++start;
		}
		if (start == text.length() || "{[".indexOf(text.charAt(start)) < 0) {
			return false;
		}
		return text.contains("\"renderpass\"") || ERROR.matcher(text)
				.region(start, text.length()).lookingAt();
	}

	/** Read the shader of text that {@link #recognizes} took. */
	public static Read read(String text) {
		Object root;
		try {
			root = Json.parse(text);
		} catch (Json.Malformed e) {
			return refuse("the JSON is cut off or broken (" +
					e.getMessage() + ")");
		} catch (OutOfMemoryError | StackOverflowError e) {
			return refuse("the JSON is too large to read");
		}
		List<String> notes = new ArrayList<>();
		if (root instanceof List<?> list) {
			if (list.isEmpty()) {
				return refuse("the list holds no shader");
			} else if (list.size() > 1) {
				notes.add("The text holds " + list.size() +
						" shaders; the first was imported.");
			}
			root = list.get(0);
		}
		if (root instanceof Map<?, ?> wrapped &&
				wrapped.get("Shader") instanceof Map<?, ?> shader) {
			root = shader;
		}
		if (!(root instanceof Map<?, ?> shader)) {
			return refuse("it is not a shader of ShaderToy");
		} else if (!(shader.get("renderpass") instanceof List<?> list)) {
			return refuse(shader.get("Error") instanceof String error
					? "ShaderToy answered with an error: " + error
					: "it has no list of passes (\"renderpass\")");
		} else if (list.isEmpty()) {
			return refuse("it has no passes");
		} else {
			List<Pass> passes = new ArrayList<>();
			for (int i = 0; i < list.size(); ++i) {
				String refusal = pass(list.get(i), i + 1, passes, notes);
				if (refusal != null) {
					return refuse(refusal);
				}
			}
			Map<?, ?> info = shader.get("info") instanceof Map<?, ?> map
					? map
					: Map.of();
			return new Read(new ShaderToyShader(string(info, "name"),
					string(info, "username"), string(info, "id"), true,
					passes), notes, null);
		}
	}

	private static Read refuse(String refusal) {
		return new Read(null, List.of(), refusal);
	}

	// Add the pass that value is, the number-th. Return why it is none,
	// or null.
	private static String pass(Object value, int number, List<Pass> passes,
			List<String> notes) {
		if (!(value instanceof Map<?, ?> pass)) {
			return "pass " + number + " is not an object";
		}
		String type = string(pass, "type");
		String name = string(pass, "name");
		Kind kind = kind(type);
		if (name == null || name.trim().isEmpty()) {
			name = type != null ? type : "pass " + number;
		}
		if (!(pass.get("code") instanceof String code)) {
			return name + " (pass " + number + ") has no code";
		}
		Object listed = pass.get("inputs");
		List<Input> inputs = new ArrayList<>();
		if (listed instanceof List<?> list) {
			for (Object item : list) {
				if (!(item instanceof Map<?, ?> input)) {
					return "an input of " + name + " is not an object";
				}
				Input read = input(input);
				if (read == null) {
					notes.add("An input of " + name + " names no channel " +
							"from 0 to 3 and was left out.");
				} else {
					inputs.add(read);
				}
			}
		} else if (listed != null && listed != Json.NULL) {
			return "the inputs of " + name + " are not a list";
		}
		String output = pass.get("outputs") instanceof List<?> outputs &&
				!outputs.isEmpty() &&
				outputs.get(0) instanceof Map<?, ?> first
				? id(first.get("id"))
				: "";
		passes.add(new Pass(kind, name.trim(), code, inputs, output));
		return null;
	}

	private static Kind kind(String type) {
		if (type != null) {
			for (Kind kind : Kind.values()) {
				if (kind.name().toLowerCase(Locale.ROOT).equals(type)) {
					return kind;
				}
			}
		}
		return Kind.OTHER;
	}

	// Null when it names no channel.
	private static Input input(Map<?, ?> input) {
		if (!(input.get("channel") instanceof Double number) ||
				number < 0 || number > 3 || number != Math.rint(number)) {
			return null;
		}
		Map<?, ?> sampler = input.get("sampler") instanceof Map<?, ?> map
				? map
				: Map.of();
		String kind = string(input, "ctype");
		String source = string(input, "src");
		return new Input(number.intValue(),
				kind != null ? kind : or(string(input, "type"), "unknown"),
				id(input.get("id")),
				source != null ? source : or(string(input, "filepath"), ""),
				or(string(sampler, "filter"), "linear"),
				or(string(sampler, "wrap"), "clamp"),
				!"false".equals(String.valueOf(sampler.get("vflip"))));
	}

	// The API has numbers, the site strings.
	private static String id(Object id) {
		return id instanceof Double number && number == Math.rint(number)
				? String.valueOf(number.longValue())
				: id instanceof String string ? string : "";
	}

	private static String string(Map<?, ?> object, String key) {
		return object.get(key) instanceof String string ? string : null;
	}

	private static String or(String value, String fallback) {
		return value != null ? value : fallback;
	}
}
