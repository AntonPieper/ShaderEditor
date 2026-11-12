package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// A shader linked in the current context, as the engine swaps it in and
// draws it, whatever kind of shader it was built from.
interface EngineBuild extends ShaderSwap.Build {
	@NonNull
	GlProgram program();

	float quality();

	float fTimeMax();

	@NonNull
	Sampling backBufferSampling();

	// Whether a new render size needs it built again.
	boolean isSizedToRender();

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

	@Nullable
	ShaderTextureResources.SamplerTextureBinding getCameraBinding();

	// GL thread. Binds what it reads and draws into the pipeline.
	void draw(@NonNull ShaderRenderPipeline pipeline);
}
