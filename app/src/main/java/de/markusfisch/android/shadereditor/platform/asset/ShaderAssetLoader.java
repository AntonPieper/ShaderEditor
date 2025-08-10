package de.markusfisch.android.shadereditor.platform.asset;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import de.markusfisch.android.shadereditor.engine.asset.AssetLoader;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;

public class ShaderAssetLoader implements AssetLoader<ShaderAsset> {
	@FunctionalInterface
	public interface VertexShaderFactory {
		@NonNull
		String get(@NonNull String fragmentSource);
	}

	@NonNull
	private final VertexShaderFactory vertexShaderFactory;

	public ShaderAssetLoader(@NonNull VertexShaderFactory vertexShaderFactory) {
		this.vertexShaderFactory = vertexShaderFactory;
	}

	@NonNull
	@Override
	public ShaderAsset load(@NonNull InputStream dataStream) throws IOException {
		try (var reader = new BufferedReader(new InputStreamReader(dataStream))) {
			var fragmentSource = reader.lines().collect(Collectors.joining("\n"));
			return new ShaderAsset(
					vertexShaderFactory.get(fragmentSource),
					fragmentSource);
		}
	}
}