package de.markusfisch.android.shadereditor.renderengine;

import androidx.annotation.NonNull;

/**
 * A generic, functional interface that represents the strategy for applying
 * a specific type of GLSL uniform. It encapsulates the required glUniform* call.
 *
 * @param <T> The Java data type this applicator can handle (e.g., Float, float[]).
 */
@FunctionalInterface
public interface UniformType<T> {
	/**
	 * Creates a command to apply a uniform.
	 *
	 * @param location The uniform's location in the shader program.
	 * @param value    The strongly-typed value to be applied.
	 * @return A {@link UniformCommand} that can be executed by the renderer.
	 */
	@NonNull
	UniformCommand createCommand(int location, @NonNull T value);
}