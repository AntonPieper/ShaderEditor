package de.markusfisch.android.shadereditor.engine.asset;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

/**
 * A worker that knows how to load a specific asset type 'T' from a stream.
 *
 * @param <T> The type from Asset this loader produces.
 */
public interface AssetLoader<T extends Asset> {
	@NonNull
	T load(@NonNull InputStream dataStream) throws IOException;
}