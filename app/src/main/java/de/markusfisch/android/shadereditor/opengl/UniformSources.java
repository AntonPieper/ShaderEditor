package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

final class UniformSources {
	private final Map<UniformSource.Key<?>, UniformSource> byKey = new HashMap<>();
	private final ArrayList<UniformSource> running = new ArrayList<>();
	@NonNull
	private final EngineHost host;
	private int deviceRotation;

	UniformSources(@NonNull EngineHost host) {
		this.host = host;
	}

	@NonNull
	<S extends UniformSource> S start(@NonNull UniformSource.Key<S> key) {
		UniformSource source = byKey.get(key);
		if (source == null) {
			source = key.create();
			byKey.put(key, source);
			source.start(host, this);
			running.add(source);
		}
		return key.cast(source);
	}

	@Nullable
	<S extends UniformSource> S get(@NonNull UniformSource.Key<S> key) {
		UniformSource source = byKey.get(key);
		return source != null ? key.cast(source) : null;
	}

	int getDeviceRotation() {
		return deviceRotation;
	}

	void setDeviceRotation(int deviceRotation) {
		this.deviceRotation = deviceRotation;
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
