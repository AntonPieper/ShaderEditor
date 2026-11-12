package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// What each active sampler of a pass reads, decided once after linking
// from the pass's inputs and the samplers the program reflects. A sampler
// without an input or an engine provider keeps the empty texture.
final class PlanBindings {
	// The first active camera sampler, or null.
	@Nullable
	final GlUniform camera;
	@NonNull
	final Sampling cameraSampling;
	// Annotated inputs the compiler optimized away.
	@NonNull
	final List<ProjectDiagnostic> warnings;

	private PlanBindings(
			@Nullable GlUniform camera,
			@NonNull Sampling cameraSampling,
			@NonNull List<ProjectDiagnostic> warnings) {
		this.camera = camera;
		this.cameraSampling = cameraSampling;
		this.warnings = List.copyOf(warnings);
	}

	@NonNull
	static PlanBindings of(
			@NonNull RenderPlan.Pass pass,
			@NonNull List<GlUniform> samplers) {
		var active = new HashMap<String, GlUniform>();
		GlUniform camera = null;
		for (var sampler : samplers) {
			active.put(sampler.name(), sampler);
			if (camera == null && isCamera(sampler)) {
				camera = sampler;
			}
		}
		var warnings = new ArrayList<ProjectDiagnostic>();
		var cameraSampling = Sampling.CAMERA;
		for (var input : pass.inputs()) {
			var sampler = active.get(input.uniform());
			if (sampler == null) {
				warnings.add(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.WARNING,
						input.uniform() + " has an input but the shader does " +
								"not use it, so nothing is bound to it",
						input.file(), input.line()));
			} else if (sampler == camera) {
				cameraSampling = input.sampling();
			}
		}
		return new PlanBindings(camera, cameraSampling, warnings);
	}

	private static boolean isCamera(@NonNull GlUniform sampler) {
		return sampler.textureTarget() == GLES11Ext.GL_TEXTURE_EXTERNAL_OES &&
				(UniformTable.CAMERA_BACK.equals(sampler.name()) ||
						UniformTable.CAMERA_FRONT.equals(sampler.name()));
	}
}
