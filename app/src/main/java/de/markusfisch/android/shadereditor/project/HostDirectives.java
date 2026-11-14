package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.HostDirective.Input;
import de.markusfisch.android.shadereditor.project.HostDirective.Target;

/**
 * The host directives of one assembled pass file: {@code #pragma se
 * input} and {@code #pragma se target}. Their lines are blanked in
 * {@code lines} so the GLSL compiler never sees them while line numbers
 * and the source map stay valid. A null option was not written.
 *
 * @param lines       assembled lines with host directives blanked
 * @param sourceMap   the origin of every line
 * @param inputs      inputs attached to their declarations, in order
 * @param target      the target directive, or null when there is none
 * @param references  paths named by directives that are neither in
 *                    inputs nor the target because they are invalid;
 *                    they are still dependencies
 * @param diagnostics assembly diagnostics followed by directive ones
 */
public record HostDirectives(
		List<String> lines,
		SourceMap sourceMap,
		List<Input> inputs,
		Target target,
		List<Reference> references,
		List<ProjectDiagnostic> diagnostics) {
	/** A path named on a 1-based line of file. */
	public record Reference(ProjectPath path, ProjectPath file, int line) {
	}

	private static final Set<String> SAMPLER_TYPES = Set.of(
			"sampler2D", "samplerCube", "samplerExternalOES");
	// What GLSL reads as whitespace besides the space and line ends.
	private static final String WHITESPACE = "\t\u000b\f\r";
	private static final Set<String> PRECISIONS = Set.of(
			"lowp", "mediump", "highp");
	private static final Pattern TOKEN = Pattern.compile(
			"[A-Za-z_][A-Za-z0-9_]*|\\S");
	private static final String SHAPE =
			"write it as: uniform sampler2D name;";

	public HostDirectives {
		lines = List.copyOf(lines);
		inputs = List.copyOf(inputs);
		references = List.copyOf(references);
		diagnostics = List.copyOf(diagnostics);
	}

	/**
	 * Parse the host directives of an assembled pass file. reserved are
	 * the GLSL types of the engine's builtin uniforms by name: an input
	 * with a path cannot use one, an input without a path must name an
	 * engine sampler of its type.
	 */
	public static HostDirectives parse(
			SourceAssembly assembly,
			Map<String, String> reserved) {
		Parser parser = new Parser(assembly, reserved);
		parser.run();
		List<ProjectDiagnostic> diagnostics =
				new ArrayList<>(assembly.diagnostics());
		diagnostics.addAll(parser.diagnostics);
		return new HostDirectives(parser.lines, assembly.sourceMap(),
				parser.inputs, parser.target, parser.references, diagnostics);
	}

	public String text() {
		return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
	}

	private record Declaration(String type, String name) {
	}

	private static final class Parser {
		private final List<SourceAssembly.Line> source;
		private final Map<String, String> reserved;
		private final List<String> lines;
		private final List<Input> inputs = new ArrayList<>();
		private final List<Reference> references = new ArrayList<>();
		private final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
		private Target target;
		private int targetLine;

		private Parser(SourceAssembly assembly, Map<String, String> reserved) {
			source = assembly.source();
			this.reserved = reserved;
			lines = new ArrayList<>(assembly.lines());
		}

		private void run() {
			for (int line = 1; line <= source.size(); ++line) {
				SourceAssembly.Line here = source.get(line - 1);
				PreprocessorScan.Directive directive = here.directive();
				SourceLocation at = here.at();
				if (directive == null ||
						directive.kind() != PreprocessorScan.Kind.PRAGMA) {
					continue;
				}
				String argument = directive.argument();
				ProjectDiagnostic misspelled = HostDirective.checkSpelling(
						argument, at);
				if (misspelled != null) {
					diagnostics.add(misspelled);
				}
				if (argument.endsWith("\\") &&
						HostDirective.isHostPragma(continued(line))) {
					error(at, "#pragma se cannot be continued with \\, " +
							"write the host directive on one line");
					continue;
				} else if (!HostDirective.isHostPragma(argument)) {
					continue;
				}
				lines.set(line - 1, "");
				HostDirective parsed = HostDirective.parse(argument, at);
				if (here.blockComment()) {
					error(at, "Block comments are not allowed on a line " +
							"with a host directive, use a trailing // comment");
				} else if (at.includedFrom() != null) {
					error(at, "Host directives are not allowed in included " +
							"files (included from " + at.includedFrom() +
							"), write them in the shader that includes " +
							"this file and pass samplers as parameters");
				} else if (directive.depth() > 0) {
					error(at, "Host directives are not allowed inside " +
							"#if, #ifdef or #ifndef, move it outside the " +
							"conditional block");
				} else if (apply(parsed, line, at)) {
					continue;
				}
				if (parsed.path() != null) {
					references.add(new Reference(parsed.path(), at.file(),
							at.line()));
				}
			}
			checkCharacters();
		}

		// Shader compilers read ASCII, and some crash on other characters;
		// in a comment or a consumed directive, such as a path, they are
		// allowed.
		private void checkCharacters() {
			for (int i = 0; i < source.size(); ++i) {
				String code = source.get(i).code();
				for (int c = 0; !lines.get(i).isEmpty() && c < code.length(); ) {
					int character = code.codePointAt(c);
					if (character > 0x7e || (character < 0x20 &&
							WHITESPACE.indexOf(character) < 0)) {
						error(source.get(i).at(), String.format(Locale.ROOT,
								"Character %s (U+%04X) outside a comment, " +
										"shader compilers read only ASCII " +
										"code; replace it",
								new String(Character.toChars(character)),
								character));
						break;
					}
					c += Character.charCount(character);
				}
			}
		}

		// The pragma text that backslashes at line ends join to a line.
		private String continued(int line) {
			StringBuilder text = new StringBuilder();
			String part = source.get(line - 1).directive().argument();
			while (part.endsWith("\\")) {
				text.append(part, 0, part.length() - 1);
				if (line >= source.size() || source.get(line).boundary()) {
					return text.toString().strip();
				}
				part = source.get(line++).code();
			}
			return text.append(part).toString().strip();
		}

		// Return whether the directive became an input or the target.
		private boolean apply(HostDirective directive, int line,
				SourceLocation at) {
			if (directive.command() == HostDirective.Command.TARGET) {
				if (targetLine > 0) {
					error(at, "Only one #pragma se target per file, merge " +
							"it into the one on line " + targetLine);
					return false;
				}
				targetLine = at.line();
				target = directive.target();
			}
			diagnostics.addAll(directive.diagnostics());
			if (directive.command() == HostDirective.Command.INPUT &&
					directive.input() != null) {
				return input(directive.input(),
						directive.diagnostics().isEmpty(), line, at);
			}
			return directive.target() != null;
		}

		private boolean input(Input parsed, boolean valid, int line,
				SourceLocation at) {
			Declaration declaration = attach(line, at);
			if (declaration == null || !valid) {
				return false;
			}
			int errors = diagnostics.size();
			String type = declaration.type;
			String name = declaration.name;
			ProjectPath source = parsed.source();
			if (parsed.wrapS() != null && type.equals("samplerCube")) {
				error(at, "wrap= does not apply to samplerCube, remove it");
			}
			// Whether the file is a picture or a pass narrows this once
			// it is read.
			if (source != null && !type.equals("sampler2D") &&
					!type.equals("samplerCube")) {
				error(at, "A file is read as sampler2D or samplerCube, " +
						"not " + type);
			}
			String builtin = reserved.get(name);
			if (source == null && builtin != null &&
					builtin.startsWith("sampler") && !builtin.equals(type)) {
				error(at, name + " is an engine sampler of type " + builtin +
						", declare it as uniform " + builtin + " " + name);
			} else if (source == null && !type.equals(builtin)) {
				error(at, name + " is not an engine sampler, add a path " +
						"such as #pragma se input \"assets/" + name +
						".png\"");
			} else if (source != null && builtin != null) {
				error(at, name + " is reserved for a builtin uniform, " +
						"rename the uniform to bind " + source);
			}
			if (diagnostics.size() > errors) {
				return false;
			}
			inputs.add(new Input(parsed.file(), parsed.line(), name, type,
					source, parsed.frame(), parsed.min(), parsed.mag(),
					parsed.mipmap(), parsed.wrapS(), parsed.wrapT()));
			return true;
		}

		private Declaration attach(int line, SourceLocation at) {
			StringBuilder text = new StringBuilder();
			for (int l = line + 1; l <= source.size(); ++l) {
				SourceAssembly.Line next = source.get(l - 1);
				SourceLocation here = next.at();
				if (here.isGenerated()) {
					continue;
				} else if (next.boundary()) {
					error(at, "The declaration after #pragma se input must " +
							"follow it in the same file, with no #include " +
							"in between, " + SHAPE);
					return null;
				}
				PreprocessorScan.Directive directive = next.directive();
				if (directive != null) {
					boolean input = directive.kind() ==
							PreprocessorScan.Kind.PRAGMA &&
							directive.argument().matches("se\\s+input\\b.*");
					error(at, input
							? "Only one #pragma se input may annotate a " +
									"declaration, remove one of them"
							: "Only comments may separate #pragma se input " +
									"from its declaration, found #" +
									directive.name() + " on line " +
									here.line());
					return null;
				}
				String code = next.code();
				text.append(code).append('\n');
				if (code.indexOf(';') > -1 || code.indexOf('{') > -1) {
					break;
				}
			}
			return declaration(text.toString(), at);
		}

		private Declaration declaration(String text, SourceLocation at) {
			List<String> tokens = new ArrayList<>();
			for (Matcher m = TOKEN.matcher(text); m.find(); ) {
				tokens.add(m.group());
			}
			tokens.addAll(Collections.nCopies(4, ""));
			int i = 0;
			String first = tokens.get(0);
			if (first.isEmpty()) {
				error(at, "#pragma se input needs a declaration on the " +
						"next line, " + SHAPE);
				return null;
			} else if (first.equals("layout")) {
				error(at, "Layout qualifiers are not supported on an " +
						"input declaration, " + SHAPE);
				return null;
			} else if (!first.equals("uniform")) {
				error(at, "#pragma se input must be followed by a direct " +
						"uniform declaration, not '" + first + "'; " +
						"macro-generated declarations are not supported, " +
						SHAPE);
				return null;
			}
			if (PRECISIONS.contains(tokens.get(++i))) {
				++i;
			}
			String type = tokens.get(i++);
			if (type.equals("struct")) {
				error(at, "Struct uniforms cannot be inputs, " + SHAPE);
				return null;
			} else if (!SAMPLER_TYPES.contains(type)) {
				error(at, "An input needs a sampler2D or samplerCube " +
						"uniform, not '" + type + "', " + SHAPE);
				return null;
			}
			String name = tokens.get(i++);
			String end = tokens.get(i);
			if (!Character.isJavaIdentifierStart(
					name.isEmpty() ? ' ' : name.charAt(0))) {
				error(at, "Expected a uniform name after " + type + ", " +
						SHAPE);
			} else if (end.equals("[")) {
				error(at, "Array uniforms cannot be inputs, declare one " +
						"uniform per input, " + SHAPE);
			} else if (end.equals(",")) {
				error(at, "An input annotates exactly one uniform, split " +
						"the comma-separated declaration, " + SHAPE);
			} else if (!end.equals(";")) {
				error(at, "Expected ';' after uniform " + name + ", " +
						SHAPE);
			} else {
				return new Declaration(type, name);
			}
			return null;
		}

		private void error(SourceLocation at, String message) {
			diagnostics.add(new ProjectDiagnostic(
					ProjectDiagnostic.Severity.ERROR, message, at.file(),
					at.line()));
		}
	}
}
