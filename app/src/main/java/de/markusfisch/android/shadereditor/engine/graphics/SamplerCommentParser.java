package de.markusfisch.android.shadereditor.engine.graphics;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.markusfisch.android.shadereditor.engine.asset.AssetRef;
import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;

public final class SamplerCommentParser {

	private static final String UNIFORM = "uniform\\s+";
	private static final String PRECISION = "(?:lowp|mediump|highp\\s+)?";
	private static final String SAMPLER_TYPE = "sampler\\w+\\s+";
	private static final String SAMPLER_NAME = "(\\w+)\\s*;";
	private static final String COMMENT_PART = "(?:\\s*///\\s*([^\\n\\r]*))?";
	private static final Pattern pat =
			Pattern.compile(UNIFORM + PRECISION + SAMPLER_TYPE + SAMPLER_NAME + COMMENT_PART);
	private static final Pattern SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

	@NonNull
	public static Map<String, SamplerBinding> parse(
			@NonNull String glslSource,
			@NonNull Set<String> allowedNames) {
		var out = new HashMap<String, SamplerBinding>();
		// very simple find of "uniform sampler" lines; refine as you like
		var m = pat.matcher(glslSource);
		while (m.find()) {
			var name = Objects.requireNonNull(m.group(1));
			if (!allowedNames.contains(name)) {
				continue;
			}
			var comment = m.group(2);
			Map<String, String> kv = (comment == null || comment.isBlank())
					? Collections.emptyMap()
					: Arrays.stream(comment.split(";"))
					.map(String::trim).filter(s -> !s.isEmpty())
					.map(p -> p.split(":", 2))
					.filter(a -> a.length == 2)
					.collect(Collectors.toMap(a -> a[0], a -> a[1]));

			var params = TextureParameters.DEFAULT;
			var min = kv.get("min");
			var mag = kv.get("mag");
			var s = kv.get("s");
			var t = kv.get("t");
			var mip = kv.get("mip");
			var an = kv.get("aniso");
			var srgb = kv.get("srgb");

			params = new TextureParameters(
					min != null ? parseMinFilter(min) : params.minFilter(),
					mag != null ? parseMagFilter(mag) : params.magFilter(),
					s != null ? parseWrap(s) : params.wrapS(),
					t != null ? parseWrap(t) : params.wrapT(),
					mip != null ? isTrue(mip) : params.mipmaps(),
					an != null ? Float.parseFloat(an) : params.anisotropy(),
					srgb != null ? isTrue(srgb) : params.sRGB()
			);

			var src = kv.get("src");
			if (src != null && SCHEME_PATTERN.matcher(src).matches()) {
				out.put(name, new SamplerBinding(AssetRef.uri(src), params));
			} else {
				out.put(
						name,
						new SamplerBinding(AssetRef.alias(src == null ? name : src), params));
			}
		}
		return out;
	}

	private static boolean isTrue(@NonNull String value) {
		return value.equals("1")
				|| value.equalsIgnoreCase("on")
				|| value.equalsIgnoreCase("true");
	}

	// maps short codes to enums
	@Contract(pure = true)
	private static TextureMinFilter parseMinFilter(@NonNull String v) {
		return switch (v) {
			case "n" -> TextureMinFilter.NEAREST;
			case "l" -> TextureMinFilter.LINEAR;
			case "nn" -> TextureMinFilter.NEAREST_MIPMAP_NEAREST;
			case "ln" -> TextureMinFilter.LINEAR_MIPMAP_NEAREST;
			case "nl" -> TextureMinFilter.NEAREST_MIPMAP_LINEAR;
			case "ll" -> TextureMinFilter.LINEAR_MIPMAP_LINEAR;
			default -> TextureMinFilter.LINEAR;
		};
	}

	@Contract(pure = true)
	private static TextureMagFilter parseMagFilter(@NonNull String v) {
		return switch (v) {
			case "n" -> TextureMagFilter.NEAREST;
			case "l" -> TextureMagFilter.LINEAR;
			default -> TextureMagFilter.LINEAR;
		};
	}

	@Contract(pure = true)
	private static TextureWrap parseWrap(@NonNull String v) {
		return switch (v) {
			case "r" -> TextureWrap.REPEAT;
			case "c" -> TextureWrap.CLAMP_TO_EDGE;
			case "m" -> TextureWrap.MIRRORED_REPEAT;
			default -> TextureWrap.CLAMP_TO_EDGE;
		};
	}

	public record SamplerBinding(
			@NonNull AssetRef assetIdentifier,
			@NonNull TextureParameters parameters) {
	}
}