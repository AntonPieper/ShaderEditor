package de.markusfisch.android.shadereditor.project.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

/**
 * Sampler settings as database shaders write them in a trailing
 * {@code ///min:..;mag:..;s:..;t:..;} comment, read exactly as the old
 * renderer read them, quirks included: an unknown key sets the
 * minification filter and an unknown value selects the fallback of its
 * key.
 *
 * @param min    base minification filter
 * @param mipmap mipmap mode of the minification filter
 * @param preset library texture a backbuffer starts with, or null
 */
public record LegacySamplerSettings(
		Filter min,
		Mipmap mipmap,
		Filter mag,
		Wrap wrapS,
		Wrap wrapT,
		String preset) {
	/** What a library texture or camera sampler has without a comment. */
	public static final LegacySamplerSettings TEXTURE =
			new LegacySamplerSettings(Filter.NEAREST, Mipmap.NONE,
					Filter.LINEAR, Wrap.REPEAT, Wrap.REPEAT, null);
	/** What {@code backbuffer} has without a comment. */
	public static final LegacySamplerSettings BACKBUFFER =
			new LegacySamplerSettings(Filter.NEAREST, Mipmap.NONE,
					Filter.NEAREST, Wrap.CLAMP, Wrap.CLAMP, null);

	private static final String HEADER = "///";
	private static final String PRESET = "p";

	/**
	 * Apply the text after a declaration's semicolon. Only
	 * {@code backbuffer} reads {@code p:}; for other samplers it is an
	 * unknown key.
	 */
	public LegacySamplerSettings parse(String params, boolean backbuffer) {
		LegacySamplerSettings settings = this;
		for (String[] parameter : parameters(params)) {
			settings = settings.with(parameter[0], parameter[1], backbuffer);
		}
		return settings;
	}

	/**
	 * Whether the text after a declaration's semicolon is a comment that
	 * holds nothing but settings with known keys, so the converted
	 * declaration can drop it.
	 */
	public static boolean isSettingsOnly(String params, boolean backbuffer) {
		List<String[]> parameters = parameters(params);
		if (parameters.isEmpty() ||
				parameters.size() != params.trim().substring(3)
						.split(";").length) {
			return false;
		}
		for (String[] parameter : parameters) {
			switch (parameter[0]) {
				case "min", "mag", "s", "t" -> {
				}
				default -> {
					if (!backbuffer || !PRESET.equals(parameter[0])) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Return the directive options that select these settings on top of
	 * defaults, shortest first: {@code filter} when min and mag agree,
	 * both {@code min} and {@code mag} otherwise, and nothing that equals
	 * a default. The preset is not an option.
	 */
	public String options(Sampling defaults, boolean wrap) {
		List<String> options = new ArrayList<>();
		if (min != defaults.min() || mag != defaults.mag()) {
			if (min == mag) {
				options.add("filter=" + name(min));
			} else {
				options.add("min=" + name(min));
				options.add("mag=" + name(mag));
			}
		}
		if (mipmap != defaults.mipmap()) {
			options.add("mipmap=" + name(mipmap));
		}
		if (wrap && (wrapS != defaults.wrapS() ||
				wrapT != defaults.wrapT())) {
			options.add("wrap=" + (wrapS == wrapT
					? name(wrapS)
					: name(wrapS) + "," + name(wrapT)));
		}
		return String.join(" ", options);
	}

	/** These settings with the mipmap mode dropped from minification. */
	public LegacySamplerSettings withoutMipmap() {
		return new LegacySamplerSettings(min, Mipmap.NONE, mag, wrapS, wrapT,
				preset);
	}

	/**
	 * What a camera image was sampled with: an external texture takes no
	 * mipmap filter, where the old renderer used linear, and is always
	 * clamped.
	 */
	public LegacySamplerSettings forCamera() {
		return new LegacySamplerSettings(
				mipmap == Mipmap.NONE ? min : Filter.LINEAR, Mipmap.NONE,
				mag, Wrap.CLAMP, Wrap.CLAMP, preset);
	}

	private static List<String[]> parameters(String params) {
		List<String[]> parameters = new ArrayList<>();
		if (params == null) {
			return parameters;
		}
		params = params.trim();
		if (params.indexOf(HEADER) != 0) {
			return parameters;
		}
		for (String param : params.substring(3).split(";")) {
			String[] exp = param.split(":");
			if (exp.length == 2) {
				parameters.add(exp);
			}
		}
		return parameters;
	}

	private LegacySamplerSettings with(String name, String value,
			boolean backbuffer) {
		Filter min = this.min;
		Mipmap mipmap = this.mipmap;
		Filter mag = this.mag;
		Wrap wrapS = this.wrapS;
		Wrap wrapT = this.wrapT;
		String preset = this.preset;
		switch (name) {
			case "mag" -> mag = "n".equals(value)
					? Filter.NEAREST
					: Filter.LINEAR;
			case "s" -> wrapS = wrap(value);
			case "t" -> wrapT = wrap(value);
			default -> {
				if (backbuffer && PRESET.equals(name)) {
					preset = value;
					break;
				}
				// The old renderer reads n, l, nn, ln, ll and takes
				// nearest-mipmap-linear for anything else.
				switch (value) {
					case "n", "l" -> mipmap = Mipmap.NONE;
					case "nn", "ln" -> mipmap = Mipmap.NEAREST;
					default -> mipmap = Mipmap.LINEAR;
				}
				min = value.equals("l") || value.equals("ln") ||
						value.equals("ll")
						? Filter.LINEAR
						: Filter.NEAREST;
			}
		}
		return new LegacySamplerSettings(min, mipmap, mag, wrapS, wrapT,
				preset);
	}

	private static Wrap wrap(String value) {
		return switch (value) {
			case "c" -> Wrap.CLAMP;
			case "m" -> Wrap.MIRROR;
			default -> Wrap.REPEAT;
		};
	}

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}
}
