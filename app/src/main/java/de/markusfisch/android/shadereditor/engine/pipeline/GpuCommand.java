package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Image2D;
import de.markusfisch.android.shadereditor.engine.scene.Material;

public sealed interface GpuCommand {
	// Stateful envelope
	record BeginPass(
			@NonNull Framebuffer target,
			float[] clearColorOrNull,
			int[] viewportXYWHOrNull
	) implements GpuCommand {
	}

	record EndPass() implements GpuCommand {
	}

	// Stateless within a pass
	record BindProgram(@NonNull Material material) implements GpuCommand {
	}

	record BindGeometry(@NonNull Geometry geometry) implements GpuCommand {
	}

	record SetUniforms(@NonNull Material material) implements GpuCommand {
	}

	record Draw(
			int vertexCount,
			int first,
			int mode /* GLES30.GL_TRIANGLES etc. */
	) implements GpuCommand {
	}

	// Utilities
	record Blit(@NonNull Image2D src, @NonNull Framebuffer dst) implements GpuCommand {
	}
}