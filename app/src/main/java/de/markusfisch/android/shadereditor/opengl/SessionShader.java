package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ShaderProjectSession;

// The entry text of a host's session, built as one shader text.
record SessionShader(@NonNull ShaderProjectSession session) implements EngineShader {
	@Override
	public float quality() {
		return session.getQuality();
	}

	@NonNull
	@Override
	public EngineShader withQuality(float quality) {
		return new SessionShader(session.withQuality(quality));
	}

	@Nullable
	@Override
	public EngineBuild link(
			@NonNull GlDevice device,
			int version,
			@NonNull List<ProjectDiagnostic> errors) {
		// Text pasted from other apps may carry non-ASCII characters
		// that crash some shader compilers.
		return ShaderBuild.link(
				device,
				removeNonAscii(session.getEntryPointSource()),
				session.getQuality(),
				version,
				errors);
	}

	@NonNull
	private static String removeNonAscii(@NonNull String text) {
		return text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", "");
	}
}
