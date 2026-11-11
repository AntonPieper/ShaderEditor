package de.markusfisch.android.shadereditor.project;

import java.util.Locale;

/**
 * A problem shown to the user, found in project files or reported by
 * the driver. {@code file} may be null, {@code line} is 1-based, or 0
 * when unknown, and {@code raw} is the text it was made from.
 */
public record ProjectDiagnostic(
		Severity severity,
		String message,
		ProjectPath file,
		int line,
		String raw) {
	public enum Severity {
		ERROR,
		WARNING
	}

	public ProjectDiagnostic(
			Severity severity,
			String message,
			ProjectPath file,
			int line) {
		this(severity, message, file, line, message);
	}

	/**
	 * An error without a location.
	 */
	public static ProjectDiagnostic error(String message) {
		return new ProjectDiagnostic(Severity.ERROR, message, null, 0);
	}

	/**
	 * The file and line to show before the message, or "" when neither
	 * is known.
	 */
	public String location() {
		String lineNumber = line > 0
				? String.format(Locale.getDefault(), "%d", line)
				: null;
		if (file == null) {
			return lineNumber == null ? "" : lineNumber;
		}
		return lineNumber == null ? file.toString() : file + ":" + lineNumber;
	}

	@Override
	public String toString() {
		String location = location();
		return location.isEmpty() ? message : location + ": " + message;
	}
}
