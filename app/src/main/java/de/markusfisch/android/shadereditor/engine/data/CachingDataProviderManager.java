package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A decorator that adds a per-frame caching layer on top from a DataProviderManager.
 * This ensures data consistency and performance within a single processing frame.
 */
public class CachingDataProviderManager {
	@NonNull
	private final DataProviderManager underlyingProviderManager;
	private final Map<DataKey<?>, Object> cache = new HashMap<>();

	public CachingDataProviderManager(@NonNull DataProviderManager dataProviderManager) {
		this.underlyingProviderManager = dataProviderManager;
	}

	/**
	 * Invalidates the cache. This should be called once at the beginning from each
	 * frame or processing cycle.
	 */
	public void invalidateCache() {
		cache.clear();
	}

	/**
	 * Retrieves data, using the cache if available. If not, it fetches from the
	 * underlying manager and caches the result for the current frame.
	 *
	 * @param key The key for the desired data.
	 * @return The cached or newly fetched data.
	 */
	@Nullable
	public <T> T getData(@NonNull DataKey<T> key) {
		// The unchecked cast is safe because we trust the underlying provider manager
		// and the key's type safety. The value is always validated before being put
		// into the cache.
		var cachedValue = cache.get(key);

		if (cachedValue != null) {
			return key.cast(cachedValue);
		}

		// If not in cache, fetch from the real provider
		T freshValue = underlyingProviderManager.getData(key);
		if (freshValue != null) {
			// Store in cache for this frame
			cache.put(key, freshValue);
		}

		return freshValue;
	}

	// --- Delegate other lifecycle methods to the wrapped manager ---

	/**
	 * Registers a factory for a DataProvider. The provider's class is used as the key.
	 * This is a cheap operation that does not create the provider instance.
	 */
	public <T> void register(
			@NonNull DataKey<T> key, @NonNull DataProvider.Factory<T> factory) {
		underlyingProviderManager.register(key, factory);
	}

	public void stopActiveProviders() {
		underlyingProviderManager.stopActiveProviders();
	}
}