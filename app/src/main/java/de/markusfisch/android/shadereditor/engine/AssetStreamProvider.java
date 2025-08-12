package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

@FunctionalInterface
public interface AssetStreamProvider {
	@NonNull
	InputStream openStream(@NonNull URI identifier) throws IOException;

}