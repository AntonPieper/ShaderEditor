package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.CompiledSource;
import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.project.SourceMap;

// What a program of a plan compiles to: its text with the lines the
// engine generates after #version, and the declaration of a tuned
// number's uniform where the text uses it, a vertex shader of the same
// version, and the origin of every line of the fragment shader.
record PlanSource(
		@NonNull String fragment,
		@NonNull String vertex,
		@NonNull SourceMap sourceMap,
		float fTimeMax) {
	private static final float DEFAULT_FTIME_MAX = 3f;
	// The macro whose number is the period of ftime.
	static final String FTIME_PERIOD = "FTIME_PERIOD";
	private static final Pattern FTIME = Pattern.compile(
			"^#define[ \\t]+" + FTIME_PERIOD + "[ \\t]+([0-9.]+)[ \\t]*$",
			Pattern.MULTILINE);

	// Returns null and adds why when this context cannot compile it.
	@Nullable
	static PlanSource of(
			@NonNull RenderPlan.Program program,
			int glesVersion,
			@NonNull List<ProjectDiagnostic> errors) {
		return of(program, glesVersion, null, errors);
	}

	// With the declaration of tuning's uniform when the code uses it.
	@Nullable
	static PlanSource of(
			@NonNull RenderPlan.Program program,
			int glesVersion,
			@Nullable Tuning tuning,
			@NonNull List<ProjectDiagnostic> errors) {
		String text = program.text();
		// What the compiler reads: comments mean nothing.
		var scan = PreprocessorScan.scan(program.lines(), null);
		int version = CompiledSource.version(scan);
		if (!CompiledSource.compiles(version, glesVersion)) {
			SourceLocation at = program.sourceMap().locate(program.head());
			errors.add(new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
					"#version " + version + " es needs OpenGL ES 3.0, but " +
							"this device only has OpenGL ES 2.0",
					at != null ? at.file() : program.file(),
					at != null ? at.line() : 0));
			return null;
		}
		float fTimeMax = fTimeMax(text);
		if (Float.isNaN(fTimeMax)) {
			errors.add(fTimeError(program));
			return null;
		}
		// The text itself is made where everybody who has to know what
		// the compiler reads can make it too.
		int after = 0;
		List<String> declaration = List.of();
		if (tuning != null && uses(scan, tuning.name)) {
			// After every #extension, which no declaration may precede.
			after = declarationLine(scan, program.lines(), program.head());
			declaration = tuning.declaration();
		}
		CompiledSource compiled = CompiledSource.of(program.lines(), scan,
				program.head(), program.sourceMap(), after, declaration);
		return new PlanSource(
				compiled.text(),
				version >= 300
						? "#version " + version + " es\n" +
						FullScreenQuad.VERTEX_SHADER_3
						: FullScreenQuad.VERTEX_SHADER,
				compiled.sourceMap(),
				fTimeMax);
	}

	// Whether the code, not a comment, names the uniform.
	private static boolean uses(@NonNull PreprocessorScan scan,
			@NonNull String name) {
		var token = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
		for (String line : scan.code()) {
			if (token.matcher(line).find()) {
				return true;
			}
		}
		return false;
	}

	// The 1-based line after which a declaration goes: after head and
	// after the last #extension, or after the #endif that closes the
	// outermost conditional around it, so that it is never left out;
	// and past a block comment or a backslash continuation that goes on
	// from that line, so that it is not swallowed by them.
	static int declarationLine(@NonNull PreprocessorScan scan,
			@NonNull List<String> lines, int head) {
		int after = head;
		var directives = scan.directives();
		for (int i = 0; i < directives.size(); ++i) {
			var directive = directives.get(i);
			if (directive.kind() != PreprocessorScan.Kind.EXTENSION) {
				continue;
			}
			int line = directive.line();
			if (directive.depth() > 0) {
				for (int j = i + 1; j < directives.size(); ++j) {
					var closing = directives.get(j);
					if (closing.kind() == PreprocessorScan.Kind.ENDIF &&
							closing.depth() == 0) {
						line = closing.line();
						break;
					}
				}
			}
			after = Math.max(after, line);
		}
		var open = scan.openComments();
		while (after > 0 && after < lines.size() &&
				(open.get(after - 1) || lines.get(after - 1).endsWith("\\"))) {
			++after;
		}
		return after;
	}

	// NaN when the period is not a number, the default without a period
	// and for one that is zero or too large, which would make ftime NaN.
	private static float fTimeMax(@NonNull String text) {
		Matcher matcher = FTIME.matcher(text);
		if (matcher.find()) {
			float value;
			try {
				value = Float.parseFloat(matcher.group(1));
			} catch (NumberFormatException e) {
				return Float.NaN;
			}
			if (value > 0f && value < Float.POSITIVE_INFINITY) {
				return value;
			}
		}
		return DEFAULT_FTIME_MAX;
	}

	@NonNull
	private static ProjectDiagnostic fTimeError(@NonNull RenderPlan.Program program) {
		String text = program.text();
		Matcher matcher = FTIME.matcher(text);
		boolean found = matcher.find();
		int line = 1;
		for (int i = 0, end = found ? matcher.start() : 0; i < end; ++i) {
			if (text.charAt(i) == '\n') {
				++line;
			}
		}
		SourceLocation at = found ? program.sourceMap().locate(line) : null;
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				"FTIME_PERIOD " + (found ? matcher.group(1) + " " : "") +
						"is not a number",
				at != null ? at.file() : program.file(),
				at != null ? at.line() : 0);
	}
}
