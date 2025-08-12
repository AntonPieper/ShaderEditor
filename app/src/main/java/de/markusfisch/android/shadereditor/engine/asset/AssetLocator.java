// engine/asset/AssetLocator.java
package de.markusfisch.android.shadereditor.engine.asset;

import androidx.annotation.NonNull;

import java.net.URI;

public interface AssetLocator {
	@NonNull
	AssetLocator IDENTITY = (logicalName, type) -> URI.create(logicalName);

	@NonNull
	URI identify(@NonNull String logicalName,
			@NonNull Class<? extends Asset> type);
}