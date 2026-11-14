package de.markusfisch.android.shadereditor.service;

import androidx.annotation.NonNull;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// What a wallpaper says about its shader. An error means it shows the
// last shader that worked, or nothing, so errors come first and say
// where they are; warnings follow as they always have.
final class WallpaperDiagnostics {
	private WallpaperDiagnostics() {
	}

	@NonNull
	static String text(@NonNull List<ProjectDiagnostic> diagnostics) {
		var text = new StringBuilder();
		for (var diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				String location = diagnostic.location();
				append(text, location.isEmpty()
						? diagnostic.message()
						: location + ": " + diagnostic.message());
			}
		}
		for (var diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.WARNING) {
				append(text, diagnostic.message());
			}
		}
		return text.toString();
	}

	private static void append(@NonNull StringBuilder text,
			@NonNull String line) {
		if (text.length() > 0) {
			text.append('\n');
		}
		text.append(line);
	}
}
