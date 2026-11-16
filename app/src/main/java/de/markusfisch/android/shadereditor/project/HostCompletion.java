package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the editor offers in the lines that name files to the host: the
 * files of a folder in the path of {@code #pragma se input "},
 * {@code #include "} and {@code initial="}, and after the path the
 * options the line can take, then their values. Only the caret's line
 * is read, and the folder's names as a listing has them; no file is
 * read. The keys and values are those {@link HostDirective} reads.
 */
public final class HostCompletion {
	/** Which line a path is in. */
	public enum Kind {INPUT, INCLUDE, INITIAL}

	/** A name in a folder as it was listed, and whether it is a folder. */
	public record Entry(String name, boolean folder) {
	}

	/** The names in the folders of a project, as far as they are listed. */
	public interface Listing {
		/**
		 * Return the entries of folder, the root when null, or null
		 * while they are not listed yet.
		 */
		List<Entry> list(ProjectPath folder);
	}

	/**
	 * A word to offer and what choosing it does to the line: start to
	 * end is replaced by text, and the caret goes to caret; offsets in
	 * the line.
	 */
	public record Choice(String word, int start, int end, String text,
			int caret) {
	}

	/**
	 * The choices to offer, and how many characters of each word are
	 * typed already.
	 */
	public record Offer(List<Choice> choices, int typed) {
		public Offer {
			choices = List.copyOf(choices);
		}

		/** Return the words of the choices, in order. */
		public List<String> words() {
			List<String> words = new ArrayList<>(choices.size());
			for (Choice choice : choices) {
				words.add(choice.word());
			}
			return words;
		}

		/** Return the choice offering word, or null. */
		public Choice choice(String word) {
			for (Choice choice : choices) {
				if (choice.word().equals(word)) {
					return choice;
				}
			}
			return null;
		}
	}

	/**
	 * The quoted path of a host line: its kind and where its text runs
	 * in the line, without the quotes; end is the closing quote, or the
	 * end of the line when there is none.
	 */
	public record Path(Kind kind, int start, int end, boolean closed) {
		/** Return the path's text in line. */
		public String text(String line) {
			return line.substring(start, end);
		}
	}

	// The order options are offered in: the common ones first.
	static final List<String> INPUT_OPTIONS = List.of(
			"frame", "filter", "wrap", "min", "mag", "mipmap");
	static final List<String> TARGET_OPTIONS = List.of(
			"scale", "size", "format", "initial", "present");
	private static final List<String> COMMANDS = List.of("input", "target");
	private static final String INITIAL = "initial=\"";
	private static final String UP = "../";

	private HostCompletion() {
	}

	/**
	 * Return what to offer for the caret at column of line, or null
	 * when the caret is not in a part of a host line that this
	 * completes; an offer may then have no choices, as while the
	 * folder is not listed yet.
	 *
	 * @param file      the file line is in
	 * @param halfFloat whether this device draws into rgba16f targets
	 */
	public static Offer offer(String line, int column, ProjectPath file,
			Listing listing, boolean halfFloat) {
		if (!owns(line, column, true)) {
			return null;
		}
		Tokens tokens = Tokens.of(line, column);
		Path path = tokens.path;
		if (path != null && path.start <= column && column <= path.end) {
			return files(line, column, path, file, listing,
					tokens.has("frame"));
		}
		Token at = tokens.at;
		if (tokens.command == null) {
			return words(COMMANDS, at, line, column, " ");
		}
		int equals = line.indexOf('=', at.start);
		if (equals >= 0 && equals < at.end && column > equals) {
			return values(line, column, at, equals, tokens, halfFloat);
		}
		return options(line, column, at, tokens);
	}

	/**
	 * Return whether the strip at column of line is this class's, the
	 * one rule between the lines that name files and the language: in
	 * the path of {@code #pragma se input "}, {@code #include "} and
	 * {@code initial="}, after the path among the options and their
	 * values, and at the command of a {@code #pragma se} line, the strip
	 * offers what {@link #offer} gives, or the file the path names, and
	 * no call is shown behind its keys. Anywhere else, on these lines
	 * after an {@code #include}'s path or in a comment too, the language
	 * completes and shows the call being typed.
	 *
	 * @param directive whether the lexer read the line as a directive,
	 *                  which a line inside a block comment is not
	 */
	public static boolean owns(String line, int column, boolean directive) {
		Tokens tokens = directive ? Tokens.of(line, column) : null;
		if (tokens == null) {
			return false;
		}
		Path path = tokens.path;
		if (path != null && path.start <= column && column <= path.end) {
			return true;
		} else if (tokens.include) {
			return false;
		}
		Token at = tokens.at;
		return tokens.command == null
				? at != null && at.index == 1
				: at != null && at.index >= 2;
	}

	/**
	 * Return the quoted path the caret at column is in, its quotes
	 * included, or null when it is in none.
	 */
	public static Path pathAt(String line, int column) {
		Tokens tokens = Tokens.of(line, column);
		Path path = tokens != null ? tokens.path : null;
		return path != null && path.start <= column && column <= path.end
				? path
				: null;
	}

	/** A file a path names, and which line names it. */
	public record Named(ProjectPath path, Kind kind) {
	}

	/**
	 * Return the file the path at the caret names when its folder is
	 * listed with it as a file; null when it names none, or while the
	 * folder is not listed.
	 *
	 * @param file the file line is in
	 */
	public static Named named(String line, int column, ProjectPath file,
			Listing listing) {
		Path path = pathAt(line, column);
		ProjectParseResult<ProjectPath> named = path != null
				? ProjectPath.resolve(file, path.text(line))
				: null;
		if (named == null || !named.isSuccess()) {
			return null;
		}
		String name = named.value().getFileName();
		List<Entry> entries = listing.list(folderOf(file,
				path.text(line)));
		for (int i = 0; entries != null && i < entries.size(); ++i) {
			Entry entry = entries.get(i);
			if (!entry.folder() && entry.name().equals(name)) {
				return new Named(named.value(), path.kind());
			}
		}
		return null;
	}

	/**
	 * A file a line names that is not there: its path, which line names
	 * it, the name of a file of its folder to use instead, or null, and
	 * the edit that uses it.
	 */
	public record Missing(ProjectPath path, Kind kind, String near,
			Choice use) {
		/** Return whether a file can be made for it: a text file. */
		public boolean creatable() {
			return FileKind.hint(path.getFileName(), null) != FileKind.PICTURE;
		}
	}

	/**
	 * Return the file line names that message says is not there, with
	 * the nearest name in its folder; null when message says no such
	 * thing of the path line names. While the folder is not listed,
	 * there is no near name.
	 *
	 * @param file the file line is in
	 */
	public static Missing missing(String line, ProjectPath file,
			String message, Listing listing) {
		if (!message.startsWith(CapturedFiles.NOT_FOUND)) {
			return null;
		}
		String named = message.substring(CapturedFiles.NOT_FOUND.length());
		int from = named.indexOf(" (included from ");
		ProjectParseResult<ProjectPath> path = ProjectPath.parse(from < 0
				? named
				: named.substring(0, from));
		Tokens tokens = path.isSuccess()
				? Tokens.of(line, line.length())
				: null;
		Path at = tokens != null ? tokens.path : null;
		ProjectParseResult<ProjectPath> written = at != null
				? ProjectPath.resolve(file, at.text(line))
				: null;
		if (written == null || !written.isSuccess() ||
				!written.value().equals(path.value())) {
			return null;
		}
		String text = at.text(line);
		List<Entry> entries = listing.list(folderOf(file, text));
		String near = entries != null
				? nearest(path.value().getFileName(), at.kind, entries)
				: null;
		return new Missing(path.value(), at.kind, near, near != null
				? retarget(line, at, near)
				: null);
	}

	/**
	 * Return the edit that makes the path of line that names from
	 * name the file to of the same folder instead, or null when line
	 * names no file from.
	 *
	 * @param file the file line is in
	 */
	public static Choice retarget(String line, ProjectPath file,
			ProjectPath from, ProjectPath to) {
		Tokens tokens = Tokens.of(line, line.length());
		Path at = tokens != null ? tokens.path : null;
		ProjectParseResult<ProjectPath> written = at != null
				? ProjectPath.resolve(file, at.text(line))
				: null;
		return written != null && written.isSuccess() &&
				written.value().equals(from)
				? retarget(line, at, to.getFileName())
				: null;
	}

	/**
	 * Return the name of entries nearest name, of a file of the kind a
	 * line of kind names as name is: one that differs only in case, or
	 * else the one alone that differs by one or two letters; null when
	 * none is that near.
	 */
	static String nearest(String name, Kind kind, List<Entry> entries) {
		boolean picture = FileKind.hint(name, null) == FileKind.PICTURE;
		String lower = name.toLowerCase(Locale.ROOT);
		String best = null;
		int distance = 3;
		boolean tie = false;
		for (Entry entry : entries) {
			String n = entry.name();
			if (entry.folder() || n.equals(name) || n.startsWith(".") ||
					rank(kind, n) < 0 ||
					(FileKind.hint(n, null) == FileKind.PICTURE) != picture) {
				continue;
			}
			int d = distance(lower, n.toLowerCase(Locale.ROOT));
			if (d < distance) {
				best = n;
				distance = d;
				tie = false;
			} else if (d == distance) {
				tie = true;
			}
		}
		return best != null && (!tie || distance == 0) ? best : null;
	}

	// The edits from a to b, a swap of two neighbours counting one.
	private static int distance(String a, String b) {
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); ++i) {
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); ++j) {
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); ++i) {
			for (int j = 1; j <= b.length(); ++j) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
						d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) &&
						a.charAt(i - 2) == b.charAt(j - 1)) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[a.length()][b.length()];
	}

	// The edit that puts name in place of the last part of path.
	private static Choice retarget(String line, Path path, String name) {
		int start = path.start + path.text(line).lastIndexOf('/') + 1;
		return new Choice(name, start, path.end, name,
				start + name.length());
	}

	/**
	 * Return the folder typed, the part of a path up to its last slash,
	 * names relative to file, null for the root; or file itself when it
	 * leaves the root or cannot be a folder.
	 */
	static ProjectPath folderOf(ProjectPath file, String typed) {
		String folder = typed.substring(0, typed.lastIndexOf('/') + 1);
		ProjectParseResult<ProjectPath> named = ProjectPath.resolve(file,
				folder + "_");
		if (!named.isSuccess()) {
			return file;
		}
		String path = named.value().toString();
		int slash = path.lastIndexOf('/');
		return slash < 0
				? null
				: ProjectPath.parse(path.substring(0, slash)).value();
	}

	private static Offer files(String line, int column, Path path,
			ProjectPath file, Listing listing, boolean hasFrame) {
		String typed = line.substring(path.start, column);
		int slash = typed.lastIndexOf('/');
		int nameStart = path.start + slash + 1;
		String name = typed.substring(slash + 1);
		ProjectPath folder = folderOf(file, typed);
		List<Entry> entries = folder != file ? listing.list(folder) : null;
		if (entries == null) {
			return new Offer(List.of(), 0);
		}
		int segmentEnd = line.indexOf('/', column);
		segmentEnd = segmentEnd < 0 || segmentEnd > path.end
				? path.end
				: segmentEnd + 1;
		String lower = name.toLowerCase(Locale.ROOT);
		List<Choice> first = new ArrayList<>();
		List<Choice> then = new ArrayList<>();
		List<Choice> folders = new ArrayList<>();
		for (Entry entry : entries) {
			String n = entry.name();
			if (n.startsWith(".") ||
					!n.toLowerCase(Locale.ROOT).startsWith(lower)) {
				continue;
			} else if (entry.folder()) {
				folders.add(new Choice(n + "/", nameStart, segmentEnd,
						n + "/", nameStart + n.length() + 1));
				continue;
			}
			int rank = rank(path.kind, n);
			// The name typed whole and closed has nothing left to
			// complete; unclosed, choosing it closes it.
			if (rank < 0 || (path.closed && n.equals(name))) {
				continue;
			}
			// The name and its closing quote, after which the options
			// are offered.
			String text = n + "\"";
			ProjectParseResult<ProjectPath> named = ProjectPath.parse(
					folder != null ? folder + "/" + n : n);
			if (path.kind == Kind.INPUT && !hasFrame &&
					named.isSuccess() && named.value().equals(file)) {
				// Reading oneself in the same frame is an error.
				text += " frame=previous";
			}
			(rank == 0 ? first : then).add(new Choice(n, nameStart,
					path.closed ? path.end + 1 : path.end, text,
					nameStart + text.length()));
		}
		// Up, from where the file is, while only going up was typed.
		if (folder != null && name.isEmpty() &&
				typed.replace(UP, "").isEmpty()) {
			folders.add(new Choice(UP, column, column, UP,
					column + UP.length()));
		}
		List<Choice> all = new ArrayList<>(first);
		all.addAll(then);
		all.addAll(folders);
		return new Offer(all, name.length());
	}

	// 0 for the files a line names first, 1 for others it can name, -1
	// for files it cannot: a pass or an initial frame names shaders
	// before pictures, an include names text, .glsl first. JSON and
	// Markdown are no shaders.
	private static int rank(Kind kind, String name) {
		boolean picture = FileKind.hint(name, null) == FileKind.PICTURE;
		if (kind == Kind.INCLUDE) {
			return picture || ShaderLanguage.of(name) != ShaderLanguage.GLSL
					? -1
					: name.toLowerCase(Locale.ROOT).endsWith(
					ShaderLanguage.INCLUDE) ? 0 : 1;
		}
		return ShaderLanguage.namesShader(name) ? 0 : picture ? 1 : -1;
	}

	private static Offer options(String line, int column, Token at,
			Tokens tokens) {
		boolean input = tokens.command.equals("input");
		List<String> keys = new ArrayList<>();
		for (String key : input ? INPUT_OPTIONS : TARGET_OPTIONS) {
			if (fits(key, input, tokens)) {
				keys.add(key + (key.equals("initial") ? "=\"" : "="));
			}
		}
		return words(keys, at, line, column, "");
	}

	// Whether key may be added to the line: it is not there, nothing
	// there excludes it, and it applies to what the input reads.
	private static boolean fits(String key, boolean input, Tokens tokens) {
		if (tokens.has(key)) {
			return false;
		}
		if (!input) {
			return switch (key) {
				case "scale" -> !tokens.has("size");
				case "size" -> !tokens.has("scale");
				default -> true;
			};
		}
		String source = tokens.source;
		boolean picture = source != null &&
				FileKind.hint(source, null) == FileKind.PICTURE;
		return switch (key) {
			case "filter" -> !tokens.has("min") && !tokens.has("mag");
			case "min", "mag" -> !tokens.has("filter");
			case "frame" -> source != null && !picture;
			default -> true;
		};
	}

	private static Offer values(String line, int column, Token at,
			int equals, Tokens tokens, boolean halfFloat) {
		String key = line.substring(at.start, equals);
		List<String> values = switch (key) {
			case "frame" -> names(HostDirective.Frame.values());
			case "filter", "min", "mag", "present" ->
					names(HostDirective.Filter.values());
			case "mipmap" -> names(HostDirective.Mipmap.values());
			case "wrap" -> names(HostDirective.Wrap.values());
			// The usual scales of a pass, as the passes sheet's Size
			// line offers them; any other value can still be typed.
			case "scale" -> List.of("1", "0.5", "0.25", "0.125");
			case "format" -> halfFloat
					? names(HostDirective.Format.values())
					: List.of("rgba8");
			default -> null;
		};
		boolean known = tokens.command.equals("input")
				? HostDirective.INPUT_KEYS.contains(key)
				: HostDirective.TARGET_KEYS.contains(key);
		if (values == null || !known) {
			return new Offer(List.of(), 0);
		}
		// wrap takes one value for each axis.
		int start = key.equals("wrap")
				? Math.max(equals, line.lastIndexOf(',', column - 1)) + 1
				: equals + 1;
		int end = key.equals("wrap") ? line.indexOf(',', column) : -1;
		end = end < 0 || end > at.end ? at.end : end;
		String typed = line.substring(start, column);
		List<Choice> choices = new ArrayList<>();
		for (String value : values) {
			if (value.startsWith(typed) && !value.equals(typed)) {
				choices.add(new Choice(value, start, end, value,
						start + value.length()));
			}
		}
		return new Offer(choices, typed.length());
	}

	private static Offer words(List<String> words, Token at, String line,
			int column, String after) {
		String typed = line.substring(at.start, column);
		int equals = line.indexOf('=', at.start);
		// What is typed of a key, its = included, is replaced whole.
		int end = equals >= 0 && equals < at.end ? equals + 1 : at.end;
		List<Choice> choices = new ArrayList<>();
		for (String word : words) {
			String shown = word.endsWith("\"")
					? word.substring(0, word.length() - 1)
					: word;
			if (word.startsWith(typed) && !shown.equals(typed)) {
				String text = word + (end < line.length() ? "" : after);
				choices.add(new Choice(shown, at.start, end, text,
						at.start + text.length()));
			}
		}
		return new Offer(choices, typed.length());
	}

	private static <E extends Enum<E>> List<String> names(E[] values) {
		List<String> names = new ArrayList<>(values.length);
		for (E value : values) {
			names.add(value.name().toLowerCase(Locale.ROOT));
		}
		return names;
	}

	// A word of the line from start to end; index counts the words
	// after the directive's name.
	private record Token(int index, int start, int end) {
	}

	// The words of a host line around the caret, read as HostDirective
	// reads them: quoted text may contain spaces, a // ends the line.
	private static final class Tokens {
		private final List<Token> words = new ArrayList<>();
		private final String line;
		private boolean include;
		private String command;
		private Path path;
		// The file an input reads, as written, or null.
		private String source;
		// The word the caret is in or after, or a new one where it
		// stands between words.
		private Token at;

		private Tokens(String line) {
			this.line = line;
		}

		private static Tokens of(String line, int column) {
			int n = line.length();
			int i = skip(line, 0);
			if (i >= n || line.charAt(i) != '#') {
				return null;
			}
			i = skip(line, i + 1);
			int name = i;
			while (i < n && Character.isLetter(line.charAt(i))) {
				++i;
			}
			String directive = line.substring(name, i);
			Tokens tokens = new Tokens(line);
			if (directive.equals("include")) {
				tokens.include = true;
				i = skip(line, i);
				if (i < n && line.charAt(i) == '"') {
					int close = line.indexOf('"', i + 1);
					tokens.path = new Path(Kind.INCLUDE, i + 1,
							close < 0 ? n : close, close >= 0);
				}
				return column > name + 7 ? tokens : null;
			} else if (!directive.equals("pragma") || column <= i) {
				return null;
			}
			return tokens.read(i, column) ? tokens : null;
		}

		// Reads the words from i; false when the caret is in a comment
		// or this is no host line.
		private boolean read(int i, int column) {
			int n = line.length();
			for (int index = 0; ; ++index) {
				i = skip(line, i);
				if (line.startsWith("//", i) || line.startsWith("/*", i)) {
					n = i;
				}
				if (i >= n) {
					break;
				}
				int start = i;
				while (i < n && line.charAt(i) != ' ' &&
						line.charAt(i) != '\t') {
					if (line.charAt(i) == '"') {
						int close = line.indexOf('"', i + 1);
						quoted(index, start, i, close);
						if (close < 0) {
							i = line.length();
							break;
						}
						i = close;
					}
					++i;
				}
				Token token = new Token(index, start, i);
				words.add(token);
				if (start <= column && column <= i) {
					at = token;
				}
			}
			if (column > n) {
				return false;
			} else if (words.isEmpty() || !word(0).equals("se")) {
				return false;
			} else if (words.size() > 1 &&
					COMMANDS.contains(word(1)) &&
					(at == null || at.index > 1)) {
				command = word(1);
			}
			if (at == null) {
				int index = words.size();
				for (int w = 0; w < words.size(); ++w) {
					if (words.get(w).start > column) {
						index = w;
						break;
					}
				}
				at = new Token(index, column, column);
			}
			return at.index > 0;
		}

		private void quoted(int index, int start, int quote, int close) {
			int end = close < 0 ? line.length() : close;
			if (index == 2 && quote == start && "input".equals(word(1))) {
				path = new Path(Kind.INPUT, quote + 1, end, close >= 0);
				source = line.substring(quote + 1, end);
			} else if (index >= 2 && "target".equals(word(1)) &&
					line.startsWith(INITIAL, start) &&
					quote == start + INITIAL.length() - 1) {
				path = new Path(Kind.INITIAL, quote + 1, end, close >= 0);
			}
		}

		private String word(int index) {
			Token token = words.get(index);
			return line.substring(token.start, token.end);
		}

		// Whether a word other than the caret's sets key.
		private boolean has(String key) {
			for (Token token : words) {
				if (token != at && token.index >= 2 &&
						line.startsWith(key + "=", token.start)) {
					return true;
				}
			}
			return false;
		}

		private static int skip(String line, int i) {
			while (i < line.length() && (line.charAt(i) == ' ' ||
					line.charAt(i) == '\t')) {
				++i;
			}
			return i;
		}
	}
}
