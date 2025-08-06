package de.markusfisch.android.shadereditor.engine.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages all DataProvider instances. It is now fully type-safe.
 */
public class DataProviderManager {
	private static final String TAG = "DataProviderManager";
	private final Map<DataKey<?>, DataProvider<?>> providers = new HashMap<>();
	@NonNull
	private final Context context;

	public DataProviderManager(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	/**
	 * Registers a new provider with the manager.
	 * This method is atomic and thread-safe.
	 *
	 * @param provider The DataProvider to register.
	 * @throws IllegalArgumentException if a provider for the given key is already registered.
	 */
	public void registerProvider(@NonNull DataProvider<?> provider) {
		if (providers.putIfAbsent(provider.getKey(), provider) != null) {
			throw new IllegalArgumentException(
					"DataProvider for key " + provider.getKey() + " is already registered.");
		}
	}

	/**
	 * Starts all registered providers. Typically called when the UI becomes visible.
	 */
	public void onStart() {
		for (var provider : providers.values()) {
			provider.start(context);
		}
	}

	/**
	 * Stops all registered providers. Typically called when the UI is no longer visible.
	 */
	public void onStop() {
		for (var provider : providers.values()) {
			provider.stop(context);
		}
	}

	/**
	 * Retrieves the current value for a given key in a fully type-safe manner.
	 *
	 * @param key The key for the desired data.
	 * @param <T> The expected type of the data.
	 * @return The data value, or null if the provider is not registered or a type mismatch occurs.
	 */
	@Nullable
	public <T> T getData(@NonNull DataKey<T> key) {
		DataProvider<?> provider = providers.get(key);
		if (provider == null) {
			Log.w(TAG, "No DataProvider registered for key: " + key);
			return null;
		}

		Object value = provider.getValue();
		try {
			// We ask the key to perform a checked cast using its internal Class<T> token.
			// This is guaranteed to be safe at runtime.
			return key.cast(value);
		} catch (ClassCastException e) {
			// This block will now execute if a provider is misconfigured
			// (i.e., it returns a type different from what its key declares).
			// This protects the rest of the engine from the error.
			Log.e(TAG,
					"FATAL: Data provider for key '" + key + "' returned an unexpected type.", e);
			return null;
		}
	}

	/**
	 * Stops all providers and removes them from the manager, resetting its state.
	 */
	public void reset() {
		onStop();
		providers.clear();
	}
}