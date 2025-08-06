package de.markusfisch.android.shadereditor.engine.data;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * An interface for a provider of a specific piece of data.
 * Implementations of this interface are responsible for managing their own resources,
 * such as registering and unregistering listeners.
 *
 * @param <T> The type of data this provider supplies.
 */
public interface DataProvider<T> {
	/**
	 * @return The unique key this provider is responsible for.
	 */
	@NonNull
	DataKey<T> getKey();

	/**
	 * Called when the provider should start actively listening for data.
	 * This is tied to the host's lifecycle (e.g., onStart).
	 *
	 * @param context An application context.
	 */
	void start(@NonNull Context context);

	/**
	 * Called when the provider should stop listening for data to save resources.
	 * This is tied to the host's lifecycle (e.g., onStop).
	 *
	 * @param context An application context.
	 */
	void stop(@NonNull Context context);

	/**
	 * @return The most recently acquired value.
	 */
	@NonNull
	T getValue();
}