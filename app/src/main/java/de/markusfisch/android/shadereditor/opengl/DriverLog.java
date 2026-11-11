package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

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
	private static final Pattern LOCATED = Pattern.compile(
			"^.*(\\d+):(\\d+):\\s+(.*)$");
	private static final Pattern WARNING = Pattern.compile(
			"^\\s*WARNING\\b",
			Pattern.CASE_INSENSITIVE);

	private DriverLog() {
	}

	@NonNull
	static List<ProjectDiagnostic> parse(
			@NonNull String infoLog,
			@NonNull ShaderLineMapping lineMapping) {
		String[] lines = infoLog.split("\n");
		List<ProjectDiagnostic> diagnostics = new ArrayList<>(lines.length);
		for (String line : lines) {
			diagnostics.add(parseLine(line, lineMapping));
		}
		return diagnostics;
	}

	@NonNull
	private static ProjectDiagnostic parseLine(
			@NonNull String text,
			@NonNull ShaderLineMapping lineMapping) {
		Severity severity = WARNING.matcher(text).find()
				? Severity.WARNING
				: Severity.ERROR;
		Matcher matcher = LOCATED.matcher(text);
		if (!matcher.matches()) {
			return new ProjectDiagnostic(severity, text, null, 0);
		}
		String message = Objects.requireNonNull(matcher.group(3));
		int line = lineMapping.toSourceLine(Integer.parseInt(
				Objects.requireNonNull(matcher.group(2))));
		SourceMap sourceMap = lineMapping.getSourceMap();
		if (sourceMap == null) {
			return new ProjectDiagnostic(severity, message, null,
					Math.max(line, 0), text);
		}
		SourceLocation location = sourceMap.locate(line);
		if (location == null || location.isGenerated()) {
			return new ProjectDiagnostic(severity, text, null, 0);
		}
		return new ProjectDiagnostic(severity, message, location.file(),
				location.line(), text);
	}
}
