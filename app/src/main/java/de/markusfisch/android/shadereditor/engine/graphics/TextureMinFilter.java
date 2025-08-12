package de.markusfisch.android.shadereditor.engine.graphics;

/**
 * Defines platform-agnostic graphics constants, mirroring OpenGL enums.
 */
public enum TextureMinFilter {
	NEAREST,
	LINEAR,
	NEAREST_MIPMAP_NEAREST,
	LINEAR_MIPMAP_NEAREST,
	NEAREST_MIPMAP_LINEAR,
	LINEAR_MIPMAP_LINEAR,
}
