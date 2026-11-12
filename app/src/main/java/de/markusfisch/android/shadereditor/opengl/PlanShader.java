package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// A prepared project: its plan, its diagnostics and the bytes of the
// files it read, which are all its images are decoded from, with the fix
// its warnings name for a sampler that reads no image.
record PlanShader(
		@NonNull Preparation preparation,
		float quality,
		@NonNull String textureFix) implements EngineShader {
	@NonNull
	@Override
	public EngineShader withQuality(float quality) {
		return new PlanShader(preparation, quality, textureFix);
	}

	@Nullable
	@Override
	public EngineBuild link(
			@NonNull GlDevice device,
			int version,
			@NonNull List<ProjectDiagnostic> errors) {
		return PlanRuntime.link(device, this, version, errors);
	}
}
