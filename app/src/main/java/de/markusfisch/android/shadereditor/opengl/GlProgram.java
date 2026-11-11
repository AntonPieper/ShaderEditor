package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class GlProgram {
	private int id;
	private Map<String, GlUniform> uniforms;
	private final Map<String, Integer> attribLocations = new HashMap<>();

	GlProgram(int id, @NonNull Map<String, GlUniform> uniforms) {
		this.id = id;
		this.uniforms = Collections.unmodifiableMap(new HashMap<>(uniforms));
	}

	int getId() {
		return id;
	}

	boolean isValid() {
		return id != 0;
	}

	@NonNull
	Map<String, GlUniform> getUniforms() {
		return uniforms;
	}

	Map<String, Integer> getAttribLocations() {
		return attribLocations;
	}

	void invalidate() {
		id = 0;
		uniforms = Collections.emptyMap();
		attribLocations.clear();
	}
}
