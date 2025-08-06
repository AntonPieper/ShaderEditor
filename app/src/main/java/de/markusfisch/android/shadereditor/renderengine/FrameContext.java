package de.markusfisch.android.shadereditor.renderengine;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A per-frame, type-safe cache for sharing data between DataProviders.
 */
public class FrameContext {
    private final Map<ProviderKey<?>, Object> cache = new HashMap<>();
    private final Context androidContext;

    public FrameContext(Context androidContext) {
        this.androidContext = androidContext;
    }

    public <T> void put(@NonNull ProviderKey<T> key, T value) {
        cache.put(key, value);
    }

    /**
     * Safely retrieves a value from the cache using the key's embedded type token for a runtime cast.
     * This prevents ClassCastExceptions at arbitrary assignment locations.
     *
     * @return The value, or null if not found.
     * @throws ClassCastException if the stored value is incompatible with the key's declared type.
     */
    @Nullable
    public <T> T get(@NonNull ProviderKey<T> key) {
        Object value = cache.get(key);
        if (value == null) {
            return null;
        }
        // Use the key's own type token to perform a safe, checked cast.
        return key.type.cast(value);
    }

    public Context getAndroidContext() {
        return androidContext;
    }
}