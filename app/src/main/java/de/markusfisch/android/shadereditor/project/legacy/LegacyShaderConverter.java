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
	// What the compiler sees as a declaration, in text without comments.
	private static final Pattern DECLARATION = Pattern.compile(
			"\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?" +
					"(sampler2D|samplerCube|samplerExternalOES)\\s+" +
					"([a-zA-Z0-9_]+)\\s*;");
	private static final Pattern DISCARD = Pattern.compile("\\bdiscard\\b");
	private static final String BACKBUFFER = "backbuffer";

	private final List<String> raw;
	private final PreprocessorScan scan;
	private final Library library;
	private final List<Declaration> declarations = new ArrayList<>();
	private final Map<String, List<Declaration>> declared = new HashMap<>();
	private final Map<Integer, List<Line>> before = new TreeMap<>();
	private final Map<Integer, List<Edit>> edits = new HashMap<>();
	private final Map<ProjectPath, String> files = new LinkedHashMap<>();
	private final Map<ProjectPath, TextureFile> textures =
			new LinkedHashMap<>();
	private final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
	private LegacySamplerSettings backbuffer = LegacySamplerSettings.BACKBUFFER;
	private boolean presetMissing;

	// matched: found by the old renderer's pattern, so params were read.
	private record Declaration(int line, int start, int end, String type,
			String name, String params, boolean code, boolean matched) {
	}

	// A declaration on its line that gets a directive before it, or
	// moves away, or only loses its settings comment.
	private record Edit(Declaration declaration, Line directive,
			boolean remove) {
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
			List<Line> lines = new ArrayList<>(or(before.get(line)));
			if (line <= raw.size()) {
				lines.addAll(edits.containsKey(line)
						? rewrite(line)
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
									m.end() - m.start()), true);
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
	// did not match it.
	private void findDeclared() {
		Map<Integer, Declaration> matches = new HashMap<>();
		for (Declaration declaration : declarations) {
			if (declaration.code) {
				matches.put(declaration.line, declaration);
			}
		}
		for (int i = 0; i < raw.size(); ++i) {
			Matcher m = DECLARATION.matcher(scan.code().get(i));
			while (m.find()) {
				Declaration match = matches.get(i + 1);
				Declaration declaration = match != null &&
						match.start == m.start()
						? match
						: new Declaration(i + 1, m.start(), m.end(),
								m.group(1), m.group(2),
								raw.get(i).substring(m.end()), true, false);
				List<Declaration> list = declared.get(declaration.name);
				if (list == null) {
					list = new ArrayList<>();
					declared.put(declaration.name, list);
				}
				list.add(declaration);
			}
		}
	}

	// The match chose the texture and settings of its name; write them on
	// the declarations of that name.
	private void convert(Declaration match) {
		String name = match.name;
		boolean camera = name.equals("cameraBack") ||
				name.equals("cameraFront");
		if (match.type.equals("samplerExternalOES")) {
			if (camera) {
				bind(targets(match), settings(match).forCamera()
						.options(Sampling.CAMERA, true));
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
		warnUndeclared(match, "read the library texture " + name +
				", but it has no declaration like uniform " + match.type +
				" " + name + "; on one line to attach #pragma se input " +
				"to, so it is not bound");
		if (!targets.isEmpty()) {
			ProjectPath path = texture(name, texture, cube);
			bind(targets, ("\"" + path + "\" " + settings(match)
					.options(Sampling.IMAGE, !cube)).trim());
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
				? library.find(settings.preset())
				: null;
		presetMissing = settings.preset() != null && preset == null;
		List<Declaration> declarations = or(declared.get(BACKBUFFER));
		String options = settings.options(Sampling.PASS, true);
		if (first != null && (!options.isEmpty() || preset != null)) {
			warnUndeclared(first, "has settings, but " +
					"it has no declaration like uniform sampler2D " +
					"backbuffer; on one line to attach #pragma se input " +
					"to, so they are not applied");
		}
		if (options.isEmpty()) {
			for (Declaration declaration : declarations) {
				edit(new Edit(declaration, null, false));
			}
		} else {
			place(declarations, new Line("#pragma se input " + options, 0));
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
						" " + match.name + " read no texture; it stays " +
						"without one");
			}
		}
		return targets;
	}

	// A name that is used but declared in a shape the old pattern found
	// only in a comment, like over two lines, keeps what it had nowhere.
	private void warnUndeclared(Declaration match, String message) {
		if (declared.containsKey(match.name)) {
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
		place(targets, new Line("#pragma se input " + arguments, 0));
	}

	// Host directives cannot be inside conditional blocks, so a
	// declaration in one moves before the outermost block around it,
	// unless the name is declared elsewhere too.
	private void place(List<Declaration> declarations, Line directive) {
		List<Declaration> top = new ArrayList<>();
		for (Declaration declaration : declarations) {
			if (scan.depthAt(declaration.line) == 0) {
				top.add(declaration);
			}
		}
		if (top.isEmpty() && declarations.size() == 1) {
			hoist(declarations.get(0), directive);
			return;
		}
		for (Declaration declaration : declarations) {
			if (top.contains(declaration)) {
				edit(new Edit(declaration, directive, false));
			} else {
				warn(declaration.line, declaration.name + " is declared " +
						"more than once, so this declaration inside #if, " +
						"#ifdef or #ifndef, where #pragma se input cannot " +
						"be, is left as written without its texture or " +
						"settings; declare it once outside the " +
						"conditional block");
			}
		}
	}

	private void hoist(Declaration declaration, Line directive) {
		int line = declaration.line;
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
		insert(open, new Line(raw.get(line - 1).substring(declaration.start,
				declaration.end), line));
		edit(new Edit(declaration, null, true));
		warn(line, "Moved uniform " + declaration.type + " " +
				declaration.name + " out of the conditional block on line " +
				open + ", because #pragma se input cannot be inside one");
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
	private List<Line> rewrite(int line) {
		String text = raw.get(line - 1);
		List<Edit> list = edits.get(line);
		Collections.sort(list, (a, b) -> Integer.compare(
				a.declaration.start, b.declaration.start));
		List<Line> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int from = 0;
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
				lines.add(edit.directive);
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

	private void insert(int line, Line text) {
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
