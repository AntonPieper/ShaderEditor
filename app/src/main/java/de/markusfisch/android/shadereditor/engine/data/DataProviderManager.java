package de.markusfisch.android.shadereditor.engine.data;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the entire lifecycle of all data providers for the engine.
 * <p>
 * This class stores factories for providers and lazily instantiates them
 * on their first use. It provides a single, unified, type-safe query interface.
 */
public class DataProviderManager {
	private static final String TAG = "DataProviderManager";
	private final Map<DataKey<?>, DataProvider.Factory<?>> factories =
			new HashMap<>();
	private final Map<DataKey<?>, DataProvider<?>> activeProviders =
			new HashMap<>();

	/**
	 * Registers a factory for a DataProvider. The provider's class is used as the key.
	 * This is a cheap operation that does not create the provider instance.
	 */
	public <T> void register(
			@NonNull DataKey<T> key, @NonNull DataProvider.Factory<T> factory) {
		if (factories.putIfAbsent(key, factory) != null) {
			Log.w(TAG, "Factory for provider " + key.name() + " is already registered.");
		}
	}

	/**
	 * Retrieves data from a provider, activating it if necessary.
	 * This is the primary, type-safe method for any part of the engine to query data.
	 *
	 * @param key The class of the provider to get data from.
	 * @return The data value, or null if the provider is not registered.
	 */
	@Nullable
	public <T> T getData(@NonNull DataKey<T> key) {
		DataProvider<?> provider = activeProviders.get(key);
		if (provider == null) {
			provider = activateProvider(key);
			if (provider == null) return null;
		}
		// The cast is safe due to the generic constraints of this class.
		return key.cast(provider.getValue());
	}

	public void stopActiveProviders() {
		for (DataProvider<?> provider : activeProviders.values()) {
			provider.stop();
		}
		activeProviders.clear();
	}

	@Nullable
	private DataProvider<?> activateProvider(@NonNull DataKey<?> key) {
		var factory = factories.get(key);
		if (factory == null) {
			Log.w(TAG, "No factory registered for provider: " + key.name());
			return null;
		}
		Log.d(TAG, "Lazily activating provider: " + key.name());
		DataProvider<?> newProvider = factory.create();
		newProvider.start();
		activeProviders.put(key, newProvider);
		return newProvider;
	}
}