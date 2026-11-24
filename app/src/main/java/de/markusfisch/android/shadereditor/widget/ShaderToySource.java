package de.markusfisch.android.shadereditor.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.editor.Text;

import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyInputs;

public final class ShaderToySource {
	// Used with find(), so no leading or trailing ".*": that would make
	// the search cost the square of the line length.
	private static final Pattern PATTERN_SHADER_TOY = Pattern.compile(
			"void\\s+mainImage\\s*\\(");
	private static final Pattern PATTERN_MAIN = Pattern.compile(
			"void\\s+main\\s*\\(");

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
				ShaderToyInputs.declarations(src) +
				ShaderToyInputs.rename(src) +
				"\n\nvoid main() {\n" +
				"\tvec4 fragment_color;\n" +
				"\tmainImage(fragment_color, gl_FragCoord.xy);\n" +
				"\tgl_FragColor = fragment_color;\n" +
				"}\n";
	}

	/**
	 * @return {@code src} converted, if one of the texts the edits put in
	 * brought its mainImage() in, else null.
	 */
	@Nullable
	public static String convert(@NonNull String src,
			@NonNull List<Text> inserted) {
		for (Text text : inserted) {
			if (needsConversion(src, text.toString())) {
				return convert(src);
			}
		}
		return null;
	}

	/**
	 * @return True if {@code inserted} brought a mainImage() into
	 * {@code src} and {@code src} defines no main().
	 */
	static boolean needsConversion(String src, CharSequence inserted) {
		return PATTERN_SHADER_TOY.matcher(inserted).find() &&
				needsConversion(src);
	}

	/**
	 * @return True if {@code src} defines mainImage() but no main().
	 */
	static boolean needsConversion(String src) {
		return PATTERN_SHADER_TOY.matcher(src).find() &&
				!PATTERN_MAIN.matcher(src).find();
	}
}
