package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class GlProgram {
	private int id;
	private Map<String, GlUniform> uniforms;
	private List<GlUniform> samplers;
	private final Map<String, Integer> attribLocations = new HashMap<>();

	// Gives each active sampler its own texture unit, in name order,
	// until the units run out.
	GlProgram(int id, @NonNull List<GlUniform> activeUniforms, int maxTextureUnits) {
		this.id = id;
		var samplers = new ArrayList<GlUniform>();
		for (var uniform : activeUniforms) {
			if (uniform.isSampler() && uniform.location() > -1) {
				samplers.add(uniform);
			}
		}
		Collections.sort(samplers, (a, b) -> a.name().compareTo(b.name()));
		int units = Math.min(samplers.size(), maxTextureUnits);
		for (int unit = 0; unit < units; ++unit) {
			samplers.set(unit, samplers.get(unit).withUnit(unit));
		}
		samplers.subList(units, samplers.size()).clear();
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

	// Samplers that got a texture unit, in unit order.
	@NonNull
	List<GlUniform> getSamplers() {
		return samplers;
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
		attribLocations.clear();
	}
}
