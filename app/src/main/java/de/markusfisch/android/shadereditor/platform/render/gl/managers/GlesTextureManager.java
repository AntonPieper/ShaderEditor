package de.markusfisch.android.shadereditor.platform.render.gl.managers;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;
import de.markusfisch.android.shadereditor.engine.graphics.TextureInternalFormat;
import de.markusfisch.android.shadereditor.engine.graphics.TextureMagFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureMinFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureWrap;
import de.markusfisch.android.shadereditor.engine.scene.Image2D;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesUtil;

public class GlesTextureManager {
	private final Map<Image2D, Integer> textureCache = new HashMap<>();

	public int getTextureHandle(@NonNull Image2D img) {
		return textureCache.computeIfAbsent(img,
				this::createTextureFor);
	}

	public void destroy() {
		// Delete all textures
		int[] textureHandles = textureCache.values().stream().mapToInt(i -> i).toArray();
		if (textureHandles.length > 0) {
			GLES30.glDeleteTextures(textureHandles.length, textureHandles, 0);
		}
		textureCache.clear();
	}

	private int createTextureFor(@NonNull Image2D img) {
		int[] id = new int[1];
		GLES30.glGenTextures(1, id, 0);
		if (id[0] == 0) throw new RuntimeException("Error creating texture.");

		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);

		final int internal;
		final int width;
		final int height;
		final TextureParameters p;
		final int uploadFormat;
		final int uploadType;

		switch (img) {
			case Image2D.FromAsset fa -> {
				internal = toGL(fa.internalFormat());
				width = fa.asset().width();
				height = fa.asset().height();
				p = fa.sampling();
				uploadFormat = fa.asset().format();
				uploadType = GLES30.GL_UNSIGNED_BYTE;
				GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal,
						width, height, 0, uploadFormat, uploadType, fa.asset().pixels());
				if (requiresMips(p.minFilter()) || p.mipmaps()) {
					GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
				}
			}
			case Image2D.RenderTarget rt -> {
				internal = toGL(rt.internalFormat());
				width = rt.width();
				height = rt.height();
				p = rt.sampling();
				uploadFormat = GLES30.GL_RGBA;
				uploadType = GLES30.GL_UNSIGNED_BYTE;
				GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal,
						width, height, 0, uploadFormat, uploadType, null);
			}
		}

		// Wrap
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, toGL(p.wrapS()));
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, toGL(p.wrapT()));

		// Filters
		GLES30.glTexParameteri(
				GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, toGL(p.minFilter()));
		GLES30.glTexParameteri(
				GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, toGL(p.magFilter()));

		// Anisotropy (extension)
		if (!Float.isNaN(p.anisotropy()) && p.anisotropy() > 1f) {
			setAnisotropy(p.anisotropy());
		}

		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
		GlesUtil.checkErrors("createTextureFor(params)");
		return id[0];
	}

	@Contract(pure = true)
	private int toGL(@NonNull TextureInternalFormat f) {
		return switch (f) {
			case RGBA8 -> GLES30.GL_RGBA8;
			case SRGB8_ALPHA8 -> GLES30.GL_SRGB8_ALPHA8;
		};
	}

	@Contract(pure = true)
	private static boolean requiresMips(@NonNull TextureMinFilter f) {
		return switch (f) {
			case NEAREST_MIPMAP_NEAREST,
				 LINEAR_MIPMAP_NEAREST,
				 NEAREST_MIPMAP_LINEAR,
				 LINEAR_MIPMAP_LINEAR -> true;
			default -> false;
		};
	}

	private static void setAnisotropy(float level) {
		String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
		if (ext != null && ext.contains("GL_EXT_texture_filter_anisotropic")) {
			// 0x84FE = GL_TEXTURE_MAX_ANISOTROPY_EXT, 0x84FF = GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT
			int GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE;
			int GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF;
			float[] max = new float[1];
			GLES30.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, max, 0);
			float clamped = Math.max(1f, Math.min(level, max[0]));
			GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, clamped);
		}
	}

	@Contract(pure = true)
	private static int toGL(@NonNull TextureWrap w) {
		return switch (w) {
			case REPEAT -> GLES30.GL_REPEAT;
			case MIRRORED_REPEAT -> GLES30.GL_MIRRORED_REPEAT;
			case CLAMP_TO_EDGE -> GLES30.GL_CLAMP_TO_EDGE;
		};
	}

	private static int toGL(@NonNull TextureMagFilter f) {
		return (f == TextureMagFilter.NEAREST)
				? GLES30.GL_NEAREST
				: GLES30.GL_LINEAR;
	}

	@Contract(pure = true)
	private static int toGL(@NonNull TextureMinFilter f) {
		return switch (f) {
			case NEAREST -> GLES30.GL_NEAREST;
			case LINEAR -> GLES30.GL_LINEAR;
			case NEAREST_MIPMAP_NEAREST -> GLES30.GL_NEAREST_MIPMAP_NEAREST;
			case LINEAR_MIPMAP_NEAREST -> GLES30.GL_LINEAR_MIPMAP_NEAREST;
			case NEAREST_MIPMAP_LINEAR -> GLES30.GL_NEAREST_MIPMAP_LINEAR;
			case LINEAR_MIPMAP_LINEAR -> GLES30.GL_LINEAR_MIPMAP_LINEAR;
		};
	}
}