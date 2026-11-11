package de.markusfisch.android.shadereditor.project;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A normalized, case-sensitive path of a file inside a project root.
 * Segments are separated by '/' and stored in Unicode NFC.
 */
public final class ProjectPath {
	public enum Kind {
		SHADER,
		IMAGE,
		OTHER
	}

	private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:");
	private static final Pattern SCHEME = Pattern.compile(
			"[A-Za-z][A-Za-z0-9+.-]*:");

	private final String path;

	private ProjectPath(String path) {
		this.path = path;
	}

	/**
	 * Parse a path relative to the project root, like the manifest entry.
	 */
	public static ProjectParseResult<ProjectPath> parse(String path) {
		return resolve(new ArrayList<>(), path, null);
	}

	/**
	 * Resolve the literal text of a path reference relative to the
	 * directory of the file containing it.
	 */
	public static ProjectParseResult<ProjectPath> resolve(
			ProjectPath containingFile,
			String reference) {
		List<String> segments = new ArrayList<>(
				List.of(containingFile.path.split("/")));
		segments.remove(segments.size() - 1);
		return resolve(segments, reference, containingFile);
	}

	public Kind getKind() {
		String name = getFileName().toLowerCase(Locale.ROOT);
		if (name.endsWith(".glsl")) {
			return Kind.SHADER;
		} else if (name.endsWith(".png") ||
				name.endsWith(".jpg") ||
				name.endsWith(".jpeg")) {
			return Kind.IMAGE;
		}
		return Kind.OTHER;
	}

	public String getFileName() {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ProjectPath &&
				path.equals(((ProjectPath) other).path);
	}

	@Override
	public int hashCode() {
		return path.hashCode();
	}

	@Override
	public String toString() {
		return path;
	}

	private static ProjectParseResult<ProjectPath> resolve(
			List<String> segments,
			String reference,
			ProjectPath file) {
		String problem = findProblem(reference);
		if (problem != null) {
			return ProjectParseResult.error(problem, file, 0);
		}
		String normalized = Normalizer.normalize(reference, Normalizer.Form.NFC);
		for (String segment : normalized.split("/", -1)) {
			if (segment.isEmpty() || ".".equals(segment)) {
				continue;
			}
			if ("..".equals(segment)) {
				if (segments.isEmpty()) {
					return ProjectParseResult.error(
							"Path leaves the project folder: " + reference,
							file, 0);
				}
				segments.remove(segments.size() - 1);
				continue;
			}
			segments.add(segment);
		}
		if (segments.isEmpty()) {
			return ProjectParseResult.error(
					"Path names the project folder, not a file: " + reference,
					file, 0);
		}
		return ProjectParseResult.success(
				new ProjectPath(String.join("/", segments)));
	}

	private static String findProblem(String reference) {
		if (reference == null || reference.isEmpty()) {
			return "Path is empty";
		}
		for (int i = 0, length = reference.length(); i < length; ++i) {
			char c = reference.charAt(i);
			if (c == '"') {
				return "Path contains a double quote: " + reference;
			} else if (c == '\\') {
				return "Path contains a backslash, use / instead: " +
						reference;
			} else if (Character.isISOControl(c)) {
				return "Path contains a control character: " +
						reference.replaceAll("[\\x00-\\x1F\\x7F-\\x9F]", "?");
			}
		}
		if (reference.startsWith("/")) {
			return "Path is absolute: " + reference;
		} else if (DRIVE.matcher(reference).lookingAt()) {
			return "Path has a drive letter: " + reference;
		} else if (SCHEME.matcher(reference).lookingAt()) {
			return "Path is a URI, not a project path: " + reference;
		}
		return null;
	}
}
