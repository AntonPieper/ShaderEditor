package de.markusfisch.android.shadereditor.renderengine;

import androidx.annotation.NonNull;

/**
 * A generic record that connects a uniform name in a shader to a specific,
 * typed {@link ProviderKey}.
 *
 * @param <T> The type of data this binding represents.
 */
public record ShaderBinding<T>(@NonNull String uniformName, @NonNull ProviderKey<T> key) {
}
