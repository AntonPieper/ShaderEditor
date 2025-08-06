package de.markusfisch.android.shadereditor.renderengine.gl;

import androidx.annotation.NonNull;

import java.util.List;

import de.markusfisch.android.shadereditor.renderengine.ShaderBinding;

/**
 * A data-only record representing a single rendering pass. It holds the
 * fragment shader source and the list of uniform bindings required for that pass.
 */
public record RenderPass(
		@NonNull String fragmentShaderSource,
		@NonNull List<ShaderBinding<?>> bindings
) {
	// This record is intentionally simple. It could be expanded to include
	// things like a vertex shader source, blend modes, culling settings, etc.
}