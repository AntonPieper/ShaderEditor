package de.markusfisch.android.shadereditor.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

public final class ShaderToySource {
	// Used with find(), so no leading or trailing ".*": that would make
	// the search cost the square of the line length.
	private static final Pattern PATTERN_SHADER_TOY = Pattern.compile(
			"void\\s+mainImage\\s*\\(");
	private static final Pattern PATTERN_MAIN = Pattern.compile(
			"void\\s+main\\s*\\(");
	// ShaderToy's inputs that are one of the app's uniforms, of the
	// same type and value: iTime and iGlobalTime are seconds as a
	// float, iFrame counts frames from 0, iMouse is mouse in its vec4
	// form, iDate is year, month from 0, day and seconds of the day.
	// iResolution is a vec3 there and a vec2 here; .xy works.
	private static final String[][] INPUTS = {
			{"iResolution", "resolution"},
			{"iGlobalTime", "time"},
			{"iTime", "time"},
			{"iFrame", "frame"},
			{"iMouse", "mouse"},
			{"iDate", "date"},
	};

	private ShaderToySource() {
	}

	/**
	 * @return {@code src} wrapped in a main() with the uniforms that
	 * have an equivalent renamed, or null if it needs no conversion.
	 */
	@Nullable
	public static String convert(@NonNull String src) {
		if (!needsConversion(src)) {
			return null;
		}
		// Only include and translate uniforms that have an equivalent.
		return "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
				"precision highp float;\n" +
				"#else\n" +
				"precision mediump float;\n" +
				"#endif\n\n" +
				"uniform vec2 resolution;\n" +
				"uniform float time;\n" +
				"uniform int frame;\n" +
				"uniform vec4 mouse;\n" +
				"uniform vec4 date;\n\n" +
				rename(src) +
				"\n\nvoid main() {\n" +
				"\tvec4 fragment_color;\n" +
				"\tmainImage(fragment_color, gl_FragCoord.xy);\n" +
				"\tgl_FragColor = fragment_color;\n" +
				"}\n";
	}

	// Whole names only: iTimeDelta is not iTime.
	@NonNull
	static String rename(@NonNull String src) {
		for (String[] input : INPUTS) {
			src = src.replaceAll("\\b" + input[0] + "\\b", input[1]);
		}
		return src;
	}

	/**
	 * @return True if {@code src} defines mainImage() but no main().
	 */
	static boolean needsConversion(String src) {
		return PATTERN_SHADER_TOY.matcher(src).find() &&
				!PATTERN_MAIN.matcher(src).find();
	}
}
