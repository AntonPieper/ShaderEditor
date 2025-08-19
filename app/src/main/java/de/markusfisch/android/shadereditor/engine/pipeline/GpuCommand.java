package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;

import java.util.Map;

import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.graphics.Primitive;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.RenderTarget;
import de.markusfisch.android.shadereditor.engine.scene.TextureSource;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;

public sealed interface GpuCommand {
	record BeginPass(@NonNull RenderTarget target, ClearColor clearColor, ViewportRect viewport)
			implements GpuCommand {
	}

	record EndPass() implements GpuCommand {
	}

	// Stateless within a pass
	record BindProgram(@NonNull ShaderAsset shader) implements GpuCommand {
	}

	record BindGeometry(@NonNull Geometry geometry) implements GpuCommand {
	}

	record SetUniforms(@NonNull Map<String, Uniform> uniforms) implements GpuCommand {
	}

	record Draw(int vertexCount, int first, @NonNull Primitive primitive) implements GpuCommand {
	}

	// Utilities
	record Blit(@NonNull TextureSource src, @NonNull RenderTarget dst) implements GpuCommand {
	}
}