package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

// This runtime draws one pass that reads images, engine samplers and its
// own previous frame into an RGBA8 target of any size, with the initial
// content of that frame from an image or a shader of the same kind.
// Anything else is refused as a whole, with one diagnostic per directive
// that asks for it.
final class PlanSupport {
	private static final String NEWER = " a newer version of ShaderEditor";

	private PlanSupport() {
	}

	@NonNull
	static List<ProjectDiagnostic> refusals(@NonNull RenderPlan plan) {
		var errors = new ArrayList<ProjectDiagnostic>();
		for (int i = 0; i < plan.passes().size(); ++i) {
			var pass = plan.passes().get(i);
			for (var input : pass.inputs()) {
				var source = input.source();
				if (source.kind() == Source.Kind.PASS && source.pass() != i) {
					errors.add(error("Reading the output of " +
							file(plan, source.pass()) + " needs" + NEWER,
							input.file(), input.line()));
				}
			}
			var target = pass.target();
			if (target.format() != Format.RGBA8) {
				errors.add(error("se target format needs" + NEWER,
						target.file(), target.line()));
			}
			if (pass.initial() != null && i != plan.output()) {
				errors.add(error("se target initial needs" + NEWER,
						target.file(), target.line()));
			}
		}
		// The running pass, and the shader it starts from.
		var initial = plan.passes().get(plan.output()).initial();
		int passes = initial != null && initial.kind() == Source.Kind.PASS
				? 2
				: 1;
		if (errors.isEmpty() && plan.passes().size() != passes) {
			errors.add(error("Projects with more than one pass need" + NEWER,
					null, 0));
		}
		return errors;
	}

	@NonNull
	private static ProjectPath file(@NonNull RenderPlan plan, int pass) {
		return plan.programs().get(plan.passes().get(pass).program()).file();
	}

	@NonNull
	private static ProjectDiagnostic error(
			@NonNull String message,
			ProjectPath file,
			int line) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				message, file, line);
	}
}
