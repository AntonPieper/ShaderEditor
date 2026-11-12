package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.project.SourceMap;

// What a program of a plan compiles to: its text with the lines the
// engine generates after #version, a vertex shader of the same version,
// and the origin of every line of the fragment shader.
record PlanSource(
		@NonNull String fragment,
		@NonNull String vertex,
		@NonNull SourceMap sourceMap,
		float fTimeMax) {
	private static final float DEFAULT_FTIME_MAX = 3f;
	private static final Pattern VERSION = Pattern.compile(
			"^[ \\t]*#[ \\t]*version[ \\t]+(\\d{1,9})", Pattern.MULTILINE);
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
		int version = languageVersion(text);
		if (version >= 300 && glesVersion < 3) {
			SourceLocation at = program.sourceMap().locate(program.versionLine());
			errors.add(new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
					"#version " + version + " es needs OpenGL ES 3.0, but " +
							"this device only has OpenGL ES 2.0",
					at != null ? at.file() : program.file(),
					at != null ? at.line() : 0));
			return null;
		}
		var generated = new ArrayList<String>();
		generated.add("#define SHADER_EDITOR 1");
		if (EXTERNAL_SAMPLER.matcher(text).find()) {
			generated.add(version >= 300
					? "#extension GL_OES_EGL_image_external_essl3 : require"
					: "#extension GL_OES_EGL_image_external : require");
		}
		var lines = new ArrayList<>(program.lines());
		lines.addAll(program.versionLine(), generated);
		return new PlanSource(
				removeNonAscii(String.join("\n", lines) + "\n"),
				version >= 300
						? "#version " + version + " es\n" +
						FullScreenQuad.VERTEX_SHADER_3
						: FullScreenQuad.VERTEX_SHADER,
				program.sourceMap().insertGenerated(program.versionLine(),
						generated.size()),
				fTimeMax(text));
	}

	private static int languageVersion(@NonNull String text) {
		Matcher matcher = VERSION.matcher(text);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 100;
	}

	private static float fTimeMax(@NonNull String text) {
		Matcher matcher = FTIME.matcher(text);
		if (matcher.find()) {
			try {
				float period = Float.parseFloat(matcher.group(1));
				if (period > 0) {
					return period;
				}
			} catch (NumberFormatException e) {
				// Fall through to the default, as without a period.
			}
		}
		return DEFAULT_FTIME_MAX;
	}

	// Text pasted from other apps may carry non-ASCII characters that
	// crash some shader compilers. Line breaks stay, so lines still map.
	@NonNull
	private static String removeNonAscii(@NonNull String text) {
		return text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", "");
	}
}
