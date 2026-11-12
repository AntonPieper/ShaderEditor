package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// What a host asks the engine to draw, at a quality.
interface EngineShader {
	float quality();

	@NonNull
	EngineShader withQuality(float quality);

	// GL thread. Returns null and adds why when it does not link.
	@Nullable
	EngineBuild link(
			@NonNull GlDevice device,
			int version,
			@NonNull List<ProjectDiagnostic> errors);
}
