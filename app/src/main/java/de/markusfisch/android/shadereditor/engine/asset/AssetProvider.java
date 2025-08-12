package de.markusfisch.android.shadereditor.engine.asset;

// --- In your core engine module ---

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import de.markusfisch.android.shadereditor.engine.AssetStreamProvider;

public class AssetProvider {
	// The provider that gives us raw byte streams (from files, editor, etc.)
	@NonNull
	private final AssetStreamProvider assetStreamOpener;
	// The registry from workers
	private final Map<Class<? extends Asset>, AssetLoader<?>> loaders = new HashMap<>();
	// The cache from already-loaded assets
	private final Map<URI, Asset> cache = new HashMap<>();
	@NonNull
	private AssetLocator locator = AssetLocator.IDENTITY;

	public AssetProvider(@NonNull AssetStreamProvider assetStreamOpener) {
		this.assetStreamOpener = assetStreamOpener;
	}

	public <T extends Asset> void registerLoader(
			@NonNull Class<T> type, @NonNull AssetLoader<T> loader) {
		loaders.put(type, loader);
	}

	public void setLocator(@NonNull AssetLocator locator) {
		this.locator = locator;
	}

	@NonNull
	public <T extends Asset> T load(@NonNull AssetRef ref, @NonNull Class<T> type) {
		URI identifier = switch (ref) {
			case AssetRef.Location(var id) -> id;
			case AssetRef.Alias(var name) -> locator.identify(name, type);
		};
		// 1. Check cache first
		if (cache.containsKey(identifier)) {
			// Return from cache, ensuring type safety with a cast.
			return Objects.requireNonNull(type.cast(cache.get(identifier)));
		}

		// 2. Find the correct loader for the requested type
		AssetLoader<?> loader = loaders.get(type);
		if (loader == null) {
			throw new IllegalStateException("No loader registered for type: " + type.getName());
		}

		// 3. Get the raw data stream and load the asset
		try (InputStream dataStream = assetStreamOpener.openStream(identifier)) {
			Asset loadedAsset = loader.load(dataStream);

			// 4. Cache and return the result
			cache.put(identifier, loadedAsset);
			return Objects.requireNonNull(type.cast(loadedAsset));

		} catch (IOException e) {
			throw new RuntimeException("Failed to load asset: " + identifier, e);
		}
	}
}