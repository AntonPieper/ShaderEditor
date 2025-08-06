package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.Objects;

/**
 * A type-safe, unique key for requesting a piece of data.
 * <p>
 * This key is now fundamentally safe. It pairs a unique name with a Class token for its
 * generic type. This allows for runtime-checked casting via {@link Class#cast(Object)},
 * preventing ClassCastExceptions within the engine's data system.
 *
 * @param <T> The type of the data this key represents.
 */
public final class DataKey<T> {
	@NonNull
	private final String name;
	@NonNull
	private final Class<T> type;

	/**
	 * Private constructor to guide creation through the static factory method.
	 */
	private DataKey(@NonNull String name, @NonNull Class<T> type) {
		this.name = Objects.requireNonNull(name);
		this.type = Objects.requireNonNull(type);
	}

	/**
	 * Creates a new, unique and type-safe key. Use namespacing to avoid collisions.
	 *
	 * @param name A unique string identifier for this key (e.g., "engine.time").
	 * @param type The .class token for the key's type (e.g., Float.class).
	 */
	@NonNull
	@Contract("_, _ -> new")
	public static <T> DataKey<T> create(@NonNull String name, @NonNull Class<T> type) {
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

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		DataKey<?> dataKey = (DataKey<?>) o;
		// Two keys are only equal if BOTH their name and type match.
		return name.equals(dataKey.name) && type.equals(dataKey.type);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, type);
	}

	@NonNull
	@Override
	public String toString() {
		return "DataKey{name='" + name + "', type=" + type.getSimpleName() + "}";
	}
}