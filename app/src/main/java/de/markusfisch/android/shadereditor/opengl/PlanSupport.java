package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;

// This runtime draws every pass of a plan into an RGBA8 target of any
// size. Anything else is refused as a whole, with one diagnostic per
// directive that asks for it.
final class PlanSupport {
	private static final String NEWER = " a newer version of ShaderEditor";

	private PlanSupport() {
	}

	@NonNull
	static List<ProjectDiagnostic> refusals(@NonNull RenderPlan plan) {
		var errors = new ArrayList<ProjectDiagnostic>();
		for (var pass : plan.passes()) {
			var target = pass.target();
			if (target.format() != Format.RGBA8) {
				errors.add(new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
						"se target format needs" + NEWER, target.file(),
						target.line()));
			}
		}
		return errors;
	}
}
