package de.markusfisch.android.shadereditor.engine.asset;

import androidx.annotation.NonNull;

public record ShaderAsset(
		@NonNull String vertexSource,
		@NonNull String fragmentSource
) implements Asset {
}
