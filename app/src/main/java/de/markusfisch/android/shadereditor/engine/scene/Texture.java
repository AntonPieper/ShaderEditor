package de.markusfisch.android.shadereditor.engine.scene;

import de.markusfisch.android.shadereditor.engine.GpuObject;

/**
 * A data-driven description of a 2D texture.
 * It does not hold any GPU-specific handles.
 */
public record Texture(
		int width,
		int height,
		int internalFormat // e.g., GLES32.GL_RGBA
) implements GpuObject {
}