package de.markusfisch.android.shadereditor.engine;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.uniform.UniformBinder;

public class UniformBag {
	private final Map<String, UniformValue<?>> values = new HashMap<>();
	private final Map<String, Integer> locations = new HashMap<>();

	public <T> UniformBag put(String name, T object, UniformBinder<T> binder) {
		values.put(name, new UniformValue<>(object, binder));
		return this;
	}

	public void bind(int program) {
		for (var entry : values.entrySet()) {
			entry.getValue().bind(locate(program, entry.getKey()));
		}
	}

	private int locate(int program, String name) {
		return locations.computeIfAbsent(name, n -> GLES20.glGetUniformLocation(program, name));
	}

	@NonNull
	public UniformBag merge(@NonNull UniformBag other) {
		values.putAll(other.values);
		locations.putAll(other.locations);
		return this;
	}

	@NonNull
	public UniformBag clear() {
		values.clear();
		locations.clear();
		return this;
	}

	private record UniformValue<T>(T object, UniformBinder<T> binder) {
		public void bind(int location) {
			binder.bind(location, object);
		}
	}

}
