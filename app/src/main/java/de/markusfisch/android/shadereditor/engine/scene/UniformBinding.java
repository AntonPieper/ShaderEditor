package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataKey;

/**
 * A pure-data record that declaratively defines a single binding between a
 * uniform name, a DataProvider class, and a function to map the data to a Uniform.
 *
 * @param <T> The type of the data from the provider.
 */
public record UniformBinding<T>(
		@NonNull String uniformName,
		@NonNull DataKey<T> key,
		@NonNull Mapper<T> mapper
) {
	@FunctionalInterface
	public interface Mapper<T> {
		@NonNull
		Uniform map(@NonNull T data);
	}
}