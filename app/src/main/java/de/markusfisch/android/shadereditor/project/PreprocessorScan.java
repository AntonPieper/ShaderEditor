package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * The preprocessor structure of GLSL text, one physical line at a time.
 * Text inside comments is ignored: {@code code} holds the lines with
 * every comment replaced by spaces. Conditions are not evaluated and
 * macros are not expanded. {@code openComments} tells for every line
 * whether a block comment is still open at its end.
 *
 * Comments and directive lines are those of {@link GlslLexer}, so the
 * build reads a file as the editor's tokenizer does. Both follow GLSL ES 3.00
 * for every file: a backslash at the end of a line continues a //
 * comment or a directive onto the next line, and a "#" on that line
 * starts no directive. GLSL ES 1.00 has no line continuation, but a file
 * says which version it is only on its #version line, after the first
 * comment may already have been read.
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
		Lexed lexed = new Lexed(lines);
		List<Directive> directives = new ArrayList<>();
		List<Integer> depths = new ArrayList<>(lines.size());
		List<ProjectDiagnostic> diagnostics = new ArrayList<>();
		Deque<Integer> open = new ArrayDeque<>();
		for (int i = 0; i < lines.size(); ++i) {
			int hash = lexed.hashes[i];
			Directive directive = hash < 0
					? null
					: parse(lexed.code.get(i).substring(hash + 1), i + 1,
							open.size());
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
		if (lexed.unclosed > 0) {
			diagnostics.add(error("Block comment is not closed in this file",
					file, lexed.unclosed));
		}
		return new PreprocessorScan(List.copyOf(lexed.code),
				List.copyOf(directives), List.copyOf(depths),
				List.copyOf(lexed.openComments), List.copyOf(diagnostics));
	}

	/** Number of conditional blocks around a 1-based line. */
	public int depthAt(int line) {
		return depths.get(line - 1);
	}

	// The text after a "#" that starts a directive.
	private static Directive parse(String text, int line, int open) {
		text = text.stripLeading();
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

	// Comments and directive starts of the lines joined by "\n". A "\r"
	// inside a line is whitespace, not a line end.
	private static final class Lexed {
		private final List<String> code;
		private final List<Boolean> openComments;
		private final int[] hashes;
		private int unclosed;

		private Lexed(List<String> lines) {
			int n = lines.size();
			String text = String.join("\n", lines);
			char[] chars = text.toCharArray();
			int[] starts = new int[n + 1];
			int[] cuts = new int[n];
			boolean[] open = new boolean[n];
			hashes = new int[n];
			for (int i = 0; i < n; ++i) {
				starts[i + 1] = starts[i] + lines.get(i).length() + 1;
				cuts[i] = starts[i + 1] - 1;
				hashes[i] = -1;
			}
			Tokens tokens = GlslLexer.lex(text.replace('\r', ' '));
			for (int t = 0; t < tokens.size(); ++t) {
				int start = tokens.start(t);
				int end = tokens.end(t);
				int line = lineOf(starts, start);
				Style style = tokens.style(t);
				if (style == Style.DIRECTIVE && chars[start] == '#') {
					hashes[line] = start - starts[line];
				} else if (style != Style.COMMENT) {
					continue;
				} else if (chars[start + 1] == '/') {
					cuts[line] = start;
					for (int l = line + 1; l < n && starts[l] < end; ++l) {
						cuts[l] = starts[l];
					}
				} else {
					boolean closed = end - start > 3 &&
							text.startsWith("*/", end - 2);
					for (int i = start; i < end; ++i) {
						if (chars[i] != '\n') {
							chars[i] = ' ';
						}
					}
					for (int l = line; l < n &&
							(starts[l + 1] - 1 < end || !closed); ++l) {
						open[l] = true;
					}
					if (!closed) {
						unclosed = line + 1;
					}
				}
			}
			code = new ArrayList<>(n);
			openComments = new ArrayList<>(n);
			for (int i = 0; i < n; ++i) {
				code.add(new String(chars, starts[i], cuts[i] - starts[i]));
				openComments.add(open[i]);
			}
		}

		private static int lineOf(int[] starts, int offset) {
			int i = Arrays.binarySearch(starts, offset);
			return i >= 0 ? i : -i - 2;
		}
	}

	private static ProjectDiagnostic error(
			String message,
			ProjectPath file,
			int line) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				message, file, line);
	}
}
