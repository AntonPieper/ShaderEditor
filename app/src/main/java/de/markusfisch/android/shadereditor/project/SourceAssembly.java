package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One shader file with its literal includes expanded statically.
 */
public final class SourceAssembly {
	public record Dependency(ProjectPath path, String revision) {
	}

	/**
	 * One assembled line as the scan of its own file saw it: the raw
	 * text, the text with comments blanked, where it comes from, whether
	 * an include lies between it and the line before, and the directive
	 * on it, or null.
	 */
	record Line(
			String raw,
			String code,
			SourceLocation at,
			boolean boundary,
			PreprocessorScan.Directive directive) {
		/**
		 * Whether a block comment starts, ends or lies on this line;
		 * otherwise code is raw without a trailing // comment.
		 */
		boolean blockComment() {
			return !raw.startsWith(code);
		}
	}

	private final List<Line> source;
	private final List<String> lines;
	private final List<ProjectDiagnostic> diagnostics;
	private final List<Dependency> dependencies;
	private final int versionLine;
	private final SourceMap sourceMap;

	private SourceAssembly(
			List<Line> source,
			List<ProjectDiagnostic> diagnostics,
			List<Dependency> dependencies,
			int versionLine) {
		this.source = List.copyOf(source);
		this.diagnostics = List.copyOf(diagnostics);
		this.dependencies = List.copyOf(dependencies);
		this.versionLine = versionLine;
		List<String> lines = new ArrayList<>(source.size());
		List<SourceLocation> map = new ArrayList<>(source.size());
		for (Line line : source) {
			lines.add(line.raw);
			map.add(line.at);
		}
		this.lines = List.copyOf(lines);
		sourceMap = new SourceMap(map);
	}

	public static SourceAssembly assemble(
			ProjectFiles files,
			ProjectPath entry,
			ProjectLimits limits) {
		Assembler assembler = new Assembler(files, entry, limits);
		assembler.expand(entry, null);
		return new SourceAssembly(assembler.assembled, assembler.diagnostics,
				assembler.dependencies, assembler.versionLine);
	}

	/** Assembled lines; incomplete when diagnostics contain an error. */
	public List<String> lines() {
		return lines;
	}

	/** The origin of every assembled line. */
	public SourceMap sourceMap() {
		return sourceMap;
	}

	/** Problems with file and line. */
	public List<ProjectDiagnostic> diagnostics() {
		return diagnostics;
	}

	/**
	 * Every file read, in order, with the revision used; a null revision
	 * means the file was absent.
	 */
	public List<Dependency> dependencies() {
		return dependencies;
	}

	/** 1-based assembled line of {@code #version}, or 0. */
	public int versionLine() {
		return versionLine;
	}

	List<Line> source() {
		return source;
	}

	public String text() {
		return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
	}

	public boolean hasErrors() {
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Return this assembly with generated lines after {@code #version},
	 * or at the top when there is none.
	 */
	public SourceAssembly withGeneratedLines(List<String> generated) {
		List<Line> updated = new ArrayList<>(source);
		List<Line> inserted = new ArrayList<>();
		for (String text : generated) {
			inserted.add(new Line(text, text, SourceLocation.GENERATED, false,
					null));
		}
		updated.addAll(versionLine, inserted);
		return new SourceAssembly(updated, diagnostics, dependencies,
				versionLine);
	}

	private record LoadedFile(List<String> lines, PreprocessorScan scan) {
	}

	private static final class Assembler {
		private final List<Line> assembled = new ArrayList<>();
		private final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
		private final List<Dependency> dependencies = new ArrayList<>();
		private final Map<ProjectPath, LoadedFile> loaded = new HashMap<>();
		private final Map<ProjectPath, String> unreadable = new HashMap<>();
		private final List<ProjectPath> stack = new ArrayList<>();
		private final ProjectFiles files;
		private final ProjectPath entry;
		private final ProjectLimits limits;
		private int versionLine;
		private long bytes;
		private boolean stopped;
		private boolean boundary;

		private Assembler(
				ProjectFiles files,
				ProjectPath entry,
				ProjectLimits limits) {
			this.files = files;
			this.entry = entry;
			this.limits = limits;
		}

		private void expand(ProjectPath file, SourceLocation from) {
			LoadedFile source = load(file, from);
			if (source == null) {
				return;
			}
			stack.add(file);
			List<PreprocessorScan.Directive> directives =
					source.scan.directives();
			int next = 0;
			for (int i = 0; i < source.lines.size() && !stopped; ++i) {
				SourceLocation here = new SourceLocation(file, i + 1, from);
				PreprocessorScan.Directive directive = null;
				if (next < directives.size() &&
						directives.get(next).line() == i + 1) {
					directive = directives.get(next++);
				}
				Line line = new Line(source.lines.get(i),
						source.scan.code().get(i), here, boundary, directive);
				if (directive != null &&
						directive.kind() == PreprocessorScan.Kind.INCLUDE) {
					if (line.blockComment()) {
						error(here, "Block comments are not allowed on a " +
								"line with #include, use a trailing // comment");
					}
					boundary = true;
					include(file, directive.argument(), here);
					boundary = true;
					continue;
				}
				if (directive != null && from == null && versionLine == 0 &&
						directive.kind() == PreprocessorScan.Kind.VERSION) {
					versionLine = assembled.size() + 1;
				}
				emit(line);
			}
			stack.remove(stack.size() - 1);
		}

		private void include(
				ProjectPath file,
				String operand,
				SourceLocation here) {
			if (operand.startsWith("<")) {
				error(here, "Angle-bracket includes are not supported, " +
						"use #include \"relative/path\"");
				return;
			} else if (!operand.startsWith("\"")) {
				error(here, "#include needs a literal \"relative/path\", " +
						"macros are not expanded: " + operand);
				return;
			}
			int close = operand.indexOf('"', 1);
			if (close < 0) {
				error(here, "Include path has no closing quote");
				return;
			} else if (!operand.substring(close + 1).isBlank()) {
				error(here, "Unexpected text after include path: " +
						operand.substring(close + 1).strip());
				return;
			}
			ProjectParseResult<ProjectPath> target = ProjectPath.resolve(
					file, operand.substring(1, close));
			if (!target.isSuccess()) {
				error(here, target.diagnostic().message());
			} else if (stack.contains(target.value())) {
				StringBuilder chain = new StringBuilder("Include cycle: ");
				for (ProjectPath path : stack) {
					chain.append(path).append(" -> ");
				}
				error(here, chain.append(target.value()).toString());
			} else if (stack.size() > limits.includeDepth()) {
				error(here, "Includes are nested deeper than the limit of " +
						limits.includeDepth());
			} else {
				expand(target.value(), here);
			}
		}

		private LoadedFile load(ProjectPath file, SourceLocation from) {
			SourceLocation site = from != null
					? from
					: new SourceLocation(file, 0, null);
			if (loaded.containsKey(file)) {
				LoadedFile source = loaded.get(file);
				if (source == null) {
					error(site, unreadable.get(file));
				}
				return source;
			}
			if (loaded.size() >= limits.filesRead()) {
				error(site, "Build reads more than the limit of " +
						limits.filesRead() + " files");
				stopped = true;
				return null;
			}
			ProjectFiles.Content content = files.read(file);
			loaded.put(file, null);
			if (content == null) {
				dependencies.add(new Dependency(file, null));
				return unreadable(site, file, "File not found: " + file);
			}
			dependencies.add(new Dependency(file, content.revision()));
			String text = SourceText.decode(content.bytes());
			if (text == null) {
				return unreadable(site, file, "File is not UTF-8 text: " + file);
			}
			List<String> lines = SourceText.lines(text);
			PreprocessorScan scan = PreprocessorScan.scan(lines, file);
			for (ProjectDiagnostic problem : scan.diagnostics()) {
				error(new SourceLocation(file, problem.line(), from),
						problem.message());
			}
			for (PreprocessorScan.Directive directive : scan.directives()) {
				SourceLocation at = new SourceLocation(file,
						directive.line(), from);
				if (directive.kind() == PreprocessorScan.Kind.VERSION &&
						!file.equals(entry)) {
					error(at, "#version belongs in the running shader, " +
							"not in an included file");
				} else if (directive.kind() == PreprocessorScan.Kind.PRAGMA &&
						"once".equals(directive.argument())) {
					report(ProjectDiagnostic.Severity.WARNING, at,
							"#pragma once is not supported, use an include " +
									"guard: #ifndef NAME, #define NAME, #endif");
				}
			}
			LoadedFile source = new LoadedFile(lines, scan);
			loaded.put(file, source);
			return source;
		}

		private LoadedFile unreadable(
				SourceLocation site,
				ProjectPath file,
				String message) {
			unreadable.put(file, message);
			error(site, message);
			return null;
		}

		private void emit(Line line) {
			bytes += line.raw().getBytes(StandardCharsets.UTF_8).length + 1;
			if (bytes > limits.sourceBytes()) {
				error(line.at(), "Assembled source is larger than the limit " +
						"of " + limits.sourceBytes() + " bytes");
				stopped = true;
				return;
			}
			assembled.add(line);
			boundary = false;
		}

		private void error(SourceLocation at, String message) {
			report(ProjectDiagnostic.Severity.ERROR, at, message);
		}

		private void report(
				ProjectDiagnostic.Severity severity,
				SourceLocation at,
				String message) {
			StringBuilder sb = new StringBuilder(message);
			String separator = " (included from ";
			for (SourceLocation site = at.includedFrom(); site != null;
					site = site.includedFrom()) {
				sb.append(separator).append(site);
				separator = ", ";
			}
			if (at.includedFrom() != null) {
				sb.append(')');
			}
			diagnostics.add(new ProjectDiagnostic(severity, sb.toString(),
					at.file(), at.line()));
		}
	}
}
