package de.markusfisch.android.shadereditor.engine.model;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public record Material(@NonNull Shader shader, @NonNull Map<String, Uniform> uniforms) {

	public Material(@NonNull String vertexShaderSource, @NonNull String fragmentShaderSource) {
		this(new Shader(vertexShaderSource, fragmentShaderSource), new HashMap<>());
	}

	public void setUniform(@NonNull String name, @NonNull Uniform value) {
		uniforms.put(name, value);
	}
}