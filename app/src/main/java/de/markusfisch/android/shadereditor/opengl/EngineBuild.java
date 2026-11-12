package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// A shader linked in the current context, as the engine swaps it in and
// draws it, whatever kind of shader it was built from.
interface EngineBuild extends ShaderSwap.Build {
	// The programs whose builtins it reads, the one drawn every frame
	// first.
	@NonNull
	List<GlProgram> programs();

	float quality();

	float fTimeMax();

	// Decodes what it reads off the GL thread; until upload() took it,
	// it is not complete.
	void loadTextures(
			@NonNull Context context,
			int width,
			int height,
			@Nullable Runnable onDecoded);

	// GL thread. Uploads what was decoded, if it is ready, adds what the
	// host should be told, and returns what the backbuffer starts as; the
	// caller recycles it.
	@Nullable
	DecodedTextures upload(@NonNull List<ProjectDiagnostic> diagnostics);

	// Whether it can never complete, so it must not replace the current:
	// what it reads did not decode or upload.
	boolean hasFailed();

	@Nullable
	ShaderTextureResources.SamplerTextureBinding getCameraBinding();

	// GL thread, before it replaces the current build and whenever the
	// render size changed: makes what it draws into for this render size,
	// starting as the seed or cleared. Adds why when it cannot; it has
	// nothing to draw into then.
	@NonNull
	ShaderSwap.Allocation createTargets(
			@NonNull ShaderRenderPipeline pipeline,
			int width,
			int height,
			@Nullable Bitmap seed,
			@NonNull List<ProjectDiagnostic> errors);

	// GL thread.
	boolean hasTargets(@NonNull ShaderRenderPipeline pipeline);

	// GL thread. Releases what it draws into; false when it had nothing.
	boolean releaseTargets(@NonNull ShaderRenderPipeline pipeline);

	// GL thread. Writes the builtins for these inputs, draws a frame and
	// puts it on the surface.
	void draw(
			@NonNull ShaderRenderPipeline pipeline,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame,
			long now);
}
