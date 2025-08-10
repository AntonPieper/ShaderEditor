package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;

public record Material(@NonNull ShaderAsset shader, @NonNull Map<String, Uniform> uniforms) {

	public Material(@NonNull ShaderAsset shader) {
		this(shader, new HashMap<>());
	}

	public void setUniform(@NonNull String name, @NonNull Uniform value) {
		uniforms.put(name, value);
	}
}