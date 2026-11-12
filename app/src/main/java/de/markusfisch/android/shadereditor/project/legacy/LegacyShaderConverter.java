package de.markusfisch.android.shadereditor.project.legacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectParseResult;
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
	 * How a sampler that reads no image gets one in the old conventions,
	 * formatted with its name.
	 */
	public static final String ADD_TEXTURE = "add a texture named %s";

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

		/** Return the texture with this row id, or null. */
		default LibraryTexture find(long id) {
			return null;
		}
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
	 *                    1-based line in the original text; negative for
	 *                    a line the conversion inserted for the original
	 *                    line of that magnitude, 0 for one inserted for
	 *                    none
	 * @param origins     for every other file the conversion generated,
	 *                    the original line it was generated for
	 * @param diagnostics what could not be converted exactly, at lines
	 *                    of the original text and with no file
	 */
	public record Result(
			Map<ProjectPath, String> files,
			List<TextureFile> textures,
			List<Integer> lines,
			Map<ProjectPath, Integer> origins,
			List<ProjectDiagnostic> diagnostics) {
		public Result {
			files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
			textures = List.copyOf(textures);
			lines = List.copyOf(lines);
			origins = Collections.unmodifiableMap(new HashMap<>(origins));
			diagnostics = List.copyOf(diagnostics);
		}

		public String shader() {
			return files.get(SHADER);
		}

		/**
		 * The original line of a 1-based converted line, or of the line
		 * it was inserted for, or 0.
		 */
		public int originalLine(int line) {
			return Math.abs(converted(line));
		}

		/**
		 * Return diagnostics of the converted files at lines of the
		 * original text, followed by what the conversion found. One
		 * about a line or a file the conversion generated is at the
		 * line it was generated for, and is left out when it is a
		 * warning: such a warning is about the conversion, which
		 * reports what it changes itself, like an unused texture that
		 * the old renderer did not report either.
		 */
		public List<ProjectDiagnostic> toOriginal(
				List<ProjectDiagnostic> converted) {
			List<ProjectDiagnostic> original = new ArrayList<>();
			for (ProjectDiagnostic diagnostic : converted) {
				ProjectPath file = diagnostic.file();
				int line = 0;
				if (SHADER.equals(file)) {
					line = converted(diagnostic.line());
				} else if (origins.containsKey(file)) {
					line = -origins.get(file);
				}
				if (file == null) {
					original.add(diagnostic);
				} else if (line > 0 || diagnostic.severity() ==
						ProjectDiagnostic.Severity.ERROR) {
					original.add(new ProjectDiagnostic(diagnostic.severity(),
							diagnostic.message(), null, Math.abs(line),
							diagnostic.raw()));
				}
			}
			original.addAll(diagnostics);
			return original;
		}

		private int converted(int line) {
			return line < 1 || line > lines.size() ? 0 : lines.get(line - 1);
		}
	}

	// The old renderer's sampler pattern and limit.
	private static final Pattern SAMPLER = Pattern.compile(
			"uniform[ \t]+sampler(2D|Cube|ExternalOES)+[ \t]+" +
					"([a-zA-Z0-9_]+);");
	private static final int MAX_TEXTURES = 32;
	// What the compiler sees as a declaration, in text without comments.
	private static final Pattern DECLARATION = Pattern.compile(
			"\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?" +
					"(sampler2D|samplerCube|samplerExternalOES)\\s+" +
					"([a-zA-Z0-9_]+)\\s*;");
	private static final Pattern DISCARD = Pattern.compile("\\bdiscard\\b");
	private static final Pattern IDENTIFIER = Pattern.compile(
			"[a-zA-Z_][a-zA-Z0-9_]*");
	private static final String BACKBUFFER = "backbuffer";

	private final List<String> raw;
	private final PreprocessorScan scan;
	private final Library library;
	private final Set<String> reserved;
	// Uniforms named like builtins that read a library texture, and the
	// names they get.
	private final Map<String, String> renames = new HashMap<>();
	private final Map<ProjectPath, Integer> origins = new HashMap<>();
	private final List<Declaration> declarations = new ArrayList<>();
	private final Map<String, List<Declaration>> declared = new HashMap<>();
	private final Set<String> macros = new HashSet<>();
	private final Map<Integer, List<Line>> before = new TreeMap<>();
	private final Map<Integer, List<Edit>> edits = new HashMap<>();
	private final Map<ProjectPath, String> files = new LinkedHashMap<>();
	private final Map<ProjectPath, TextureFile> textures =
			new LinkedHashMap<>();
	private final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
	// Whether a block comment is open at the start of each line.
	private final boolean[] inComment;
	private LegacySamplerSettings backbuffer = LegacySamplerSettings.BACKBUFFER;
	private boolean presetMissing;

	// matched: found by the old renderer's pattern, so params were read.
	// One that continues on later lines ends at the end of its first.
	private record Declaration(int line, int start, int end, String type,
			String name, String params, boolean code, boolean matched,
			boolean multiline) {
	}

	// A declaration on its line that gets a directive before it, or
	// moves away, or only loses its settings comment.
	private record Edit(Declaration declaration, Line directive,
			boolean remove) {
	}

	// original is negative for a line inserted for that original line.
	private record Line(String text, int original) {
	}

	private LegacyShaderConverter(List<String> raw, Library library,
			Set<String> reserved) {
		this.raw = raw;
		this.scan = PreprocessorScan.scan(raw, null);
		this.library = library;
		this.reserved = reserved;
		this.inComment = comments(scan);
		files.put(SHADER, "");
	}

	/**
	 * Convert the text of a database shader. reserved are the names of
	 * the builtin uniforms, which an input cannot take.
	 */
	public static Result convert(String text, Library library,
			Set<String> reserved) {
		text = text.replace("\r\n", "\n").replace('\r', '\n');
		LegacyShaderConverter converter = new LegacyShaderConverter(
				SourceText.lines(text), library, reserved);
		List<Integer> lines = new ArrayList<>();
		String shader = converter.run(lines);
		if (text.endsWith("\n") && !shader.isEmpty()) {
			shader += "\n";
		}
		converter.files.put(SHADER, shader);
		return new Result(converter.files,
				new ArrayList<>(converter.textures.values()), lines,
				converter.origins, converter.diagnostics);
	}

	private String run(List<Integer> map) {
		find();
		findDeclared();
		// The old renderer applied the matches of a name in order, so the
		// last one chose the texture and its settings.
		Map<String, Declaration> last = new LinkedHashMap<>();
		for (Declaration declaration : declarations) {
			if (!BACKBUFFER.equals(declaration.name)) {
				last.put(declaration.name, declaration);
			}
		}
		for (Declaration declaration : last.values()) {
			convert(declaration);
		}
		convertBackbuffer();
		warnDiscard();
		List<String> out = new ArrayList<>();
		for (int line = 1; line <= raw.size() + 1; ++line) {
			List<Line> lines = new ArrayList<>();
			int from = 0;
			if (before.containsKey(line)) {
				// Inserted lines go where the comment that is open at the
				// start of the line ends, so that they are code.
				from = commentEnd(line);
				if (from > 0) {
					lines.add(new Line(raw.get(line - 1).substring(0, from),
							line));
				}
				lines.addAll(before.get(line));
			}
			if (line > raw.size()) {
				// Nothing follows.
			} else if (edits.containsKey(line)) {
				lines.addAll(rewrite(line, from));
			} else if (from == 0 ||
					!raw.get(line - 1).substring(from).isBlank()) {
				lines.add(new Line(raw.get(line - 1).substring(from), line));
			}
			for (Line l : lines) {
				out.add(l.text);
				map.add(l.original);
			}
		}
		rename(out, map);
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
									m.end() - m.start()), true, false);
			declarations.add(declaration);
			if (BACKBUFFER.equals(declaration.name)) {
				// Settings in a comment still scaled the output.
				backbuffer = backbuffer.parse(declaration.params, true);
			} else {
				++samplers;
			}
		}
	}

	// Every declaration in code, by name, wherever the old pattern did or
	// did not match it, also one over several lines.
	private void findDeclared() {
		Map<Integer, Declaration> matches = new HashMap<>();
		for (Declaration declaration : declarations) {
			if (declaration.code) {
				matches.put(declaration.line, declaration);
			}
		}
		Set<Integer> directives = new HashSet<>();
		for (PreprocessorScan.Directive d : scan.directives()) {
			directives.add(d.line());
		}
		boolean continued = false;
		boolean[] macro = new boolean[raw.size()];
		for (int i = 0; i < raw.size(); ++i) {
			Matcher m = DECLARATION.matcher(scan.code().get(i));
			// A macro body is not a declaration until it is expanded.
			if (continued || directives.contains(i + 1)) {
				continued = raw.get(i).endsWith("\\");
				macro[i] = true;
				while (m.find()) {
					macros.add(m.group(2));
				}
				continue;
			}
			while (m.find()) {
				Declaration match = matches.get(i + 1);
				declare(match != null && match.start == m.start()
						? match
						: new Declaration(i + 1, m.start(), m.end(),
								m.group(1), m.group(2),
								raw.get(i).substring(m.end()), true, false,
								false));
			}
		}
		findMultiline(macro);
	}

	// The old pattern bound such a declaration by name through a match in
	// a comment.
	private void findMultiline(boolean[] macro) {
		String code = String.join("\n", scan.code());
		int[] starts = new int[raw.size() + 1];
		for (int i = 0; i < raw.size(); ++i) {
			starts[i + 1] = starts[i] + scan.code().get(i).length() + 1;
		}
		for (Matcher m = DECLARATION.matcher(code); m.find(); ) {
			int first = lineOf(starts, m.start());
			int last = lineOf(starts, m.end() - 1);
			boolean inMacro = false;
			for (int i = first; i <= last; ++i) {
				inMacro |= macro[i];
			}
			if (first != last && !inMacro) {
				declare(new Declaration(first + 1, m.start() - starts[first],
						raw.get(first).length(), m.group(1), m.group(2), "",
						true, false, true));
			}
		}
	}

	private void declare(Declaration declaration) {
		List<Declaration> list = declared.get(declaration.name);
		if (list == null) {
			list = new ArrayList<>();
			declared.put(declaration.name, list);
		}
		list.add(declaration);
	}

	private static int lineOf(int[] starts, int offset) {
		int i = Arrays.binarySearch(starts, offset);
		return i >= 0 ? i : -i - 2;
	}

	// The match chose the texture and settings of its name; write them on
	// the declarations of that name.
	private void convert(Declaration match) {
		String name = match.name;
		boolean camera = name.equals("cameraBack") ||
				name.equals("cameraFront");
		if (match.type.equals("samplerExternalOES")) {
			if (camera) {
				List<Declaration> targets = targets(match);
				bind(targets, settings(match).forCamera()
						.options(Sampling.CAMERA, true));
				warnCameraFoundTwice(match, targets);
			}
			return;
		}
		LibraryTexture texture = library.find(name);
		if (texture == null) {
			return;
		}
		List<Declaration> declarations = or(declared.get(name));
		if (camera) {
			if (!declarations.isEmpty()) {
				warn(declarations.get(0).line, name + " is the camera's " +
						"name, so it cannot read the library texture " +
						name + "; rename the uniform and the texture to " +
						"bind it");
			}
			return;
		}
		boolean cube = match.type.equals("samplerCube");
		List<Declaration> targets = targets(match);
		warnUndeclared(match, "does not read the library texture " + name,
				"read the library texture " + name +
				", but it has no declaration like uniform " + match.type +
				" " + name + "; to attach #pragma se input to, so it is " +
				"not bound");
		List<Declaration> placed = placeable(targets);
		if (!placed.isEmpty()) {
			ProjectPath path = texture(name, texture, cube, match.line);
			place(placed, new Line("#pragma se input " + ("\"" + path +
					"\" " + settings(match).options(Sampling.IMAGE, !cube))
					.trim(), 0));
			// An input cannot take the name of a builtin, which the old
			// renderer did not set on a sampler.
			if (reserved.contains(name)) {
				renames.put(name, freeName(name));
			}
		}
	}

	// A name used nowhere in the text, comments included.
	private String freeName(String name) {
		Set<String> used = new HashSet<>();
		for (Matcher m = IDENTIFIER.matcher(String.join("\n", raw));
				m.find(); ) {
			used.add(m.group());
		}
		String free = name + "_texture";
		for (int i = 2; used.contains(free) || reserved.contains(free) ||
				renames.containsValue(free); ++i) {
			free = name + "_texture" + i;
		}
		return free;
	}

	// The uniforms named like builtins take their new names in the code
	// of the original lines, also in macros and uses; comments and
	// inserted lines keep their text.
	private void rename(List<String> out, List<Integer> map) {
		if (renames.isEmpty()) {
			return;
		}
		List<String> code = PreprocessorScan.scan(out, null).code();
		for (int i = 0; i < out.size(); ++i) {
			if (map.get(i) <= 0) {
				continue;
			}
			String text = out.get(i);
			StringBuilder renamed = new StringBuilder();
			int from = 0;
			for (Matcher m = IDENTIFIER.matcher(code.get(i)); m.find(); ) {
				String to = renames.get(m.group());
				if (to != null) {
					renamed.append(text, from, m.start()).append(to);
					from = m.end();
				}
			}
			if (from > 0) {
				out.set(i, renamed.append(text.substring(from)).toString());
			}
		}
	}

	// The old renderer bound one name once for every match, comments
	// included, and only the first binding got the camera image, which
	// the later ones then replaced with none.
	private void warnCameraFoundTwice(Declaration match,
			List<Declaration> targets) {
		int matches = 0;
		for (Declaration declaration : declarations) {
			if (declaration.name.equals(match.name)) {
				++matches;
			}
		}
		if (matches > 1 && !targets.isEmpty()) {
			warn(match.line, "The old renderer showed no camera image for " +
					match.name + ", because it found the name more than " +
					"once, comments included; it now shows the camera");
		}
	}

	private void convertBackbuffer() {
		LegacySamplerSettings settings = backbuffer;
		Declaration first = null;
		for (Declaration declaration : this.declarations) {
			if (BACKBUFFER.equals(declaration.name)) {
				first = declaration;
				break;
			}
		}
		int at = first != null ? first.line : 0;
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
				? findPreset(settings.preset())
				: null;
		presetMissing = settings.preset() != null && preset == null;
		List<Declaration> declarations = or(declared.get(BACKBUFFER));
		String options = settings.options(Sampling.PASS, true);
		if (first != null && (!options.isEmpty() || preset != null)) {
			warnUndeclared(first, "does not get its settings",
					"has settings, but " +
					"it has no declaration like uniform sampler2D " +
					"backbuffer; to attach #pragma se input to, so they " +
					"are not applied");
		}
		if (options.isEmpty()) {
			for (Declaration declaration : declarations) {
				edit(new Edit(declaration, null, false));
			}
		} else {
			place(placeable(declarations),
					new Line("#pragma se input " + options, 0));
		}
		List<String> target = new ArrayList<>();
		if (preset != null && !declarations.isEmpty()) {
			ProjectPath image = texture(settings.preset(), preset, false, at);
			String file = image.getFileName();
			ProjectPath shader = path("presets/" +
					file.substring(0, file.length() - 4) + ".glsl");
			files.put(shader, PresetShader.text("../" + image,
					preset.width(), preset.height()));
			origins.put(shader, at);
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
					String.join(" ", target), -at));
		}
	}

	private LibraryTexture findPreset(String name) {
		LibraryTexture texture = library.find(name);
		long id = LegacySamplerSettings.presetId(name);
		return texture == null && id > 0 ? library.find(id) : texture;
	}

	// A library texture name that cannot be a file name, or names the
	// file of another texture, gets a safe one.
	private ProjectPath texture(String name, LibraryTexture texture,
			boolean cube, int line) {
		String suffix = cube == texture.atlas()
				? ""
				: cube ? ".cube" : ".atlas";
		ProjectPath path = textureFile(name + suffix, name, cube);
		if (path == null) {
			String safe = name.replaceAll("[^a-zA-Z0-9_]", "_") + suffix;
			path = textureFile(safe, name, cube);
			for (int i = 2; path == null; ++i) {
				path = textureFile(safe + "_" + i, name, cube);
			}
			warn(line, "The library texture \"" + name + "\" has a name " +
					"that cannot be a file name, so its copy is " + path);
		}
		textures.put(path, new TextureFile(name, path, cube));
		return path;
	}

	private ProjectPath textureFile(String stem, String name, boolean cube) {
		String text = "textures/" + stem + ".png";
		ProjectParseResult<ProjectPath> path = ProjectPath.parse(text);
		if (stem.contains("/") || !path.isSuccess() ||
				!path.value().toString().equals(text)) {
			return null;
		}
		TextureFile file = textures.get(path.value());
		return file == null || (file.name.equals(name) && file.cubemap == cube)
				? path.value()
				: null;
	}

	// The declarations of a match's name that can take what it chose: the
	// old renderer bound a texture of the match's type to the name, and a
	// sampler of another type read none.
	private List<Declaration> targets(Declaration match) {
		List<Declaration> targets = new ArrayList<>();
		for (Declaration declaration : or(declared.get(match.name))) {
			if (declaration.type.equals(match.type)) {
				targets.add(declaration);
			} else {
				warn(declaration.line, "The old renderer bound " +
						match.name + " as a " + match.type + " from line " +
						match.line + ", so uniform " + declaration.type +
						" " + match.name + " did not read the texture but " +
						"whatever its texture unit held from earlier " +
						"drawing; it now reads black");
			}
		}
		return targets;
	}

	// A name that is used but declared in a shape the old pattern found
	// only in a comment, like a list of uniforms, keeps what it had
	// nowhere.
	private void warnUndeclared(Declaration match, String lost,
			String message) {
		if (declared.containsKey(match.name)) {
			return;
		} else if (macros.contains(match.name)) {
			warn(match.line, match.name + " is declared by a macro, which " +
					"#pragma se input cannot annotate, so it is left as " +
					"written and " + lost);
			return;
		}
		Pattern use = Pattern.compile("(?<![a-zA-Z0-9_])" + match.name +
				"(?![a-zA-Z0-9_])");
		for (String code : scan.code()) {
			if (use.matcher(code).find()) {
				warn(match.line, match.name + " " + message);
				return;
			}
		}
	}

	// Write a directive for the declarations of a match's name.
	private void bind(List<Declaration> targets, String arguments) {
		if (arguments.isEmpty()) {
			for (Declaration declaration : targets) {
				edit(new Edit(declaration, null, false));
			}
			return;
		}
		place(placeable(targets), new Line("#pragma se input " + arguments,
				0));
	}

	// Host directives cannot be inside conditional blocks, so a
	// declaration in one moves before the outermost block around it,
	// unless the name is declared elsewhere too. A directive cannot
	// follow a line that ends in a backslash, which would continue into
	// it. Return the declarations that get a directive.
	private List<Declaration> placeable(List<Declaration> declarations) {
		List<Declaration> top = new ArrayList<>();
		for (Declaration declaration : declarations) {
			if (scan.depthAt(declaration.line) == 0) {
				top.add(declaration);
			}
		}
		boolean hoist = top.isEmpty() && declarations.size() == 1;
		List<Declaration> placeable = new ArrayList<>();
		for (Declaration declaration : declarations) {
			if (!hoist && !top.contains(declaration)) {
				warn(declaration.line, declaration.name + " is declared " +
						"more than once, so this declaration inside #if, " +
						"#ifdef or #ifndef, where #pragma se input cannot " +
						"be, is left as written without its texture or " +
						"settings; declare it once outside the " +
						"conditional block");
			} else if (hoist && declaration.multiline) {
				warn(declaration.line, declaration.name + " is declared " +
						"over several lines inside #if, #ifdef or #ifndef, " +
						"where #pragma se input cannot be, so it is left " +
						"as written without its texture or settings; " +
						"declare it on one line outside the conditional " +
						"block");
			} else if (hoist
					? continues(opening(declaration.line), 0)
					: continues(declaration.line, declaration.start)) {
				warn(declaration.line, declaration.name + " would get " +
						"#pragma se input after a line that ends in a " +
						"backslash, which would continue into it, so it " +
						"is left as written without its texture or " +
						"settings; remove the backslash");
			} else {
				placeable.add(declaration);
			}
		}
		return placeable;
	}

	// Whether a line inserted before this column of a line follows a
	// line that ends in a backslash.
	private boolean continues(int line, int column) {
		return line > 1 && !inComment[line - 1] &&
				raw.get(line - 1).substring(0, column).isBlank() &&
				raw.get(line - 2).endsWith("\\");
	}

	private void place(List<Declaration> declarations, Line directive) {
		for (Declaration declaration : declarations) {
			if (scan.depthAt(declaration.line) == 0) {
				edit(new Edit(declaration, directive, false));
			} else {
				hoist(declaration, directive);
			}
		}
	}

	// The outermost conditional block around a line.
	private int opening(int line) {
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
		return open;
	}

	private void hoist(Declaration declaration, Line directive) {
		int line = declaration.line;
		int open = opening(line);
		insert(open, new Line(directive.text, -line));
		insert(open, new Line(raw.get(line - 1).substring(declaration.start,
				declaration.end), line));
		edit(new Edit(declaration, null, true));
		warn(line, "Moved uniform " + declaration.type + " " +
				declaration.name + " out of the conditional block on line " +
				open + ", because #pragma se input cannot be inside one");
	}

	// Where a block comment that is open at the start of a line ends on
	// it, or 0.
	private int commentEnd(int line) {
		return line <= raw.size() && inComment[line - 1]
				? raw.get(line - 1).indexOf("*/") + 2
				: 0;
	}

	// Whether a block comment is open at the start of each line.
	private static boolean[] comments(PreprocessorScan scan) {
		List<Boolean> open = scan.openComments();
		boolean[] comments = new boolean[open.size()];
		for (int l = 1; l < comments.length; ++l) {
			comments[l] = open.get(l - 1);
		}
		return comments;
	}

	private void edit(Edit edit) {
		List<Edit> list = edits.get(edit.declaration.line);
		if (list == null) {
			list = new ArrayList<>();
			edits.put(edit.declaration.line, list);
		}
		list.add(edit);
	}

	// A line with its edits: a directive goes on a line of its own before
	// the declaration, and code before that keeps its own line.
	private List<Line> rewrite(int line, int from) {
		String text = raw.get(line - 1);
		List<Edit> list = edits.get(line);
		Collections.sort(list, (a, b) -> Integer.compare(
				a.declaration.start, b.declaration.start));
		List<Line> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		Edit last = null;
		for (Edit edit : list) {
			Declaration declaration = edit.declaration;
			current.append(text, from, declaration.start);
			if (edit.directive != null) {
				if (!current.toString().isBlank()) {
					lines.add(new Line(current.toString().stripTrailing(),
							line));
					current.setLength(0);
				}
				lines.add(new Line(edit.directive.text, -line));
			}
			if (!edit.remove) {
				current.append(text, declaration.start, declaration.end);
			}
			from = declaration.end;
			last = edit;
		}
		if (!isSettingsOnly(last.declaration)) {
			current.append(text.substring(from));
		}
		String rest = current.toString();
		lines.add(new Line(last.remove
				? rest.isBlank() ? "" : rest.stripTrailing()
				: rest, line));
		return lines;
	}

	// A preset naming a missing texture stays where it is written.
	private boolean isSettingsOnly(Declaration declaration) {
		return declaration.matched &&
				LegacySamplerSettings.isSettingsOnly(declaration.params,
				BACKBUFFER.equals(declaration.name) && !presetMissing);
	}

	// Insert before a line, or, when a block comment is open at its start,
	// on the line that comment ends on, after its end.
	private void insert(int line, Line text) {
		while (line <= raw.size() && inComment[line - 1] &&
				!raw.get(line - 1).contains("*/")) {
			++line;
		}
		List<Line> lines = before.get(line);
		if (lines == null) {
			lines = new ArrayList<>();
			before.put(line, lines);
		}
		lines.add(text);
	}

	// The old renderer never cleared, so a discarded pixel kept what
	// the buffer held two frames earlier.
	private void warnDiscard() {
		for (int i = 0; i < raw.size(); ++i) {
			if (DISCARD.matcher(scan.code().get(i)).find()) {
				warn(i + 1, "Discarded pixels are now opaque black, " +
						"where they used to keep older content of the " +
						"output");
				return;
			}
		}
	}

	private void warn(int line, String message) {
		diagnostics.add(new ProjectDiagnostic(
				ProjectDiagnostic.Severity.WARNING, message, null, line));
	}

	private static LegacySamplerSettings settings(Declaration declaration) {
		return LegacySamplerSettings.TEXTURE.parse(declaration.params, false);
	}

	private static <T> List<T> or(List<T> list) {
		return list != null ? list : List.of();
	}

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
