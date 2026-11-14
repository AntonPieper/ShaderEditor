package de.markusfisch.android.shadereditor.project;

import java.util.List;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Style;

/**
 * GLSL as a project reads it. What a pass file is made of is found by
 * {@link SourceAssembly}, {@link HostDirectives} and
 * {@link PreprocessorScan}; this is where the rest of the project
 * layer reaches them, through {@link ShaderLanguage}.
 */
final class Glsl {
	/** What a pass must define, as messages name it. */
	static final String ENTRY_POINT = "main";

	private static final Pattern MAIN = Pattern.compile("\\bmain\\b");

	private Glsl() {
	}

	/**
	 * Read the pass file with its includes expanded and its
	 * {@code #pragma se} lines parsed.
	 */
	static PassSource pass(ProjectFiles files, ProjectPath file,
			ProjectLimits limits, Builtins builtins) {
		SourceAssembly assembly = SourceAssembly.assemble(files, file,
				limits);
		HostDirectives directives = HostDirectives.parse(assembly, builtins);
		List<String> code = PreprocessorScan.scan(directives.lines(),
				null).code();
		boolean main = false;
		for (String line : code) {
			if (MAIN.matcher(line).find()) {
				main = true;
				break;
			}
		}
		return new PassSource(directives.lines(), code,
				directives.sourceMap(), assembly.versionLine(),
				directives.inputs(), directives.target(),
				directives.references(), directives.diagnostics(), main);
	}

	/** Return how a texture read as kind is declared under name. */
	static String declaration(TextureKind kind, String name) {
		return "uniform " + HostDirectives.keyword(kind) + " " + name;
	}

	// Whether text declares a function main outside any other block and
	// outside "#if 0", and gives it a body, as the lexer reads it. Reads
	// one line at a time and keeps only the tokens of that line.
	static boolean definesMain(Text text) {
		LineTokens tokens = new LineTokens();
		int state = GlslLexer.TEXT_START;
		int braces = 0;
		int level = 0;
		int disabled = -1;
		int parens = 0;
		// 0: nothing, 1: after "main", 2: in its parameters, 3: after them.
		int stage = 0;
		boolean word = false;
		for (int n = 0, lines = text.lineCount(); n < lines; ++n) {
			String line = text.line(n);
			state = GlslLexer.lexLine(line, n < lines - 1, state, tokens);
			String directive = line.trim();
			if (directive.startsWith("#")) {
				directive = directive.substring(1).trim();
				if (directive.startsWith("if")) {
					++level;
					if (disabled < 0 && directive.matches("if\\s+0\\b.*")) {
						disabled = level;
					}
				} else if (directive.startsWith("endif")) {
					if (disabled == level) {
						disabled = -1;
					}
					--level;
				} else if (directive.startsWith("el") && disabled == level) {
					disabled = -1;
				}
			}
			for (int i = 0, size = tokens.size(); i < size; ++i) {
				if (disabled >= 0 || tokens.style(i) == Style.COMMENT ||
						tokens.inDirective(i)) {
					continue;
				}
				String token = line.substring(tokens.start(i),
						tokens.end(i));
				char c = token.charAt(0);
				if (stage == 1) {
					stage = c == '(' ? 2 : 0;
					parens = 1;
				} else if (stage == 2) {
					parens += c == '(' ? 1 : c == ')' ? -1 : 0;
					stage = parens == 0 ? 3 : 2;
					continue;
				} else if (stage == 3) {
					if (c == '{') {
						return true;
					}
					stage = 0;
				}
				if (stage == 0 && braces == 0 && word &&
						token.equals("main")) {
					stage = 1;
				}
				braces += c == '{' ? 1 : c == '}' ? -1 : 0;
				word = Character.isJavaIdentifierStart(c);
			}
		}
		return false;
	}
}
