package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.project.SourceMap;
import de.markusfisch.android.shadereditor.project.SourceText;

// What a program of a plan compiles to: its text with the lines the
// engine generates after #version, a vertex shader of the same version,
// and the origin of every line of the fragment shader.
record PlanSource(
		@NonNull String fragment,
		@NonNull String vertex,
		@NonNull SourceMap sourceMap,
		float fTimeMax) {
	private static final float DEFAULT_FTIME_MAX = 3f;
	private static final Pattern VERSION = Pattern.compile("\\d{1,9}");
	private static final Pattern FTIME = Pattern.compile(
			"^#define[ \\t]+FTIME_PERIOD[ \\t]+([0-9.]+)[ \\t]*$",
			Pattern.MULTILINE);
	private static final Pattern EXTERNAL_SAMPLER = Pattern.compile(
			"\\bsamplerExternalOES\\b");

	// Returns null and adds why when this context cannot compile it.
	@Nullable
	static PlanSource of(
			@NonNull RenderPlan.Program program,
			int glesVersion,
			@NonNull List<ProjectDiagnostic> errors) {
		String text = program.text();
		// What the compiler reads: comments mean nothing.
		var scan = PreprocessorScan.scan(program.lines(), null);
		int version = languageVersion(scan);
		if (version >= 300 && glesVersion < 3) {
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
		var generated = new ArrayList<String>();
		generated.add("#define SHADER_EDITOR 1");
		if (EXTERNAL_SAMPLER.matcher(String.join("\n", scan.code())).find()) {
			generated.add(version >= 300
					? "#extension GL_OES_EGL_image_external_essl3 : require"
					: "#extension GL_OES_EGL_image_external : require");
		}
		// A line of code that ends behind a backslash in characters
		// a compiler cannot read goes on in the next line, as it was
		// written to: they are left out, where a space would end it.
		var lines = new ArrayList<String>();
		for (int i = 0; i < program.lines().size(); ++i) {
			String line = program.lines().get(i);
			int tail = SourceText.afterContinuation(line,
					scan.code().get(i));
			lines.add(tail < 0 ? line : line.substring(0, tail));
		}
		lines.addAll(program.head(), generated);
		return new PlanSource(
				removeNonAscii(String.join("\n", lines) + "\n"),
				version >= 300
						? "#version " + version + " es\n" +
						FullScreenQuad.VERTEX_SHADER_3
						: FullScreenQuad.VERTEX_SHADER,
				program.sourceMap().insertGenerated(program.head(),
						generated.size()),
				fTimeMax);
	}

	private static int languageVersion(@NonNull PreprocessorScan scan) {
		for (var directive : scan.directives()) {
			if (directive.kind() == PreprocessorScan.Kind.VERSION) {
				Matcher matcher = VERSION.matcher(directive.argument());
				return matcher.lookingAt()
						? Integer.parseInt(matcher.group())
						: 100;
			}
		}
		return 100;
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

	// Non-ASCII characters crash some shader compilers, so each becomes
	// a space here, at the last step before the compiler, and never in
	// the stored text. In a comment that changes nothing; in code
	// preparation warned of it. Line breaks stay, so lines still map.
	@NonNull
	private static String removeNonAscii(@NonNull String text) {
		return text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", " ");
	}
}
