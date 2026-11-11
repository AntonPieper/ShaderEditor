package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

final class UniformSources {
	private final Map<UniformSource.Key<?>, UniformSource> byKey = new HashMap<>();
	private final ArrayList<UniformSource> running = new ArrayList<>();
	@NonNull
	private final EngineHost host;

	UniformSources(@NonNull EngineHost host) {
		this.host = host;
	}

	@NonNull
	UniformSource start(@NonNull UniformSource.Key<?> key) {
		UniformSource source = byKey.get(key);
		if (source == null) {
			source = key.create();
			byKey.put(key, source);
			running.add(source);
			source.start(host);
		}
		return source;
	}

	void update(long now) {
		for (int i = 0, size = running.size(); i < size; ++i) {
			running.get(i).update(now);
		}
	}

	void stop() {
		for (int i = 0, size = running.size(); i < size; ++i) {
			running.get(i).stop();
		}
		running.clear();
		byKey.clear();
	}
}
