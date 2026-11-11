package de.markusfisch.android.shadereditor.project.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.SourceText;

/**
 * Turns the text of a database shader, written for the old conventions,
 * into project files that say the same with host directives. This is
 * the only code that knows those conventions: a sampler named like a
 * library texture reads it, a trailing {@code ///} comment configures
 * sampling, and {@code backbuffer} settings also scale the output.
 */
public final class LegacyShaderConverter {
	/** Where the converted shader is. */
	public static final ProjectPath SHADER = path("main.glsl");

	/**
	 * A library texture: whether it is stored as a cubemap atlas, and
	 * its stored size in pixels.
	 */
	public record LibraryTexture(boolean atlas, int width, int height) {
	}

	/** The textures of the library, by name. */
	public interface Library {
		/** Return the texture with this name, or null. */
		LibraryTexture find(String name);
	}

	/**
	 * A library texture the converted files read at path; a cubemap is
	 * the stored image cut like the old atlas and repacked into a strip.
	 */
	public record TextureFile(String name, ProjectPath path, boolean cubemap) {
	}

	/**
	 * @param files       every file as text, the converted shader at
	 *                    {@link #SHADER} first
	 * @param textures    library textures the files read
	 * @param lines       for every line of the converted shader, its
	 *                    1-based line in the original text, or 0 for an
	 *                    inserted line
	 * @param diagnostics what could not be converted exactly, at lines
	 *                    of the original text and with no file
	 */
	public record Result(
			Map<ProjectPath, String> files,
			List<TextureFile> textures,
			List<Integer> lines,
			List<ProjectDiagnostic> diagnostics) {
		public Result {
			files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
			textures = List.copyOf(textures);
			lines = List.copyOf(lines);
			diagnostics = List.copyOf(diagnostics);
		}

		public String shader() {
			return files.get(SHADER);
		}

		/** The original line of a 1-based converted line, or 0. */
		public int originalLine(int line) {
			return line < 1 || line > lines.size() ? 0 : lines.get(line - 1);
		}
	}

	// The old renderer's sampler pattern and limit, see ShaderSourcePreparer.
	private static final Pattern SAMPLER = Pattern.compile(
			"uniform[ \t]+sampler(2D|Cube|ExternalOES)+[ \t]+" +
					"([a-zA-Z0-9_]+);");
	private static final int MAX_TEXTURES = 32;
	private static final String BACKBUFFER = "backbuffer";

	private final List<String> raw;
	private final PreprocessorScan scan;
	private final Library library;
	private final List<Declaration> declarations = new ArrayList<>();
	private final Map<Integer, List<Line>> before = new TreeMap<>();
	private final Map<Integer, List<Line>> replaced = new HashMap<>();
	private final Map<ProjectPath, String> files = new LinkedHashMap<>();
	private final Map<ProjectPath, TextureFile> textures =
			new LinkedHashMap<>();
	private final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
	private LegacySamplerSettings backbuffer = LegacySamplerSettings.BACKBUFFER;
	private boolean presetMissing;

	private record Declaration(int line, int start, int end, String type,
			String name, String params, boolean code) {
	}

	private record Line(String text, int original) {
	}

	private LegacyShaderConverter(List<String> raw, Library library) {
		this.raw = raw;
		this.scan = PreprocessorScan.scan(raw, null);
		this.library = library;
		files.put(SHADER, "");
	}

	/** Convert the text of a database shader. */
	public static Result convert(String text, Library library) {
		text = text.replace("\r\n", "\n").replace('\r', '\n');
		LegacyShaderConverter converter = new LegacyShaderConverter(
				SourceText.lines(text), library);
		List<Integer> lines = new ArrayList<>();
		String shader = converter.run(lines);
		if (text.endsWith("\n") && !shader.isEmpty()) {
			shader += "\n";
		}
		converter.files.put(SHADER, shader);
		return new Result(converter.files,
				new ArrayList<>(converter.textures.values()), lines,
				converter.diagnostics);
	}

	private String run(List<Integer> map) {
		find();
		List<Declaration> backbuffers = new ArrayList<>();
		for (Declaration declaration : declarations) {
			if (!declaration.code) {
				continue;
			} else if (BACKBUFFER.equals(declaration.name)) {
				backbuffers.add(declaration);
			} else {
				convert(declaration);
			}
		}
		convertBackbuffer(backbuffers);
		List<String> out = new ArrayList<>();
		for (int line = 1; line <= raw.size() + 1; ++line) {
			List<Line> lines = new ArrayList<>(or(before.get(line)));
			if (line <= raw.size()) {
				lines.addAll(replaced.containsKey(line)
						? replaced.get(line)
						: List.of(new Line(raw.get(line - 1), line)));
			}
			for (Line l : lines) {
				out.add(l.text);
				map.add(l.original);
			}
		}
		return String.join("\n", out);
	}

	// Like the old renderer: the first match on each line, comments
	// included, until it has as many samplers as it binds textures.
	private void find() {
		int samplers = 0;
		for (int i = 0; i < raw.size() && samplers < MAX_TEXTURES; ++i) {
			String line = raw.get(i);
			Matcher m = SAMPLER.matcher(line);
			if (!m.find()) {
				continue;
			}
			String code = scan.code().get(i);
			Declaration declaration = new Declaration(i + 1, m.start(),
					m.end(), "sampler" + m.group(1), m.group(2),
					line.substring(m.end()),
					code.length() >= m.end() &&
							code.regionMatches(m.start(), line, m.start(),
									m.end() - m.start()));
			declarations.add(declaration);
			if (BACKBUFFER.equals(declaration.name)) {
				// Settings in a comment still scaled the output.
				backbuffer = backbuffer.parse(declaration.params, true);
			} else {
				++samplers;
			}
		}
	}

	private void convert(Declaration declaration) {
		String name = declaration.name;
		boolean camera = name.equals("cameraBack") ||
				name.equals("cameraFront");
		if (declaration.type.equals("samplerExternalOES")) {
			if (camera) {
				annotate(declaration, "", settings(declaration)
						.forCamera().options(Sampling.CAMERA, true));
			}
			return;
		}
		LibraryTexture texture = library.find(name);
		if (texture == null) {
			return;
		} else if (camera) {
			warn(declaration.line, name + " is the camera's name, so it " +
					"cannot read the library texture " + name + "; rename " +
					"the uniform and the texture to bind it");
			return;
		}
		boolean cube = declaration.type.equals("samplerCube");
		ProjectPath path = texture(name, texture, cube);
		annotate(declaration, "\"" + path + "\"", settings(declaration)
				.options(Sampling.IMAGE, !cube));
	}

	private void convertBackbuffer(List<Declaration> declarations) {
		LegacySamplerSettings settings = backbuffer;
		int at = 0;
		for (Declaration declaration : this.declarations) {
			if (BACKBUFFER.equals(declaration.name)) {
				at = declaration.line;
				break;
			}
		}
		if (settings.mipmap() != Mipmap.NONE) {
			settings = settings.withoutMipmap();
			warn(at,
					"backbuffer reads with min=" + name(settings.min()) +
							" instead of a mipmap filter, because pass " +
							"outputs have no mipmaps; the old mipmaps " +
							"were made once and never updated");
		}
		if (settings.min() != settings.mag()) {
			warn(at, "The output is scaled to the screen with " +
					name(settings.mag()) + " filtering also when the " +
					"quality is above 1, where the old renderer used the " +
					"backbuffer's min=" + name(settings.min()));
		}
		if (settings.mag() == Filter.LINEAR &&
				(settings.wrapS() == Wrap.REPEAT ||
						settings.wrapT() == Wrap.REPEAT)) {
			warn(at, "The edge pixels of the output are no longer " +
					"blended with the opposite edge when it is scaled to " +
					"the screen, which the backbuffer's wrap=repeat did");
		}
		LibraryTexture preset = settings.preset() != null
				? library.find(settings.preset())
				: null;
		presetMissing = settings.preset() != null && preset == null;
		String options = settings.options(Sampling.PASS, true);
		for (Declaration declaration : declarations) {
			annotate(declaration, "", options);
		}
		List<String> target = new ArrayList<>();
		if (preset != null && !declarations.isEmpty()) {
			ProjectPath image = texture(settings.preset(), preset, false);
			ProjectPath shader = path("presets/" + settings.preset() +
					".glsl");
			files.put(shader, PresetShader.text("../" + image,
					preset.width(), preset.height()));
			target.add("initial=\"" + shader + "\"");
		}
		if (settings.mag() == Filter.LINEAR) {
			target.add("present=linear");
		}
		if (!target.isEmpty()) {
			int version = 0;
			for (PreprocessorScan.Directive d : scan.directives()) {
				if (d.kind() == PreprocessorScan.Kind.VERSION) {
					version = d.line();
					break;
				}
			}
			insert(version + 1, new Line("#pragma se target " +
					String.join(" ", target), 0));
		}
	}

	private ProjectPath texture(String name, LibraryTexture texture,
			boolean cube) {
		String suffix = cube == texture.atlas()
				? ""
				: cube ? ".cube" : ".atlas";
		ProjectPath path = path("textures/" + name + suffix + ".png");
		textures.put(path, new TextureFile(name, path, cube));
		return path;
	}

	// Write a directive for a declaration in code.
	private void annotate(Declaration declaration, String source,
			String options) {
		String arguments = (source + " " + options).trim();
		if (arguments.isEmpty()) {
			dropSettingsComment(declaration);
			return;
		}
		Line directive = new Line("#pragma se input " + arguments, 0);
		int line = declaration.line;
		if (scan.depthAt(line) > 0) {
			hoist(declaration, directive);
			return;
		}
		String text = raw.get(line - 1);
		String prefix = text.substring(0, declaration.start);
		List<Line> lines = new ArrayList<>();
		if (!prefix.isBlank()) {
			lines.add(new Line(prefix.stripTrailing(), line));
		}
		lines.add(directive);
		lines.add(new Line((prefix.isBlank() ? prefix : "") +
				declaration(declaration), line));
		replaced.put(line, lines);
	}

	// Host directives cannot be inside conditional blocks, so the
	// declaration moves before the outermost one around it.
	private void hoist(Declaration declaration, Line directive) {
		int line = declaration.line;
		for (Declaration other : declarations) {
			if (other != declaration && other.code &&
					other.name.equals(declaration.name)) {
				warn(line, declaration.name + " is declared more than " +
						"once, so this declaration inside #if, #ifdef or " +
						"#ifndef, where #pragma se input cannot be, is " +
						"left as written without its texture or " +
						"settings; declare it once outside the " +
						"conditional block");
				return;
			}
		}
		int open = 0;
		for (PreprocessorScan.Directive d : scan.directives()) {
			if (d.line() > line) {
				break;
			} else if (d.depth() == 0 &&
					(d.kind() == PreprocessorScan.Kind.IF ||
							d.kind() == PreprocessorScan.Kind.IFDEF ||
							d.kind() == PreprocessorScan.Kind.IFNDEF)) {
				open = d.line();
			}
		}
		insert(open, directive);
		insert(open, new Line("uniform " + declaration.type + " " +
				declaration.name + ";", line));
		String rest = raw.get(line - 1).substring(0, declaration.start) +
				remainder(declaration);
		replaced.put(line, List.of(new Line(rest.isBlank()
				? ""
				: rest.stripTrailing(), line)));
		warn(line, "Moved uniform " + declaration.type + " " +
				declaration.name + " out of the conditional block on line " +
				open + ", because #pragma se input cannot be inside one");
	}

	private void dropSettingsComment(Declaration declaration) {
		if (isSettingsOnly(declaration)) {
			String text = raw.get(declaration.line - 1);
			replaced.put(declaration.line, List.of(new Line(
					text.substring(0, declaration.end), declaration.line)));
		}
	}

	private String declaration(Declaration declaration) {
		return raw.get(declaration.line - 1)
				.substring(declaration.start, declaration.end) +
				remainder(declaration);
	}

	// What follows the semicolon, without a comment that only held
	// settings the directive now states.
	private String remainder(Declaration declaration) {
		return isSettingsOnly(declaration) ? "" : declaration.params;
	}

	// A preset naming a missing texture stays where it is written.
	private boolean isSettingsOnly(Declaration declaration) {
		return LegacySamplerSettings.isSettingsOnly(declaration.params,
				BACKBUFFER.equals(declaration.name) && !presetMissing);
	}

	private void insert(int line, Line text) {
		List<Line> lines = before.get(line);
		if (lines == null) {
			lines = new ArrayList<>();
			before.put(line, lines);
		}
		lines.add(text);
	}

	private void warn(int line, String message) {
		diagnostics.add(new ProjectDiagnostic(
				ProjectDiagnostic.Severity.WARNING, message, null, line));
	}

	private static LegacySamplerSettings settings(Declaration declaration) {
		return LegacySamplerSettings.TEXTURE.parse(declaration.params, false);
	}

	private static List<Line> or(List<Line> lines) {
		return lines != null ? lines : List.of();
	}

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
