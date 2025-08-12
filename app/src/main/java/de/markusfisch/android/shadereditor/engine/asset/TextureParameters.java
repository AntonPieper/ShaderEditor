package de.markusfisch.android.shadereditor.engine.asset;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.graphics.TextureMagFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureMinFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureWrap;

/**
 * A platform-agnostic representation of texture sampling parameters,
 * parsed from shader comments.
 *
 * @param minFilter  The minification filter.
 * @param magFilter  The magnification filter.
 * @param wrapS      The wrap mode for the S coordinate.
 * @param wrapT      The wrap mode for the T coordinate.
 * @param mipmaps    Whether to generate mipmaps.
 * @param anisotropy The anisotropic filtering level. NaN means default.
 * @param sRGB       Whether the texture is in sRGB color space.
 */
public record TextureParameters(
		@NonNull TextureMinFilter minFilter,
		@NonNull TextureMagFilter magFilter,
		@NonNull TextureWrap wrapS,
		@NonNull TextureWrap wrapT,
		boolean mipmaps,
		float anisotropy,
		boolean sRGB
) {
	@NonNull
	public static final TextureParameters DEFAULT = new TextureParameters(
			TextureMinFilter.LINEAR,
			TextureMagFilter.LINEAR,
			TextureWrap.CLAMP_TO_EDGE,
			TextureWrap.CLAMP_TO_EDGE,
			false,
			Float.NaN,
			false
	);
}