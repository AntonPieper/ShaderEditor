package de.markusfisch.android.shadereditor.project;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code #pragma se input} or {@code #pragma se target} line parsed
 * into values. Only the directive's own text is checked here; a null
 * option was not written.
 *
 * @param command     the command, or null when it could not be read
 * @param input       the input options when the line could be read,
 *                    also when diagnostics report a problem with them
 * @param target      the target when it is valid, else null
 * @param path        the file the line names as a source or initial,
 *                    also when other parts of it are invalid; null when
 *                    it names none or the path itself is invalid
 * @param diagnostics problems with this line
 */
public record HostDirective(
		Command command,
		Input input,
		Target target,
		ProjectPath path,
		List<ProjectDiagnostic> diagnostics) {
	public enum Command {INPUT, TARGET}
	public enum Frame {CURRENT, PREVIOUS}
	public enum Filter {NEAREST, LINEAR}
	public enum Mipmap {NONE, NEAREST, LINEAR}
	public enum Wrap {CLAMP, REPEAT, MIRROR}
	public enum Format {RGBA8, RGBA16F}

	/**
	 * An input for {@code uniform samplerType uniform;}; both are null
	 * until the input is attached to its declaration. A null source means
	 * an engine sampler. {@code filter=} sets both min and mag, a single
	 * {@code wrap=} value sets both axes.
	 */
	public record Input(
			ProjectPath file,
			int line,
			String uniform,
			String samplerType,
			ProjectPath source,
			Frame frame,
			Filter min,
			Filter mag,
			Mipmap mipmap,
			Wrap wrapS,
			Wrap wrapT) {
	}

	/**
	 * A target; width and height are set together by {@code size=}.
	 */
	public record Target(
			ProjectPath file,
			int line,
			Double scale,
			Integer width,
			Integer height,
			Format format,
			ProjectPath initial,
			Filter present) {
	}

	private static final List<String> INPUT_KEYS = List.of(
			"frame", "filter", "min", "mag", "mipmap", "wrap");
	private static final List<String> TARGET_KEYS = List.of(
			"scale", "size", "format", "initial", "present");
	private static final Pattern MISSPELLED = Pattern.compile(
			"se(\\.|::|_)(input|target)\\b.*");
	private static final Pattern DECIMAL = Pattern.compile(
			"[0-9]+(\\.[0-9]*)?|\\.[0-9]+");
	private static final Pattern SIZE = Pattern.compile(
			"([0-9]+)x([0-9]+)");

	public HostDirective {
		diagnostics = List.copyOf(diagnostics);
	}

	/**
	 * Whether a comment-free pragma argument is in the {@code se}
	 * namespace; unrelated pragmas such as {@code sequence} are not.
	 */
	public static boolean isHostPragma(String argument) {
		return argument.split("\\s+", 2)[0].equals("se");
	}

	/**
	 * Return the error for a pragma argument spelled like
	 * {@code se.input}, {@code se::input} or {@code se_input}, or null.
	 */
	public static ProjectDiagnostic checkSpelling(
			String argument,
			SourceLocation at) {
		Matcher misspelled = MISSPELLED.matcher(argument);
		return misspelled.matches()
				? error(at, "Unsupported spelling se" + misspelled.group(1) +
						misspelled.group(2) + ", write #pragma se " +
						misspelled.group(2))
				: null;
	}

	/**
	 * Parse the raw text after {@code #pragma}, comments included, of a
	 * host directive at the given location.
	 */
	public static HostDirective parse(String text, SourceLocation at) {
		Reader reader = new Reader(at);
		Command command = reader.parse(text);
		return new HostDirective(command, reader.input, reader.target,
				reader.named, reader.diagnostics);
	}

	private static ProjectDiagnostic error(SourceLocation at, String message) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				message, at.file(), at.line());
	}

	private static final class Reader {
		private final SourceLocation at;
		private final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
		private Input input;
		private Target target;
		private ProjectPath named;

		private Reader(SourceLocation at) {
			this.at = at;
		}

		private Command parse(String text) {
			List<String> tokens = tokenize(text);
			if (tokens == null) {
				return null;
			} else if (tokens.size() < 2) {
				error("#pragma se needs a command, write " +
						"#pragma se input or #pragma se target");
				return null;
			}
			Command command = switch (tokens.get(1)) {
				case "input" -> Command.INPUT;
				case "target" -> Command.TARGET;
				default -> null;
			};
			if (command == Command.INPUT) {
				input(tokens);
			} else if (command == Command.TARGET) {
				target(tokens);
			} else {
				error("Unknown command se " + tokens.get(1) +
						", only #pragma se input and #pragma se target exist");
			}
			return command;
		}

		// Quoted text may contain spaces.
		private List<String> tokenize(String text) {
			int i = 0;
			List<String> tokens = new ArrayList<>();
			for (int n = text.length(); ; ) {
				while (i < n && (text.charAt(i) == ' ' ||
						text.charAt(i) == '\t')) {
					++i;
				}
				int start = i;
				for (; i < n && text.charAt(i) != ' ' &&
						text.charAt(i) != '\t'; ++i) {
					if (text.startsWith("/*", i)) {
						error("Block comments are not allowed in host " +
								"directives, use a trailing // comment");
						return null;
					} else if (text.startsWith("//", i)) {
						n = i;
						break;
					} else if (text.charAt(i) == '"') {
						int close = text.indexOf('"', i + 1);
						if (close < 0) {
							error("Path has no closing quote");
							return null;
						}
						i = close;
					}
				}
				if (i > start) {
					tokens.add(text.substring(start, i));
				}
				if (i >= n) {
					return tokens;
				}
			}
		}

		private void input(List<String> tokens) {
			int errors = diagnostics.size();
			int first = 2;
			ProjectPath source = null;
			if (tokens.size() > 2 && tokens.get(2).startsWith("\"")) {
				source = path(tokens.get(2));
				named = source;
				++first;
			}
			Map<String, String> options = options(tokens, first, INPUT_KEYS,
					"input");
			if (diagnostics.size() > errors) {
				return;
			}
			Frame frame = choice(options, "frame", Frame.values());
			Filter filter = choice(options, "filter", Filter.values());
			Filter min = choice(options, "min", Filter.values());
			Filter mag = choice(options, "mag", Filter.values());
			Mipmap mipmap = choice(options, "mipmap", Mipmap.values());
			Wrap[] wrap = wrap(options.get("wrap"));
			if (filter != null && (min != null || mag != null)) {
				error("filter sets min and mag together, write either " +
						"filter=... or min=... mag=...");
			} else if (filter != null) {
				min = filter;
				mag = filter;
			}
			if (source == null && options.isEmpty()) {
				error("#pragma se input needs a path or an option, for " +
						"example #pragma se input \"assets/noise.png\"");
			}
			// What a path names, a picture or a pass, is known once the
			// file is read; only an engine sampler is known here.
			if (frame != null && source == null) {
				error("frame= applies only to a pass, remove it");
			}
			if (mipmap != null && source == null) {
				error("mipmap= applies only to a picture, pass " +
						"outputs and engine samplers have no mipmaps");
			}
			input = new Input(at.file(), at.line(), null, null, source,
					frame, min, mag, mipmap,
					wrap != null ? wrap[0] : null,
					wrap != null ? wrap[1] : null);
		}

		private void target(List<String> tokens) {
			named = initial(tokens);
			int errors = diagnostics.size();
			Map<String, String> options = options(tokens, 2, TARGET_KEYS,
					"target");
			if (diagnostics.size() > errors) {
				return;
			} else if (options.isEmpty()) {
				error("#pragma se target needs at least one property, " +
						"for example #pragma se target scale=0.5");
				return;
			} else if (options.containsKey("scale") &&
					options.containsKey("size")) {
				error("scale and size cannot be combined, write one");
				return;
			}
			Double scale = scale(options.get("scale"));
			int[] size = size(options.get("size"));
			Format format = choice(options, "format", Format.values());
			String initial = options.get("initial");
			ProjectPath initialPath = null;
			if (initial != null) {
				initialPath = path(initial);
			}
			Filter present = choice(options, "present", Filter.values());
			if (diagnostics.size() == errors) {
				target = new Target(at.file(), at.line(), scale,
						size != null ? size[0] : null,
						size != null ? size[1] : null,
						format, initialPath, present);
			}
		}

		// The initial path, also when other options are invalid.
		private ProjectPath initial(List<String> tokens) {
			for (int i = 2; i < tokens.size(); ++i) {
				if (tokens.get(i).startsWith("initial=")) {
					int errors = diagnostics.size();
					ProjectPath path = path(tokens.get(i).substring(8));
					diagnostics.subList(errors, diagnostics.size()).clear();
					return path;
				}
			}
			return null;
		}

		private Double scale(String value) {
			if (value == null) {
				return null;
			} else if (!DECIMAL.matcher(value).matches()) {
				error("Invalid scale=" + value + ", write a decimal " +
						"number such as scale=0.5");
				return null;
			}
			BigDecimal scale = new BigDecimal(value);
			if (scale.scale() > 6) {
				error("scale=" + value + " has more than six digits " +
						"after the decimal point");
				return null;
			} else if (scale.signum() <= 0 ||
					scale.compareTo(BigDecimal.ONE) > 0) {
				error("scale=" + value + " must be greater than 0 and " +
						"at most 1, use size=WIDTHxHEIGHT for other sizes");
				return null;
			}
			return scale.doubleValue();
		}

		private int[] size(String value) {
			if (value == null) {
				return null;
			}
			Matcher m = SIZE.matcher(value);
			if (!m.matches()) {
				error("Invalid size=" + value + ", write " +
						"size=WIDTHxHEIGHT such as size=256x256");
				return null;
			}
			try {
				int[] size = {Integer.parseInt(m.group(1)),
						Integer.parseInt(m.group(2))};
				if (size[0] > 0 && size[1] > 0) {
					return size;
				}
				error("size=" + value + " must be positive in both " +
						"dimensions");
			} catch (NumberFormatException e) {
				error("size=" + value + " is too large");
			}
			return null;
		}

		private Map<String, String> options(
				List<String> tokens,
				int first,
				List<String> keys,
				String command) {
			Map<String, String> options = new LinkedHashMap<>();
			for (int i = first; i < tokens.size(); ++i) {
				String token = tokens.get(i);
				int equals = token.indexOf('=');
				if (token.startsWith("\"")) {
					error("The path must come right after " +
							"#pragma se input, before any option");
				} else if (equals < 1) {
					error("Unexpected '" + token + "' in #pragma se " +
							command + ", write options as key=value");
				} else if (!keys.contains(token.substring(0, equals))) {
					error("Unknown key '" + token.substring(0, equals) +
							"' for #pragma se " + command + ", use one of " +
							String.join(", ", keys));
				} else if (options.put(token.substring(0, equals),
						token.substring(equals + 1)) != null) {
					error("Repeated key " + token.substring(0, equals) +
							", write it once");
				} else {
					continue;
				}
				break;
			}
			return options;
		}

		private <E extends Enum<E>> E choice(
				Map<String, String> options,
				String key,
				E[] values) {
			String value = options.get(key);
			if (value == null) {
				return null;
			}
			E found = find(value, values);
			if (found == null) {
				error("Invalid " + key + "=" + value + ", use " + key +
						"=" + names(values));
			}
			return found;
		}

		private Wrap[] wrap(String value) {
			if (value == null) {
				return null;
			}
			String[] parts = value.split(",", -1);
			Wrap s = find(parts[0], Wrap.values());
			Wrap t = parts.length == 2 ? find(parts[1], Wrap.values()) : s;
			if (parts.length > 2 || s == null || t == null) {
				error("Invalid wrap=" + value + ", use wrap=MODE or " +
						"wrap=MODE,MODE with MODE " + names(Wrap.values()));
				return null;
			}
			return new Wrap[]{s, t};
		}

		private ProjectPath path(String quoted) {
			if (quoted.length() < 2 || !quoted.startsWith("\"") ||
					quoted.indexOf('"', 1) != quoted.length() - 1) {
				error("Invalid path " + quoted + ", write it in " +
						"double quotes such as \"assets/noise.png\"");
				return null;
			}
			ProjectParseResult<ProjectPath> result = ProjectPath.resolve(
					at.file(), quoted.substring(1, quoted.length() - 1));
			if (!result.isSuccess()) {
				error(result.diagnostic().message());
				return null;
			}
			return result.value();
		}

		private static <E extends Enum<E>> E find(String value, E[] values) {
			for (E e : values) {
				if (e.name().toLowerCase(Locale.ROOT).equals(value)) {
					return e;
				}
			}
			return null;
		}

		private static <E extends Enum<E>> String names(E[] values) {
			List<String> names = new ArrayList<>();
			for (E e : values) {
				names.add(e.name().toLowerCase(Locale.ROOT));
			}
			return String.join("|", names);
		}

		private void error(String message) {
			diagnostics.add(HostDirective.error(at, message));
		}
	}
}
