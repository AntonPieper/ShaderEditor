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
	private static final String MISSING_SEMICOLON = "';' is missing after '";

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

	/** A compile showed that a ';' after token on line is missing. */
	public static ProjectDiagnostic missingSemicolon(ProjectPath file,
			int line, String token) {
		return new ProjectDiagnostic(Severity.WARNING,
				MISSING_SEMICOLON + token + "'", file, line);
	}

	/** Whether this was made by {@link #missingSemicolon}. */
	public boolean isMissingSemicolon() {
		return severity == Severity.WARNING &&
				message.startsWith(MISSING_SEMICOLON);
	}

	/**
	 * An error without a location.
	 */
	public static ProjectDiagnostic error(String message) {
		return new ProjectDiagnostic(Severity.ERROR, message, null, 0);
	}

	/**
	 * Whether one of these is an error; warnings alone leave the shader
	 * running.
	 */
	public static boolean anyError(Iterable<ProjectDiagnostic> diagnostics) {
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == Severity.ERROR) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether this is about what is open: the file shown, the file that
	 * runs, or a file reads, the files what runs is made of. When
	 * nothing runs, no build reports, and what is told is of the
	 * manifest. Anything else is of a build of another file, which ran
	 * before, and is no longer to be told.
	 */
	public boolean isAbout(ProjectPath shown, ProjectPath running,
			Reads reads) {
		return file == null || running == null || file.equals(shown) ||
				file.equals(running) || reads.reads(file);
	}

	/** Whether what runs is made of a file. */
	public interface Reads {
		boolean reads(ProjectPath path);
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
