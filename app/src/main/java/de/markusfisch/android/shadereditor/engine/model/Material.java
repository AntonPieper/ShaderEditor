package de.markusfisch.android.shadereditor.engine.model;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public class Material {
	private final String vertexShaderSource;
	private final String fragmentShaderSource;
	private final Map<String, Object> uniforms;
	private int programId = -1; // Cached by the renderer

	public Material(@NonNull String vertexShaderSource, @NonNull String fragmentShaderSource) {
		this.vertexShaderSource = vertexShaderSource;
		this.fragmentShaderSource = fragmentShaderSource;
		this.uniforms = new HashMap<>();
	}

	public void setUniform(String name, Object value) {
		uniforms.put(name, value);
	}

	public String getVertexShaderSource() {
		return vertexShaderSource;
	}

	public String getFragmentShaderSource() {
		return fragmentShaderSource;
	}

	public Map<String, Object> getUniforms() {
		return uniforms;
	}

	public int getProgramId() {
		return programId;
	}

	public void setProgramId(int programId) {
		this.programId = programId;
	}
}