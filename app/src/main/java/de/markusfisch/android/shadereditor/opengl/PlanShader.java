package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.project.Preparation;

// A prepared project: its plan, its diagnostics and the bytes of the
// files it read, which are all its images are decoded from, with the fix
// its warnings name for a sampler that reads no image.
record PlanShader(
		@NonNull Preparation preparation,
		float quality,
		@NonNull String textureFix) {
	@NonNull
	PlanShader withQuality(float quality) {
		return new PlanShader(preparation, quality, textureFix);
	}
}
