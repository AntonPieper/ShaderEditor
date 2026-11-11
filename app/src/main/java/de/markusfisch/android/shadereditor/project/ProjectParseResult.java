package de.markusfisch.android.shadereditor.project;

/**
 * Either a parsed value or the diagnostic explaining why parsing failed.
 */
public record ProjectParseResult<T>(T value, ProjectDiagnostic diagnostic) {
	static <T> ProjectParseResult<T> success(T value) {
		return new ProjectParseResult<>(value, null);
	}

	static <T> ProjectParseResult<T> error(
			String message,
			ProjectPath file,
			int line) {
		return new ProjectParseResult<>(null, new ProjectDiagnostic(
				ProjectDiagnostic.Severity.ERROR, message, file, line));
	}

	public boolean isSuccess() {
		return diagnostic == null;
	}
}
