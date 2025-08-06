package de.markusfisch.android.shadereditor.renderengine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A unique, type-safe identifier for a piece of data.
 * The generic <T> ensures type safety at compile time.
 *
 * @param <T> The data type this key represents (e.g., float[], Integer).
 */
public final class ProviderKey<T> {
    public final Class<T> type;
    public final UniformType<T> uniformType; // The generic types must match!
    private final String debugName;

    public ProviderKey(@NonNull String debugName, @NonNull Class<T> type, @Nullable UniformType<T> uniformType) {
        this.debugName = debugName;
        this.type = type;
        this.uniformType = uniformType;
    }

    public ProviderKey(@NonNull String debugName, @NonNull Class<T> type) {
        this(debugName, type, null);
    }

    @Override
    public String toString() {
        return "ProviderKey(" + debugName + ")";
    }
}