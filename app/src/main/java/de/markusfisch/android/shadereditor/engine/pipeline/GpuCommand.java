package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.graphics.Primitive;
import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Image2D;
import de.markusfisch.android.shadereditor.engine.scene.Material;

public sealed interface GpuCommand {
	record BeginPass(@NonNull Framebuffer target, ClearColor clearColor, ViewportRect viewport)
			implements GpuCommand {
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

	record Draw(int vertexCount, int first, @NonNull Primitive primitive) implements GpuCommand {
	}

	// Utilities
	record Blit(@NonNull Image2D src, @NonNull Framebuffer dst) implements GpuCommand {
	}
}