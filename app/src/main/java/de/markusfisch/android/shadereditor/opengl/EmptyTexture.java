package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.Nullable;

// The texture an active sampler that nothing provides reads, one of the
// kind its type samples, so that it reads zero: opaque black for a float
// sampler as always, 0 for an integer one, and for a shadow sampler a
// depth texture whose comparison never passes.
enum EmptyTexture {
	FLAT(GLES20.GL_TEXTURE_2D, GLES20.GL_RGBA, GLES20.GL_RGBA,
			GLES20.GL_UNSIGNED_BYTE, false, 2),
	CUBE(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_RGBA, GLES20.GL_RGBA,
			GLES20.GL_UNSIGNED_BYTE, false, 2),
	// Without an image, an external texture samples as opaque black.
	EXTERNAL(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, 0, 0, false, 2),
	VOLUME(GLES30.GL_TEXTURE_3D, GLES30.GL_RGBA8, GLES20.GL_RGBA,
			GLES20.GL_UNSIGNED_BYTE, false, 3),
	ARRAY(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_RGBA8, GLES20.GL_RGBA,
			GLES20.GL_UNSIGNED_BYTE, false, 3),
	INT_FLAT(GLES20.GL_TEXTURE_2D, GLES30.GL_RGBA8I,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_BYTE, false, 3),
	INT_CUBE(GLES20.GL_TEXTURE_CUBE_MAP, GLES30.GL_RGBA8I,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_BYTE, false, 3),
	INT_VOLUME(GLES30.GL_TEXTURE_3D, GLES30.GL_RGBA8I,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_BYTE, false, 3),
	INT_ARRAY(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_RGBA8I,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_BYTE, false, 3),
	UINT_FLAT(GLES20.GL_TEXTURE_2D, GLES30.GL_RGBA8UI,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_UNSIGNED_BYTE, false, 3),
	UINT_CUBE(GLES20.GL_TEXTURE_CUBE_MAP, GLES30.GL_RGBA8UI,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_UNSIGNED_BYTE, false, 3),
	UINT_VOLUME(GLES30.GL_TEXTURE_3D, GLES30.GL_RGBA8UI,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_UNSIGNED_BYTE, false, 3),
	UINT_ARRAY(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_RGBA8UI,
			GLES30.GL_RGBA_INTEGER, GLES20.GL_UNSIGNED_BYTE, false, 3),
	SHADOW_FLAT(GLES20.GL_TEXTURE_2D, GLES30.GL_DEPTH_COMPONENT16,
			GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_SHORT, true, 3),
	SHADOW_CUBE(GLES20.GL_TEXTURE_CUBE_MAP, GLES30.GL_DEPTH_COMPONENT16,
			GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_SHORT, true, 3),
	SHADOW_ARRAY(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_DEPTH_COMPONENT16,
			GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_SHORT, true, 3);

	final int target;
	final int internalFormat;
	final int format;
	final int type;
	// Whether it is a depth texture compared with GL_NEVER.
	final boolean compares;
	// The OpenGL ES version that has it.
	final int glesVersion;

	EmptyTexture(int target, int internalFormat, int format, int type,
			boolean compares, int glesVersion) {
		this.target = target;
		this.internalFormat = internalFormat;
		this.format = format;
		this.type = type;
		this.compares = compares;
		this.glesVersion = glesVersion;
	}

	// The empty texture of a sampler type, or null for a type that is no
	// sampler.
	@Nullable
	static EmptyTexture of(int samplerType) {
		return switch (samplerType) {
			case GLES20.GL_SAMPLER_2D -> FLAT;
			case GLES20.GL_SAMPLER_CUBE -> CUBE;
			case GLES11Ext.GL_SAMPLER_EXTERNAL_OES -> EXTERNAL;
			case GLES30.GL_SAMPLER_3D -> VOLUME;
			case GLES30.GL_SAMPLER_2D_ARRAY -> ARRAY;
			case GLES30.GL_INT_SAMPLER_2D -> INT_FLAT;
			case GLES30.GL_INT_SAMPLER_CUBE -> INT_CUBE;
			case GLES30.GL_INT_SAMPLER_3D -> INT_VOLUME;
			case GLES30.GL_INT_SAMPLER_2D_ARRAY -> INT_ARRAY;
			case GLES30.GL_UNSIGNED_INT_SAMPLER_2D -> UINT_FLAT;
			case GLES30.GL_UNSIGNED_INT_SAMPLER_CUBE -> UINT_CUBE;
			case GLES30.GL_UNSIGNED_INT_SAMPLER_3D -> UINT_VOLUME;
			case GLES30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY -> UINT_ARRAY;
			case GLES30.GL_SAMPLER_2D_SHADOW -> SHADOW_FLAT;
			case GLES30.GL_SAMPLER_CUBE_SHADOW -> SHADOW_CUBE;
			case GLES30.GL_SAMPLER_2D_ARRAY_SHADOW -> SHADOW_ARRAY;
			default -> null;
		};
	}
}
