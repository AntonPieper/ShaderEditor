package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

/**
 * A type-safe, unique key for requesting a piece of data.
 * <p>
 * This key is now fundamentally safe. It pairs a unique name with a Class token for its
 * generic type. This allows for runtime-checked casting via {@link Class#cast(Object)},
 * preventing ClassCastExceptions within the engine's data system.
 *
 * @param <T> The type of the data this key represents.
 */
public record DataKey<T>(@NonNull String name, @NonNull Class<T> type) {

	@NonNull
	@Contract("_, _ -> new")
	public static <T> DataKey<T> of(@NonNull String name, @NonNull Class<T> type) {
		return new DataKey<>(name, type);
	}

	/**
	 * Safely casts an object to the type represented by this key.
	 *
	 * @param obj The object to cast.
	 * @return The casted object.
	 * @throws ClassCastException if the object is not of the expected type.
	 */
	@NonNull
	@SuppressWarnings("DataFlowIssue")
	public T cast(@NonNull Object obj) {
		return type.cast(obj);
	}
}