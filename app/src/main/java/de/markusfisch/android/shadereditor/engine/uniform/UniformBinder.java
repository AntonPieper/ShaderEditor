package de.markusfisch.android.shadereditor.engine.uniform;

@FunctionalInterface
public interface UniformBinder<T> {
	void bind(int location, T value);
}
