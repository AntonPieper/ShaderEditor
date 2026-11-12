package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

// This runtime draws one pass that reads images and engine samplers into
// the default target. Anything else is refused as a whole, with one
// diagnostic per directive that asks for it.
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
				if (source.kind() == Source.Kind.PASS) {
					errors.add(error(source.pass() == i
							? "Reading the previous frame with se input needs" +
							NEWER
							: "Reading the output of " +
							file(plan, source.pass()) + " needs" + NEWER,
							input.file(), input.line()));
				} else if (source.kind() == Source.Kind.IMAGE &&
						"samplerCube".equals(input.samplerType())) {
					errors.add(error("Cubemap images need" + NEWER,
							input.file(), input.line()));
				}
			}
			var target = pass.target();
			var options = new ArrayList<String>();
			if (target.isFixed()) {
				options.add("size");
			} else if (target.scale() != 1) {
				options.add("scale");
			}
			if (target.format() != Format.RGBA8) {
				options.add("format");
			}
			if (target.present() != Filter.NEAREST) {
				options.add("present");
			}
			if (!options.isEmpty()) {
				errors.add(error("se target " + String.join(", ", options) +
						" needs" + NEWER, target.file(), target.line()));
			}
			if (pass.initial() != null) {
				errors.add(error("se target initial needs" + NEWER,
						target.file(), target.line()));
			}
		}
		if (errors.isEmpty() && plan.passes().size() != 1) {
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
