package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.project.Preparation;

// A prepared project: its plan, its diagnostics and the bytes of the
// files it read, which are all its images are decoded from, with the fix
// its warnings name for a sampler that reads no image, and the uniform a
// tuned number of the text was replaced by, if any.
record PlanShader(
		@NonNull Preparation preparation,
		float quality,
		@NonNull String textureFix,
		@Nullable Tuning tuning) {
	PlanShader(@NonNull Preparation preparation, float quality,
			@NonNull String textureFix) {
		this(preparation, quality, textureFix, null);
	}

	@NonNull
	PlanShader withQuality(float quality) {
		return new PlanShader(preparation, quality, textureFix, tuning);
	}
}
