package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ResourceStreamProvider {
	@NonNull
	InputStream openStream(@NonNull String identifier) throws IOException;
}