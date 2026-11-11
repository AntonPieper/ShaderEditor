package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

final class GlProgram {
	private int id;
	private Map<String, GlUniform> uniforms;
	private List<GlUniform> samplers;
	private int textureUnitCount;
	private final Map<String, Integer> attribLocations = new HashMap<>();

	// Gives each element of each active sampler its own texture unit,
	// in name order.
	GlProgram(int id, @NonNull List<GlUniform> activeUniforms) {
		this.id = id;
		var samplers = new ArrayList<GlUniform>();
		for (var uniform : activeUniforms) {
			if (uniform.isSampler() && uniform.location() > -1) {
				samplers.add(uniform);
			}
		}
		Collections.sort(samplers, (a, b) -> a.name().compareTo(b.name()));
		int unit = 0;
		for (int i = 0; i < samplers.size(); ++i) {
			var sampler = samplers.get(i);
			samplers.set(i, sampler.withUnit(unit));
			unit += sampler.size();
		}
		textureUnitCount = unit;
		var uniforms = new HashMap<String, GlUniform>();
		for (var uniform : activeUniforms) {
			uniforms.put(uniform.name(), uniform);
		}
		for (var sampler : samplers) {
			uniforms.put(sampler.name(), sampler);
		}
		for (var uniform : List.copyOf(uniforms.values())) {
			String name = uniform.name();
			if (name.endsWith("[0]")) {
				uniforms.put(name.substring(0, name.length() - 3), uniform);
			}
		}
		this.uniforms = Collections.unmodifiableMap(uniforms);
		this.samplers = List.copyOf(samplers);
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

	// Active samplers, in unit order.
	@NonNull
	List<GlUniform> getSamplers() {
		return samplers;
	}

	@Nullable
	ProjectDiagnostic checkTextureUnits(int maxTextureUnits) {
		if (textureUnitCount <= maxTextureUnits) {
			return null;
		}
		return ProjectDiagnostic.error("The shader needs " + textureUnitCount +
				" texture units, but this device has only " + maxTextureUnits);
	}

	int getTextureUnit(@NonNull String name) {
		GlUniform uniform = uniforms.get(name);
		return uniform != null ? uniform.unit() : GlUniform.NO_UNIT;
	}

	Map<String, Integer> getAttribLocations() {
		return attribLocations;
	}

	void invalidate() {
		id = 0;
		uniforms = Collections.emptyMap();
		samplers = List.of();
		textureUnitCount = 0;
		attribLocations.clear();
	}
}
