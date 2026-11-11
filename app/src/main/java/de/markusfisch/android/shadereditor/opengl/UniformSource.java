package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

interface UniformSource {
	interface Key<S extends UniformSource> {
		@NonNull
		S create();

		@NonNull
		@SuppressWarnings("unchecked")
		default S cast(@NonNull UniformSource source) {
			return (S) source;
		}
	}

	void start(@NonNull EngineHost host, @NonNull UniformSources sources);

	void stop();

	default void update(long now) {
	}
}
