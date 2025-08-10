package de.markusfisch.android.shadereditor.engine.util.observer;

@FunctionalInterface
public interface Observer<T> {
	void update(T value);
}
