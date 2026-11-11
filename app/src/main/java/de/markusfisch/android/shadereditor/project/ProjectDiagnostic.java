package de.markusfisch.android.shadereditor.project;

/**
 * A problem found in project files. {@code file} may be null and
 * {@code line} is 1-based, or 0 when unknown.
 */
public record ProjectDiagnostic(
		Severity severity,
		String message,
		ProjectPath file,
		int line) {
	public enum Severity {
		ERROR,
		WARNING
	}
}
