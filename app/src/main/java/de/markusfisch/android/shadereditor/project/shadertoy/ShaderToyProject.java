package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Input;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Kind;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Pass;

/**
 * Turns a shader of ShaderToy into the files of a project: Image is
 * the running shader, every buffer a pass that the passes reading it
 * name as an input, Common an include. The code of a tab is kept as it
 * is; what ShaderToy puts around it stands above it, written with the
 * app's uniforms and host directives. What has no counterpart is a
 * warning and reads black, never something else. The same shader
 * always gives the same files.
 */
public final class ShaderToyProject {
	/** The running shader among the files. */
	public static final String ENTRY = "main.frag";
	static final String COMMON = "common.glsl";
	static final String EMPTY = "empty.frag";
	static final String README = "README.md";

	/**
	 * The files of the project by their paths in its folder, with
	 * {@link #ENTRY} last, and what was not carried over; or why there
	 * is no project.
	 *
	 * @param name the shader's title, or null
	 */
	public record Converted(String name, Map<String, String> files,
			List<String> warnings, String refusal) {
	}

	// A tab that is drawn, by its file; order is its place among the
	// buffers in a frame, and past them for Image.
	private record Drawn(Pass pass, String file, String label, int order) {
	}

	// The version and the precisions ShaderToy compiles a tab with.
	// Samplers are highp too: their default is lowp, which need not
	// hold what a float buffer does.
	private static final String VERSION = "#version 300 es\n" +
			"precision highp float;\n" +
			"precision highp int;\n" +
			"precision highp sampler2D;\n" +
			"precision highp sampler3D;\n" +
			"precision highp samplerCube;\n";
	private static final Pattern MAIN = Pattern.compile(
			"\\bvoid\\s+main\\s*\\(");
	private static final Pattern BUFFER_NAME = Pattern.compile(
			"(?i)\\bbuf(?:fer)?\\s*([a-z])\\b");
	private static final Pattern BUFFER_SOURCE = Pattern.compile(
			"buffer0([0-3])\\.png");
	// The ids of Buffer A to D, in the API's answers and on the site.
	private static final List<String> BUFFER_IDS = List.of(
			"257", "258", "259", "260",
			"4dXGR8", "XsXGR8", "4sXGR8", "XdfGR8");

	private final ShaderToyShader shader;
	private final Map<String, String> files = new LinkedHashMap<>();
	private final List<String> warnings = new ArrayList<>();
	private final List<Drawn> buffers = new ArrayList<>();
	// The code of Common without its comments, or "".
	private String common = "";
	private boolean hasCommon;
	// Whether a buffer starts from empty.frag.
	private boolean empties;

	private ShaderToyProject(ShaderToyShader shader) {
		this.shader = shader;
	}

	/** Convert the shader that text is in ShaderToy's JSON. */
	public static Converted ofJson(String text) {
		ShaderToyJson.Read read = ShaderToyJson.read(text);
		if (read.shader() == null) {
			return new Converted(null, Map.of(), List.of(), read.refusal());
		}
		Converted converted = of(read.shader());
		List<String> warnings = new ArrayList<>(read.notes());
		warnings.addAll(converted.warnings());
		return new Converted(converted.name(), converted.files(), warnings,
				converted.refusal());
	}

	public static Converted of(ShaderToyShader shader) {
		ShaderToyProject project = new ShaderToyProject(shader);
		String refusal = project.convert();
		String name = shader.name() != null && !shader.name().trim().isEmpty()
				? ShaderToyHeader.line(shader.name())
				: null;
		return refusal != null
				? new Converted(name, Map.of(), List.of(), refusal)
				: new Converted(name, project.files, project.warnings, null);
	}

	// Return why there is no project, or null.
	private String convert() {
		Pass image = null;
		// ShaderToy puts every Common above a tab's code, in their
		// order: one file here.
		StringBuilder shared = null;
		Map<String, String> kept = new LinkedHashMap<>();
		Set<String> letters = new LinkedHashSet<>();
		for (Pass pass : shader.passes()) {
			switch (pass.kind()) {
				case IMAGE:
					if (image != null) {
						return "it has two Image passes";
					}
					image = pass;
					break;
				case COMMON:
					shared = shared == null
							? new StringBuilder(ended(pass.code()))
							: shared.append(ended(pass.code()));
					break;
				case BUFFER:
					String letter = letter(pass, letters);
					if (letter == null) {
						return "it has more buffers than there are letters";
					} else if (!letters.add(letter)) {
						return "two passes write Buffer " + letter;
					}
					buffers.add(new Drawn(pass, "buffer" + letter + ".frag",
							"Buffer " + letter, buffers.size()));
					break;
				default:
					String file = unused(kept, pass.kind() == Kind.CUBEMAP
							? "cubeA"
							: pass.kind() == Kind.VOLUME ? "volumeA"
							: pass.kind() == Kind.SOUND ? "sound"
							: pass.name().replaceAll("[^A-Za-z0-9 _-]", "_"));
					kept.put(file, pass.code());
					warnings.add(pass.kind() == Kind.CUBEMAP ||
							pass.kind() == Kind.VOLUME
							? "The " + pass.name() + " tab draws a " +
							(pass.kind() == Kind.CUBEMAP
									? "cubemap"
									: "volume texture") +
							", which the app cannot do. Its code is kept in " +
							file + " and is not run."
							: pass.kind() == Kind.SOUND
							? "The " + pass.name() + " tab makes sound, " +
							"which the app does not do. Its code is kept " +
							"in " + file + " and is not run."
							: "The tab " + pass.name() + " is of a kind the " +
							"import does not know. Its code is kept in " +
							file + " and is not run.");
					break;
			}
		}
		if (image == null) {
			return "it has no Image pass";
		}
		if (shared != null) {
			hasCommon = true;
			common = ShaderToyHeader.code(shared.toString(), false);
		}
		Map<String, String> passes = new LinkedHashMap<>();
		for (Drawn buffer : buffers) {
			passes.put(buffer.file(), text(buffer));
		}
		String main = text(new Drawn(image, ENTRY, "Image",
				Integer.MAX_VALUE));
		files.put(README, ShaderToyReadme.text(shader, listed(kept),
				!buffers.isEmpty(), warnings));
		if (hasCommon) {
			files.put(COMMON, shared.toString());
		}
		if (empties) {
			files.put(EMPTY, "#version 300 es\n" +
					"precision mediump float;\n\n" +
					"// What a buffer holds before the first frame: " +
					"nothing, as on ShaderToy.\n" +
					"#pragma se target size=1x1\n\n" +
					"out vec4 color;\n\n" +
					"void main() {\n\tcolor = vec4(0.0);\n}\n");
		}
		files.putAll(passes);
		for (Map.Entry<String, String> file : kept.entrySet()) {
			files.put(file.getKey(), ended(file.getValue()));
		}
		files.put(ENTRY, main);
		return null;
	}

	// The files with what each is, as the README lists them.
	private Map<String, String> listed(Map<String, String> kept) {
		Map<String, String> listed = new LinkedHashMap<>();
		listed.put(ENTRY, "Image: what you see");
		for (Drawn buffer : buffers) {
			listed.put(buffer.file(), buffer.label());
		}
		if (hasCommon) {
			listed.put(COMMON, "Common: code every pass includes");
		}
		if (empties) {
			listed.put(EMPTY, "What a buffer holds before the first frame");
		}
		for (String file : kept.keySet()) {
			listed.put(file, "Kept from ShaderToy, not run");
		}
		return listed;
	}

	// The letter of a buffer: by its output's id, else by its name,
	// else the first that is free. Null when none is.
	private static String letter(Pass pass, Set<String> taken) {
		int known = BUFFER_IDS.indexOf(pass.output());
		if (known > -1) {
			return String.valueOf((char) ('A' + known % 4));
		}
		Matcher named = BUFFER_NAME.matcher(pass.name());
		if (named.find()) {
			return named.group(1).toUpperCase(Locale.ROOT);
		}
		for (char letter = 'A'; letter <= 'Z'; ++letter) {
			if (!taken.contains(String.valueOf(letter))) {
				return String.valueOf(letter);
			}
		}
		return null;
	}

	private static String unused(Map<String, String> taken, String stem) {
		String name = stem.trim().isEmpty() ? "tab" : stem.trim();
		String file = name + ".glsl";
		for (int n = 2; taken.containsKey(file) || file.equals(COMMON); ++n) {
			file = name + " " + n + ".glsl";
		}
		return file;
	}

	// The file of a tab that is drawn.
	private String text(Drawn drawn) {
		String code = ShaderToyHeader.code(drawn.pass().code(), false);
		if (MAIN.matcher(code).find()) {
			warnings.add(drawn.file() + " defines main itself, so it was " +
					"kept as it is, without ShaderToy's inputs.");
			return ended(drawn.pass().code());
		}
		String all = common + "\n" + code;
		StringBuilder sb = new StringBuilder(VERSION);
		List<ShaderToyChannel> channels = channels(drawn, all);
		sb.append("\n// ").append(drawn.label());
		if (shader.name() != null) {
			sb.append(" of \"").append(ShaderToyHeader.line(shader.name())).append('"');
		}
		if (shader.author() != null) {
			sb.append(" by ").append(ShaderToyHeader.line(shader.author()));
		}
		sb.append(", imported from ShaderToy.\n// README.md says what " +
				"the import did.\n");
		if (drawn.order() < Integer.MAX_VALUE) {
			boolean read = historyRead(drawn);
			empties |= read;
			sb.append("#pragma se target format=rgba16f")
					.append(read ? " initial=\"" + EMPTY + "\"" : "")
					.append('\n');
		}
		for (ShaderToyChannel channel : channels) {
			sb.append('\n').append(channel.lines());
			if (channel.warning() != null) {
				warnings.add(drawn.file() + ": " + channel.warning());
			}
		}
		sb.append('\n').append(ShaderToyHeader.inputs(all, channels));
		sb.append("\nout vec4 shaderToyColor;\n\n" +
				"void mainImage(out vec4 c, in vec2 f);\n\n" +
				"void main() {\n" +
				"\tshaderToyInputs();\n" +
				"\tvec4 color = vec4(1e20);\n" +
				"\tmainImage(color, gl_FragCoord.xy);\n" +
				"\tshaderToyColor = ")
				.append(drawn.order() < Integer.MAX_VALUE
						? "color"
						: "vec4(color.rgb, 1.0)")
				.append(";\n}\n\n")
				.append(ShaderToyHeader.defines(all,
						common + "\n" + ShaderToyHeader.code(drawn.pass().code(), true)));
		if (hasCommon) {
			sb.append("#include \"" + COMMON + "\"\n\n");
		}
		return sb.append(ended(drawn.pass().code())).toString();
	}

	// Whether a pass reads what the buffer drew the frame before.
	private boolean historyRead(Drawn buffer) {
		for (Drawn reader : buffers) {
			if (reader.order() > buffer.order()) {
				continue;
			}
			String all = common + "\n" + ShaderToyHeader.code(reader.pass().code(), false);
			for (Input input : reader.pass().inputs()) {
				if (find(input) == buffer && ShaderToyInputs.names(all,
						"iChannel" + input.channel())) {
					return true;
				}
			}
		}
		return false;
	}

	// The channels the code of a tab names, by their numbers.
	private List<ShaderToyChannel> channels(Drawn drawn, String all) {
		Map<Integer, Input> inputs = new TreeMap<>();
		for (Input input : drawn.pass().inputs()) {
			if (!inputs.containsKey(input.channel())) {
				inputs.put(input.channel(), input);
			}
		}
		boolean sizes = ShaderToyInputs.names(all, "iChannelResolution");
		List<ShaderToyChannel> channels = new ArrayList<>();
		for (int n = 0; n < 4; ++n) {
			Input input = inputs.get(n);
			if (!ShaderToyInputs.names(all, "iChannel" + n) &&
					!(sizes && input != null)) {
				continue;
			}
			Drawn buffer = input != null && input.kind().equals("buffer")
					? find(input)
					: null;
			channels.add(buffer != null
					? ShaderToyChannel.buffer(n, input, buffer.file(),
							buffer.label(), previous(drawn, buffer))
					: input != null
					? ShaderToyChannel.missing(n, input)
					: shader.wired()
					? ShaderToyChannel.unattached(n)
					: ShaderToyChannel.unknown(n, choices(drawn)));
		}
		return channels;
	}

	// The buffer an input reads, or null: by the id of its output, else
	// by the picture ShaderToy shows for it.
	private Drawn find(Input input) {
		if (!input.kind().equals("buffer")) {
			return null;
		}
		for (Drawn buffer : buffers) {
			if (!input.id().isEmpty() &&
					input.id().equals(buffer.pass().output())) {
				return buffer;
			}
		}
		Matcher source = BUFFER_SOURCE.matcher(input.source());
		if (source.find()) {
			String label = "Buffer " + (char) ('A' +
					Integer.parseInt(source.group(1)));
			for (Drawn buffer : buffers) {
				if (buffer.label().equals(label)) {
					return buffer;
				}
			}
		}
		return null;
	}

	// ShaderToy draws the buffers in their order, then Image, and a
	// read gets the picture finished last: of this frame from a buffer
	// drawn before the reader, of the frame before from itself and
	// from a buffer drawn after it.
	private static boolean previous(Drawn reader, Drawn buffer) {
		return buffer.order() >= reader.order();
	}

	// The input lines a channel of drawn may get, one per buffer.
	private List<String> choices(Drawn drawn) {
		List<String> choices = new ArrayList<>();
		for (Drawn buffer : buffers) {
			choices.add("#pragma se input \"" + buffer.file() + "\"" +
					(previous(drawn, buffer) ? " frame=previous" : ""));
		}
		return choices;
	}

	private static String ended(String text) {
		return text.endsWith("\n") ? text : text + "\n";
	}
}
