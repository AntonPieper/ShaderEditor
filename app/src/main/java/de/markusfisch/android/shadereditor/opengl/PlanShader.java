package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.Preparation;

// A prepared project: its plan, its diagnostics and the bytes of the
// files it read, which are all its images are decoded from, with the fix
// its warnings name for a sampler that reads no image, the uniforms tuned
// numbers of the text were replaced by, and who hears whether a build
// with them draws instead of the engine's listener, if anybody does.
record PlanShader(
		@NonNull Preparation preparation,
		float quality,
		@NonNull String textureFix,
		@NonNull List<Tuning> tunings,
		@Nullable Tuning.Listener heard) {
	// The build owns which uniforms it has; their values are shared.
	PlanShader {
		tunings = List.copyOf(tunings);
	}

	PlanShader(@NonNull Preparation preparation, float quality,
			@NonNull String textureFix) {
		this(preparation, quality, textureFix, List.of(), null);
	}

	@NonNull
	PlanShader withQuality(float quality) {
		return new PlanShader(preparation, quality, textureFix, tunings,
				heard);
	}
}
