package de.markusfisch.android.shadereditor.engine.error;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class to parse shader compiler info logs into structured error data.
 */
public final class ShaderErrorParser {
	// Matches error lines like: "ERROR: 0:12: 'varying' : syntax error"
	// It captures the line number and the error message.
	private static final Pattern ERROR_PATTERN = Pattern.compile(
			"^(?:ERROR|WARNING):\\s*\\d+:(\\d+):\\s*(.*)$");

	private ShaderErrorParser() {
		throw new UnsupportedOperationException("This class cannot be instantiated.");
	}

	/**
	 * Parses a raw info log from a shader compiler.
	 *
	 * @param infoLog The complete string output from glGetShaderInfoLog or glGetProgramInfoLog.
	 * @return A list of structured SourceLocation objects.
	 */
	@NonNull
	public static List<EngineError.SourceLocation> parseInfoLog(@NonNull String infoLog) {
		String[] lines = infoLog.split("\n");
		List<EngineError.SourceLocation> locations = new ArrayList<>();
		for (String line : lines) {
			if (line.trim().isEmpty()) {
				continue;
			}
			Matcher matcher = ERROR_PATTERN.matcher(line.trim());
			if (matcher.matches()) {
				try {
					// Group 1 is the line number.
					int lineNumber = Integer.parseInt(matcher.group(1));
					// Group 2 is the error message.
					String message = matcher.group(2).trim();
					locations.add(new EngineError.SourceLocation(lineNumber, message));
				} catch (NumberFormatException | IndexOutOfBoundsException e) {
					// If parsing the structured line fails, add it as a general message.
					locations.add(new EngineError.SourceLocation(-1, line));
				}
			} else {
				// For lines that don't match the expected error format.
				locations.add(new EngineError.SourceLocation(-1, line));
			}
		}
		return locations;
	}
}