package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The preprocessor structure of GLSL text, one physical line at a time.
 * Text inside comments is ignored: {@code code} holds the lines with
 * every comment replaced by spaces. Conditions are not evaluated and
 * macros are not expanded. {@code openComments} tells for every line
 * whether a block comment is still open at its end.
 */
public record PreprocessorScan(
		List<String> code,
		List<Directive> directives,
		List<Integer> depths,
		List<Boolean> openComments,
		List<ProjectDiagnostic> diagnostics) {
	public enum Kind {
		INCLUDE,
		VERSION,
		EXTENSION,
		PRAGMA,
		IF,
		IFDEF,
		IFNDEF,
		ELIF,
		ELSE,
		ENDIF,
		OTHER
	}

	/**
	 * A directive on a 1-based line. {@code name} is the directive name
	 * as written, {@code argument} the rest of the line without comments,
	 * and {@code depth} the number of conditional blocks around the line.
	 */
	public record Directive(
			Kind kind,
			String name,
			String argument,
			int line,
			int depth) {
	}

	/**
	 * Scan the lines of one file; diagnostics name file, which may be
	 * null for text that is not a single file.
	 */
	public static PreprocessorScan scan(List<String> lines, ProjectPath file) {
		List<String> code = new ArrayList<>(lines.size());
		List<Directive> directives = new ArrayList<>();
		List<Integer> depths = new ArrayList<>(lines.size());
		List<Boolean> openComments = new ArrayList<>(lines.size());
		List<ProjectDiagnostic> diagnostics = new ArrayList<>();
		Deque<Integer> open = new ArrayDeque<>();
		boolean[] inComment = {false};
		int commentStart = 0;
		for (int i = 0; i < lines.size(); ++i) {
			boolean continued = inComment[0];
			String stripped = stripComments(lines.get(i), inComment);
			if (!inComment[0]) {
				commentStart = 0;
			} else if (!continued) {
				commentStart = i + 1;
			}
			code.add(stripped);
			openComments.add(inComment[0]);
			Directive directive = parse(stripped, i + 1, open.size());
			depths.add(directive != null ? directive.depth : open.size());
			if (directive == null) {
				continue;
			}
			switch (directive.kind) {
				case IF, IFDEF, IFNDEF -> open.push(i + 1);
				case ELIF, ELSE, ENDIF -> {
					if (open.isEmpty()) {
						diagnostics.add(error("#" + directive.name +
								" without #if in this file", file, i + 1));
					} else if (directive.kind == Kind.ENDIF) {
						open.pop();
					}
				}
				default -> {
				}
			}
			directives.add(directive);
		}
		for (int line : open) {
			diagnostics.add(error("Conditional is not closed by #endif " +
					"in this file", file, line));
		}
		if (commentStart > 0) {
			diagnostics.add(error("Block comment is not closed in this file",
					file, commentStart));
		}
		return new PreprocessorScan(List.copyOf(code), List.copyOf(directives),
				List.copyOf(depths), List.copyOf(openComments),
				List.copyOf(diagnostics));
	}

	/** Number of conditional blocks around a 1-based line. */
	public int depthAt(int line) {
		return depths.get(line - 1);
	}

	// inComment carries an open block comment from line to line.
	private static String stripComments(String line, boolean[] inComment) {
		StringBuilder sb = new StringBuilder(line);
		boolean directive = false;
		boolean code = false;
		for (int i = 0, n = line.length(); i < n; ++i) {
			char c = line.charAt(i);
			char next = i + 1 < n ? line.charAt(i + 1) : 0;
			if (inComment[0]) {
				sb.setCharAt(i, ' ');
				if (c == '*' && next == '/') {
					sb.setCharAt(++i, ' ');
					inComment[0] = false;
				}
			} else if (c == '/' && next == '/') {
				sb.setLength(i);
				break;
			} else if (c == '/' && next == '*') {
				sb.setCharAt(i, ' ');
				sb.setCharAt(++i, ' ');
				inComment[0] = true;
			} else if (c == '"' && directive) {
				int close = line.indexOf('"', i + 1);
				i = close < 0 ? n : close;
			} else if (!code && !Character.isWhitespace(c)) {
				code = true;
				directive = c == '#';
			}
		}
		return sb.toString();
	}

	private static Directive parse(String code, int line, int open) {
		String text = code.strip();
		if (!text.startsWith("#")) {
			return null;
		}
		text = text.substring(1).stripLeading();
		int end = 0;
		while (end < text.length() &&
				Character.isJavaIdentifierPart(text.charAt(end))) {
			++end;
		}
		if (end == 0) {
			return null;
		}
		String name = text.substring(0, end);
		Kind kind = switch (name) {
			case "include" -> Kind.INCLUDE;
			case "version" -> Kind.VERSION;
			case "extension" -> Kind.EXTENSION;
			case "pragma" -> Kind.PRAGMA;
			case "if" -> Kind.IF;
			case "ifdef" -> Kind.IFDEF;
			case "ifndef" -> Kind.IFNDEF;
			case "elif" -> Kind.ELIF;
			case "else" -> Kind.ELSE;
			case "endif" -> Kind.ENDIF;
			default -> Kind.OTHER;
		};
		boolean closes = kind == Kind.ELIF || kind == Kind.ELSE ||
				kind == Kind.ENDIF;
		return new Directive(kind, name, text.substring(end).strip(), line,
				closes && open > 0 ? open - 1 : open);
	}

	private static ProjectDiagnostic error(
			String message,
			ProjectPath file,
			int line) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				message, file, line);
	}
}
