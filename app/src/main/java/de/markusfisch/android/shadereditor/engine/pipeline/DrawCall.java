package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Material;

public record DrawCall(
		@NonNull Geometry geometry,
		@NonNull Material material,
		int mode // GLES30.GL_TRIANGLE_STRIP etc.
) {
}