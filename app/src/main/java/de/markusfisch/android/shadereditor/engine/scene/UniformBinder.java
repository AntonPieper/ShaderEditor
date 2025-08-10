package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.data.DataKey;

/**
 * A utility that applies a list of declarative bindings to a material.
 */
public class UniformBinder {
	private final List<UniformBinding<?>> bindings;

	public UniformBinder(@NonNull List<UniformBinding<?>> bindings) {
		this.bindings = bindings;
	}

	@NonNull
	@Contract(" -> new")
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Applies the configured bindings.
	 * For each uniform that is active in the shader, it finds the corresponding
	 * binding, gets the data from the DataProviderManager (activating the
	 * provider if necessary), and sets the uniform on the material.
	 */
	public void apply(
			@NonNull Engine engine,
			@NonNull Material material,
			@NonNull Set<String> activeUniformNames) {
		for (UniformBinding<?> binding : bindings) {
			if (activeUniformNames.contains(binding.uniformName())) {
				Object rawValue = engine.getData(binding.key());
				if (rawValue != null) {
					applyValue(material, binding, rawValue);
				}
			}
		}
	}

	private <T> void applyValue(
			@NonNull Material material, @NonNull UniformBinding<T> binding, Object rawValue) {
		// This cast is safe due to the chain of type guarantees.
		T typedValue = binding.key().cast(rawValue);
		material.setUniform(binding.uniformName(), binding.mapper().map(typedValue));
	}

	public static class Builder {
		private List<UniformBinding<?>> bindings = new ArrayList<>();

		public UniformBinder build() {
			var snapshot = Collections.unmodifiableList(bindings);
			bindings = new ArrayList<>();
			return new UniformBinder(snapshot);
		}

		@NonNull
		public <T> Builder bind(
				@NonNull String uniformName,
				@NonNull DataKey<T> key,
				@NonNull UniformBinding.Mapper<T> mapper) {
			bindings.add(new UniformBinding<>(uniformName, key, mapper));
			return this;
		}
	}
}