package de.markusfisch.android.shadereditor.project;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Input;
import de.markusfisch.android.shadereditor.project.HostDirective.Target;

/**
 * A pass file as its language read it: the text for the compiler, and
 * what the file asks of the host. Whatever the language, this is all
 * the pass graph learns of the text.
 *
 * @param lines       the lines the compiler gets, includes expanded
 *                    and host directives blanked
 * @param code        the lines without their comments
 * @param sourceMap   the origin of every line
 * @param head        how many of the lines must stay first, as in
 *                    {@link RenderPlan.Program}
 * @param inputs      inputs attached to their declarations, in order
 * @param target      the target directive, or null when there is none
 * @param references  paths named by directives that are neither in
 *                    inputs nor the target because they are invalid;
 *                    they are still dependencies
 * @param diagnostics problems with file and line
 * @param entryPoint  whether the code has what could be an entry
 *                    point; the compiler is the judge of whether it
 *                    is one
 */
record PassSource(
		List<String> lines,
		List<String> code,
		SourceMap sourceMap,
		int head,
		List<Input> inputs,
		Target target,
		List<HostDirectives.Reference> references,
		List<ProjectDiagnostic> diagnostics,
		boolean entryPoint) {
	PassSource {
		lines = List.copyOf(lines);
		code = List.copyOf(code);
		inputs = List.copyOf(inputs);
		references = List.copyOf(references);
		diagnostics = List.copyOf(diagnostics);
	}
}
