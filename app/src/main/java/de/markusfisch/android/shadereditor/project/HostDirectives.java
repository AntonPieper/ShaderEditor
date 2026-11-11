package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * @param diagnostics assembly diagnostics followed by directive ones
 */
public record HostDirectives(
		List<String> lines,
		SourceMap sourceMap,
		List<Input> inputs,
		Target target,
		List<ProjectDiagnostic> diagnostics) {
	public static final Set<String> ENGINE_SAMPLERS = Set.of(
			"backbuffer", "cameraBack", "cameraFront");

	private static final Set<String> SAMPLER_TYPES = Set.of(
			"sampler2D", "samplerCube", "samplerExternalOES");
	private static final Set<String> PRECISIONS = Set.of(
			"lowp", "mediump", "highp");
	private static final Pattern TOKEN = Pattern.compile(
			"[A-Za-z_][A-Za-z0-9_]*|\\S");
	private static final String SHAPE =
			"write it as: uniform sampler2D name;";

	public HostDirectives {
		lines = List.copyOf(lines);
		inputs = List.copyOf(inputs);
		diagnostics = List.copyOf(diagnostics);
	}

	public static HostDirectives parse(SourceAssembly assembly) {
		Parser parser = new Parser(assembly);
		parser.run();
		List<ProjectDiagnostic> diagnostics =
				new ArrayList<>(assembly.diagnostics());
		diagnostics.addAll(parser.diagnostics);
		return new HostDirectives(parser.lines, assembly.sourceMap(),
				parser.inputs, parser.target, diagnostics);
	}

	public String text() {
		return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
	}

	private record Declaration(String type, String name) {
	}

	private static final class Parser {
		private final List<String> lines;
		private final List<String> raw;
		private final SourceMap map;
		private final PreprocessorScan scan;
		private final PreprocessorScan.Directive[] byLine;
		private final List<Input> inputs = new ArrayList<>();
		private final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
		private Target target;
		private int targetLine;

		private Parser(SourceAssembly assembly) {
			raw = assembly.lines();
			lines = new ArrayList<>(raw);
			map = assembly.sourceMap();
			scan = PreprocessorScan.scan(raw, null);
			byLine = new PreprocessorScan.Directive[raw.size() + 1];
			for (PreprocessorScan.Directive directive : scan.directives()) {
				byLine[directive.line()] = directive;
			}
		}

		private void run() {
			for (PreprocessorScan.Directive directive : scan.directives()) {
				SourceLocation at = map.locate(directive.line());
				if (directive.kind() != PreprocessorScan.Kind.PRAGMA ||
						at == null || at.isGenerated()) {
					continue;
				}
				ProjectDiagnostic misspelled = HostDirective.checkSpelling(
						directive.argument(), at);
				if (misspelled != null) {
					diagnostics.add(misspelled);
				}
				if (!HostDirective.isHostPragma(directive.argument())) {
					continue;
				}
				lines.set(directive.line() - 1, "");
				if (at.includedFrom() != null) {
					error(at, "Host directives are not allowed in included " +
							"files (included from " + at.includedFrom() +
							"), write them in the shader that includes " +
							"this file and pass samplers as parameters");
				} else if (directive.depth() > 0) {
					error(at, "Host directives are not allowed inside " +
							"#if, #ifdef or #ifndef, move it outside the " +
							"conditional block");
				} else {
					parse(directive.line(), at);
				}
			}
		}

		private void parse(int line, SourceLocation at) {
			String text = raw.get(line - 1);
			int i = scan.code().get(line - 1).indexOf('#') + 1;
			while (Character.isWhitespace(text.charAt(i))) {
				++i;
			}
			i += "pragma".length();
			HostDirective directive = HostDirective.parse(text.substring(i),
					at);
			if (directive.command() == HostDirective.Command.TARGET) {
				if (targetLine > 0) {
					error(at, "Only one #pragma se target per file, merge " +
							"it into the one on line " + targetLine);
					return;
				}
				targetLine = at.line();
				target = directive.target();
			}
			diagnostics.addAll(directive.diagnostics());
			if (directive.command() == HostDirective.Command.INPUT &&
					directive.input() != null) {
				input(directive.input(), directive.diagnostics().isEmpty(),
						line, at);
			}
		}

		private void input(Input parsed, boolean valid, int line,
				SourceLocation at) {
			Declaration declaration = attach(line, at);
			if (declaration == null || !valid) {
				return;
			}
			int errors = diagnostics.size();
			String type = declaration.type;
			String name = declaration.name;
			ProjectPath source = parsed.source();
			ProjectPath.Kind kind = source != null ? source.getKind() : null;
			if (parsed.wrapS() != null && type.equals("samplerCube")) {
				error(at, "wrap= does not apply to samplerCube, remove it");
			}
			if (kind == ProjectPath.Kind.SHADER &&
					!type.equals("sampler2D")) {
				error(at, "A .glsl source is read as uniform sampler2D " +
						name + ", not " + type);
			} else if (kind == ProjectPath.Kind.IMAGE &&
					!type.equals("sampler2D") && !type.equals("samplerCube")) {
				error(at, "An image source is read as sampler2D or " +
						"samplerCube, not " + type);
			}
			if (source == null && !ENGINE_SAMPLERS.contains(name)) {
				error(at, name + " is not an engine sampler, add a path " +
						"such as #pragma se input \"assets/" + name +
						".png\", or use backbuffer, cameraBack or cameraFront");
			} else if (source != null && ENGINE_SAMPLERS.contains(name)) {
				error(at, name + " is reserved for the engine sampler, " +
						"rename the uniform to bind " + source);
			}
			if (diagnostics.size() == errors) {
				inputs.add(new Input(parsed.file(), parsed.line(), name, type,
						source, parsed.frame(), parsed.min(), parsed.mag(),
						parsed.mipmap(), parsed.wrapS(), parsed.wrapT()));
			}
		}

		private Declaration attach(int line, SourceLocation at) {
			StringBuilder text = new StringBuilder();
			for (int l = line + 1; l <= lines.size(); ++l) {
				SourceLocation here = map.locate(l);
				if (here.isGenerated()) {
					continue;
				} else if (here.includedFrom() != null) {
					error(at, "The declaration after #pragma se input must " +
							"be in the same file, not in an included file, " +
							SHAPE);
					return null;
				}
				PreprocessorScan.Directive directive = byLine[l];
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
				String code = scan.code().get(l - 1);
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
