package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.project.SourceMap;

/**
 * Turns a driver's info log into diagnostics, one per line. A line
 * whose location cannot be read or mapped keeps its raw text as the
 * message.
 */
final class DriverLog {
	// "ERROR: 0:12: 'foo' : undeclared identifier", as most Android
	// drivers write it; severity is the word in front.
	private static final Pattern LOCATED = Pattern.compile(
			"^.*(\\d+):(\\d+):\\s+(.*)$");
	// "0:12(5): error: `foo' undeclared" or "0:4(1): preprocessor
	// error: #error ...", as Mesa writes it.
	private static final Pattern MESA = Pattern.compile(
			"^\\s*\\d+:(\\d+)\\(\\d+\\):\\s*(?:preprocessor\\s+)?" +
					"(error|warning):\\s*(.*)$",
			Pattern.CASE_INSENSITIVE);
	// "0(12) : error C1008: undefined variable \"foo\"", as NVIDIA's
	// documentation shows it.
	private static final Pattern NVIDIA = Pattern.compile(
			"^\\s*\\d+\\((\\d+)\\)\\s*:\\s*(error|warning)\\s*\\w*:\\s*(.*)$",
			Pattern.CASE_INSENSITIVE);
	// "ERROR: 2 compilation errors.  No code generated.", the line
	// Adreno ends every failed compile with.
	private static final Pattern SUMMARY = Pattern.compile(
			"^ERROR: \\d+ compilation errors?\\.\\s+No code generated\\.\\s*$");
	private static final Pattern WARNING = Pattern.compile(
			"^\\s*WARNING\\b",
			Pattern.CASE_INSENSITIVE);

	private DriverLog() {
	}

	/** The first error of a parsed log if it has a line, else null. */
	@Nullable
	static ProjectDiagnostic firstError(
			@NonNull List<ProjectDiagnostic> diagnostics) {
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == Severity.ERROR) {
				return diagnostic.line() > 0 ? diagnostic : null;
			}
		}
		return null;
	}

	@NonNull
	static List<ProjectDiagnostic> parse(
			@NonNull String infoLog,
			@Nullable SourceMap sourceMap) {
		String[] lines = infoLog.split("\n");
		List<ProjectDiagnostic> diagnostics = new ArrayList<>(lines.length);
		for (String line : lines) {
			if (!SUMMARY.matcher(line).matches()) {
				diagnostics.add(parseLine(line, sourceMap));
			}
		}
		// A log that is only the summary still says the build failed.
		if (diagnostics.isEmpty()) {
			for (String line : lines) {
				diagnostics.add(parseLine(line, sourceMap));
			}
		}
		return diagnostics;
	}

	@NonNull
	private static ProjectDiagnostic parseLine(
			@NonNull String text,
			@Nullable SourceMap sourceMap) {
		Severity severity = WARNING.matcher(text).find()
				? Severity.WARNING
				: Severity.ERROR;
		Matcher matcher = LOCATED.matcher(text);
		String lineText;
		if (matcher.matches()) {
			lineText = matcher.group(2);
		} else if ((matcher = MESA.matcher(text)).matches() ||
				(matcher = NVIDIA.matcher(text)).matches()) {
			lineText = matcher.group(1);
			severity = "warning".equalsIgnoreCase(matcher.group(2))
					? Severity.WARNING
					: Severity.ERROR;
		} else {
			return new ProjectDiagnostic(severity, text, null, 0);
		}
		String message = Objects.requireNonNull(matcher.group(3));
		int line = Integer.parseInt(Objects.requireNonNull(lineText));
		if (sourceMap == null) {
			return new ProjectDiagnostic(severity, message, null,
					Math.max(line, 0), text);
		}
		SourceLocation location = sourceMap.locate(line);
		// A "}" missing at the end is reported on the line after the
		// last newline, one past the text: it belongs to the last line.
		if (location == null && line == sourceMap.lines().size() + 1) {
			location = sourceMap.locate(line - 1);
		}
		if (location == null || location.isGenerated()) {
			return new ProjectDiagnostic(severity, text, null, 0);
		}
		return new ProjectDiagnostic(severity, message, location.file(),
				location.line(), text);
	}
}
