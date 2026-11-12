package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.hardware.SensorRegistry;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

final class UniformSources {
	private final Map<UniformSource.Key<?>, UniformSource> byKey = new HashMap<>();
	private final ArrayList<UniformSource> running = new ArrayList<>();
	// What the running sources cannot provide on this host.
	private final ArrayList<ProjectDiagnostic> warnings = new ArrayList<>();
	private boolean warned;
	@NonNull
	private final EngineHost host;
	// The keys run() started, or null when they must start again.
	@Nullable
	private Set<UniformSource.Key<?>> roots = Set.of();
	@Nullable
	private SensorRegistry sensors;
	@Nullable
	private ShaderTextureResources.SamplerTextureBinding cameraBinding;
	private int deviceRotation;
	private int renderWidth;
	private int renderHeight;

	UniformSources(@NonNull EngineHost host) {
		this.host = host;
	}

	UniformSources(@NonNull EngineHost host, @NonNull SensorRegistry sensors) {
		this.host = host;
		this.sensors = sensors;
	}

	// Runs the sources of these keys and the ones they start. When the
	// same keys run already, they keep running and keep their values;
	// otherwise all stop first.
	void run(@NonNull Set<UniformSource.Key<?>> keys) {
		if (keys.equals(roots)) {
			return;
		}
		stop();
		roots = keys;
		for (var key : keys) {
			start(key);
		}
	}

	@NonNull
	<S extends UniformSource> S start(@NonNull UniformSource.Key<S> key) {
		UniformSource source = byKey.get(key);
		if (source == null) {
			source = key.create();
			byKey.put(key, source);
			source.start(host, this);
			// After start, so the sources it started update before it.
			running.add(source);
		}
		return key.cast(source);
	}

	void warn(@NonNull String message) {
		for (var warning : warnings) {
			if (warning.message().equals(message)) {
				return;
			}
		}
		warnings.add(new ProjectDiagnostic(
				ProjectDiagnostic.Severity.WARNING, message, null, 0));
		warned = true;
	}

	// Whether a source warned since this was asked last.
	boolean takeWarned() {
		boolean result = warned;
		warned = false;
		return result;
	}

	@NonNull
	List<ProjectDiagnostic> getWarnings() {
		return warnings;
	}

	@NonNull
	SensorRegistry sensors() {
		if (sensors == null) {
			sensors = SensorRegistry.of(host.context());
		}
		return sensors;
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

	@Nullable
	ShaderTextureResources.SamplerTextureBinding getCameraBinding() {
		return cameraBinding;
	}

	void setCameraBinding(
			@Nullable ShaderTextureResources.SamplerTextureBinding cameraBinding) {
		if (cameraBinding != this.cameraBinding) {
			this.cameraBinding = cameraBinding;
			// A running camera streams into the texture of the last binding.
			roots = null;
		}
	}

	int getRenderWidth() {
		return renderWidth;
	}

	int getRenderHeight() {
		return renderHeight;
	}

	void setRenderSize(int renderWidth, int renderHeight) {
		this.renderWidth = renderWidth;
		this.renderHeight = renderHeight;
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
		warnings.clear();
		warned = false;
		roots = Set.of();
	}
}
