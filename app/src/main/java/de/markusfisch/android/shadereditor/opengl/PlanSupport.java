package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

// This runtime draws every pass of a plan into a target of any size in
// the formats the context can draw into. A plan that asks for a format,
// or a linear filter of one, that the context lacks is refused as a
// whole, with one diagnostic per directive that asks for it.
final class PlanSupport {
	private static final String LINEAR = ", which needs " +
			"OES_texture_half_float_linear; this device does not have it, ";

	private PlanSupport() {
	}

	@NonNull
	static List<ProjectDiagnostic> refusals(
			@NonNull RenderPlan plan,
			@NonNull TargetFormats formats) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var passes = plan.passes();
		for (int i = 0; i < passes.size(); ++i) {
			var pass = passes.get(i);
			var target = pass.target();
			var refusal = formats.refusal(target.format());
			if (refusal != null) {
				errors.add(error(refusal, target.file(), target.line()));
			}
			for (var input : pass.inputs()) {
				var source = input.source();
				if (source.kind() == Source.Kind.PASS &&
						isLinear(input.sampling()) &&
						!filters(plan, source.pass(), formats)) {
					errors.add(error(input.uniform() + " reads " +
							describe(plan, source.pass()) + " with a linear " +
							"filter" + LINEAR + "read it with filter=nearest",
							input.file(), input.line()));
				}
			}
			var initial = pass.initial();
			if (initial != null && initial.kind() == Source.Kind.PASS &&
					!filters(plan, initial.pass(), formats)) {
				errors.add(error("initial stretches " +
						describe(plan, initial.pass()) + " linearly" + LINEAR +
						"start from a pass in format=rgba8", target.file(),
						target.line()));
			}
			if (i == plan.output() && target.present() == Filter.LINEAR &&
					!filters(plan, i, formats)) {
				errors.add(error("present=linear scales " + describe(plan, i) +
						LINEAR + "use present=nearest", target.file(),
						target.line()));
			}
		}
		return errors;
	}

	private static boolean isLinear(@NonNull Sampling sampling) {
		return sampling.min() == Filter.LINEAR || sampling.mag() == Filter.LINEAR;
	}

	// Whether a pass in a format the context draws into can be filtered;
	// one it cannot draw into is refused already.
	private static boolean filters(
			@NonNull RenderPlan plan,
			int pass,
			@NonNull TargetFormats formats) {
		var format = format(plan, pass);
		return formats.refusal(format) != null || formats.filters(format);
	}

	@NonNull
	private static String describe(@NonNull RenderPlan plan, int pass) {
		ProjectPath file = plan.programs().get(
				plan.passes().get(pass).program()).file();
		return file + ", a target in format=" +
				TargetFormats.name(format(plan, pass)) + ",";
	}

	@NonNull
	private static Format format(@NonNull RenderPlan plan, int pass) {
		return plan.passes().get(pass).target().format();
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
