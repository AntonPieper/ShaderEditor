package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import de.markusfisch.android.shadereditor.engine.GpuObject;

/**
 * A data-driven representation of a piece of geometry.
 * It holds the vertex data but does not manage any GPU resources itself.
 */
public record Geometry(
		@NonNull float[] vertices,
		int vertexCount
) implements GpuObject {

	// A fullscreen quad definition
	private static final float[] QUAD_VERTICES = {
			// X,    Y,   U,   V
			-1.0f, 1.0f, 0.0f, 1.0f, // Top-left
			-1.0f, -1.0f, 0.0f, 0.0f, // Bottom-left
			1.0f, 1.0f, 1.0f, 1.0f, // Top-right
			1.0f, -1.0f, 1.0f, 0.0f  // Bottom-right
	};

	@NonNull
	@Contract(" -> new")
	public static Geometry fullscreenQuad() {
		return new Geometry(QUAD_VERTICES, 4);
	}
}