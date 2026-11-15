package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ShaderToy's inputs that are one of the app's uniforms with the same
 * value: the one table of them, for text pasted in ShaderToy's form
 * and for a shader imported with its passes.
 */
public final class ShaderToyInputs {
	/**
	 * One input: its name and type in ShaderToy, the app's uniform that
	 * holds its value with the declaration of that uniform, and the
	 * uniform as an expression of the input's type.
	 */
	public record Input(String name, String type, String uniform,
			String declaration, String value) {
	}

	// iTime and iGlobalTime are seconds as a float, iFrame counts
	// frames from 0, iMouse is mouse in its vec4 form, iDate is year,
	// month from 0, day and seconds of the day. iResolution is a vec3
	// there and a vec2 here; .xy works on the uniform itself.
	public static final List<Input> ALL = List.of(
			new Input("iResolution", "vec3", "resolution",
					"uniform vec2 resolution;", "vec3(resolution, 1.0)"),
			new Input("iGlobalTime", "float", "time",
					"uniform float time;", "time"),
			new Input("iTime", "float", "time",
					"uniform float time;", "time"),
			new Input("iFrame", "int", "frame",
					"uniform int frame;", "frame"),
			new Input("iMouse", "vec4", "mouse",
					"uniform vec4 mouse;", "mouse"),
			new Input("iDate", "vec4", "date",
					"uniform vec4 date;", "date"));

	private ShaderToyInputs() {
	}

	/** Whether src has name as a whole word: iTimeDelta is not iTime. */
	public static boolean names(String src, String name) {
		return Pattern.compile("\\b" + name + "\\b").matcher(src).find();
	}

	/**
	 * The declarations of the uniforms of the inputs src names, each
	 * once, and a blank line; empty when it names none. Only those: a
	 * port may name its own frame or time.
	 */
	public static String declarations(String src) {
		Set<String> declared = new LinkedHashSet<>();
		for (Input input : ALL) {
			if (names(src, input.name())) {
				declared.add(input.declaration());
			}
		}
		StringBuilder sb = new StringBuilder();
		for (String declaration : declared) {
			sb.append(declaration).append('\n');
		}
		return sb.length() > 0 ? sb.append('\n').toString() : "";
	}

	/** src with every input named as the app's uniform is. */
	public static String rename(String src) {
		for (Input input : ALL) {
			src = src.replaceAll("\\b" + input.name() + "\\b",
					input.uniform());
		}
		return src;
	}
}
