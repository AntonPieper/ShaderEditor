package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

/**
 * An interface for a provider of a specific piece of data.
 * Implementations of this interface are responsible for managing their own resources,
 * such as registering and unregistering listeners.
 *
 * @param <T> The type of data this provider supplies.
 */
public interface DataProvider<T> {

	@FunctionalInterface
	interface Factory<T> {
		@NonNull
		DataProvider<T> create();
	}

	/**
	 * Called when the provider should start actively listening for data.
	 */
	void start();

	/**
	 * Called when the provider should stop listening for data to save resources.
	 */
	void stop();

	/**
	 * @return The most recently acquired value.
	 */
	@NonNull
	T getValue();
}