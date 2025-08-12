package de.markusfisch.android.shadereditor.engine.asset;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.net.URI;

public sealed interface AssetRef {
	@NonNull
	@Contract("_ -> new")
	static Alias alias(@NonNull String name) {
		return new Alias(name);
	}

	@NonNull
	@Contract("_ -> new")
	static Location uri(@NonNull String uri) {
		return new Location(URI.create(uri));
	}

	record Alias(@NonNull String name) implements AssetRef {
	}

	record Location(@NonNull URI uri) implements AssetRef {
	}
}
