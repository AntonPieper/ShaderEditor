package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * What the files of a project know of each other: for each file what
 * its language knows of its text, and which files it includes. From
 * that, what a file sees of the others: the declarations of the files
 * it includes, directly or through other includes, and, where those do
 * not explain a word, of the files that include it.
 *
 * It knows no language: a file's knowledge comes from the syntax of its
 * language, and an include is a link the knowledge reports, which is
 * resolved as the build resolves it. The edges are kept in both
 * directions, so that the files a declaration reaches can be told: all
 * that include its file.
 *
 * Immutable but for what it remembers of answers it gave, so it is made
 * on any thread and asked on one.
 */
public final class ProjectKnowledge {
	/** Knows no file. */
	public static final ProjectKnowledge NONE = new ProjectKnowledge(
			new LinkedHashMap<>());

	/** The most names a file completes from other files. */
	static final int MAX_NAMES = 2000;
	// The bounds of what is kept of one file, as the editor's analysis
	// has them.
	private static final int MAX_SYMBOLS = 10000;
	private static final int MAX_CHARS = 1 << 19;
	private static final int MAX_LINE_LENGTH = 1 << 16;
	private static final byte[] INCLUDE = {'i', 'n', 'c', 'l', 'u', 'd',
			'e'};

	private record Entry(byte[] bytes, Knowledge knowledge,
			List<ProjectPath> includes) {
	}

	// For a knowledge of a whole folder, read() made: the revision each
	// file was read at. Null for one of the files of a build.
	private Map<ProjectPath, String> revisions;

	// In the order the files were given.
	private final Map<ProjectPath, Entry> entries;
	// For each file the files that include it themselves.
	private final Map<ProjectPath, List<ProjectPath>> includers =
			new HashMap<>();
	// Answers given before: what a file includes, and the names it sees.
	private final Map<ProjectPath, Set<ProjectPath>> included =
			new HashMap<>();
	private final Map<ProjectPath, List<String>> names = new HashMap<>();

	private ProjectKnowledge(Map<ProjectPath, Entry> entries) {
		this.entries = entries;
		for (Map.Entry<ProjectPath, Entry> e : entries.entrySet()) {
			for (ProjectPath included : e.getValue().includes) {
				List<ProjectPath> by = includers.get(included);
				if (by == null) {
					by = new ArrayList<>();
					includers.put(included, by);
				}
				by.add(e.getKey());
			}
		}
	}

	/**
	 * Read files, each the bytes of a text file by its path, as the
	 * syntax of its language reads it. What previous knows of a file
	 * with the same bytes is kept, so that only a file that changed is
	 * read again; and a file that includes nothing and that nothing
	 * includes is not read at all, since nobody asks about it: a shader
	 * of one file costs a search for a word.
	 */
	public static ProjectKnowledge of(Map<ProjectPath, byte[]> files,
			ProjectKnowledge previous) {
		Map<ProjectPath, Entry> entries = new LinkedHashMap<>();
		Set<ProjectPath> related = new HashSet<>();
		for (Map.Entry<ProjectPath, byte[]> file : files.entrySet()) {
			ProjectPath path = file.getKey();
			byte[] bytes = file.getValue();
			Entry entry = previous.entries.get(path);
			if (entry == null || !Arrays.equals(entry.bytes, bytes)) {
				// Without the word, it includes nothing.
				entry = mentions(bytes, INCLUDE)
						? read(path, bytes)
						: new Entry(bytes, null, Collections.emptyList());
			}
			entries.put(path, entry);
			if (!entry.includes.isEmpty()) {
				related.add(path);
				related.addAll(entry.includes);
			}
		}
		for (ProjectPath path : related) {
			Entry entry = entries.get(path);
			if (entry != null && entry.knowledge == null) {
				entries.put(path, read(path, entry.bytes));
			}
		}
		return entries.isEmpty() ? NONE : new ProjectKnowledge(entries);
	}

	// What the language of path knows of its text, and its includes.
	private static Entry read(ProjectPath path, byte[] bytes) {
		String text = SourceText.decode(bytes);
		Knowledge knowledge = text == null
				? Knowledge.NONE
				: read(ShaderLanguage.of(path.toString()).syntax(), text);
		List<ProjectPath> includes = new ArrayList<>();
		for (Symbol link : knowledge.links()) {
			if (link.kind() != Knowledge.Kind.INCLUDE) {
				continue;
			}
			ProjectParseResult<ProjectPath> target =
					ProjectPath.resolve(path, link.name());
			if (target.isSuccess() && !includes.contains(target.value())) {
				includes.add(target.value());
			}
		}
		return new Entry(bytes, knowledge,
				Collections.unmodifiableList(includes));
	}

	// Feeds the lines of text to the syntax, as the editor's analysis
	// does, with its bounds.
	private static Knowledge read(Syntax syntax, String text) {
		Knowledge.Builder builder = syntax.knowledge(MAX_SYMBOLS, MAX_CHARS);
		if (builder == null) {
			return Knowledge.NONE;
		}
		LineTokens tokens = new LineTokens();
		int state = Syntax.TEXT_START;
		for (int start = 0, length = text.length(); start <= length; ) {
			int end = text.indexOf('\n', start);
			if (end < 0) {
				end = length;
			}
			if (end - start > MAX_LINE_LENGTH) {
				return Knowledge.NONE;
			}
			String line = text.substring(start, end);
			state = syntax.lexLine(line, end < length, state, tokens);
			builder.addLine(line, start, tokens);
			start = end + 1;
		}
		return builder.build();
	}

	private static boolean mentions(byte[] bytes, byte[] word) {
		for (int i = 0, n = bytes.length - word.length; i <= n; ++i) {
			int k = 0;
			while (k < word.length && bytes[i + k] == word[k]) {
				++k;
			}
			if (k == word.length) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Return what is known of the text of path; null for a file that
	 * was not given, and for one that includes nothing and that nothing
	 * includes.
	 */
	public Knowledge of(ProjectPath path) {
		Entry entry = entries.get(path);
		return entry != null ? entry.knowledge : null;
	}

	/**
	 * Return the files path includes, directly or through other
	 * includes, in the order their text comes in.
	 */
	public Set<ProjectPath> includes(ProjectPath path) {
		Set<ProjectPath> found = included.get(path);
		if (found == null) {
			found = new LinkedHashSet<>();
			collect(path, found, true);
			found.remove(path);
			found = Collections.unmodifiableSet(found);
			included.put(path, found);
		}
		return found;
	}

	/**
	 * Return the files that include path, directly or through other
	 * includes: the files a declaration of path reaches.
	 */
	public Set<ProjectPath> includers(ProjectPath path) {
		Set<ProjectPath> found = new LinkedHashSet<>();
		collect(path, found, false);
		found.remove(path);
		return found;
	}

	// Adds what path includes or is included by to found, a file's own
	// includes before the file that follows it.
	private void collect(ProjectPath path, Set<ProjectPath> found,
			boolean down) {
		List<ProjectPath> next;
		if (down) {
			Entry entry = entries.get(path);
			next = entry != null ? entry.includes : null;
		} else {
			next = includers.get(path);
		}
		if (next == null) {
			return;
		}
		for (ProjectPath other : next) {
			if (!other.equals(path) && found.add(other)) {
				collect(other, found, down);
			}
		}
	}

	/**
	 * Return the names path sees of the files it includes, each once,
	 * sorted, at most {@link #MAX_NAMES}; the same list every time.
	 */
	public List<String> names(ProjectPath path) {
		List<String> seen = names.get(path);
		if (seen == null) {
			Set<String> sorted = new TreeSet<>();
			for (ProjectPath included : includes(path)) {
				Knowledge knowledge = of(included);
				if (knowledge == null) {
					continue;
				}
				for (Symbol symbol : knowledge.symbols()) {
					sorted.add(symbol.name());
				}
			}
			seen = new ArrayList<>(sorted);
			if (seen.size() > MAX_NAMES) {
				seen = seen.subList(0, MAX_NAMES);
			}
			seen = Collections.unmodifiableList(seen);
			names.put(path, seen);
		}
		return seen;
	}

	/**
	 * Return what word refers to in path as other files tell, each
	 * symbol with its file: what the files path includes declare, a
	 * function with every form; else, for a file that is included
	 * itself, what a file that includes it sees.
	 */
	public List<Symbol> find(ProjectPath path, CharSequence word) {
		List<Symbol> found = find(includes(path), word);
		if (!found.isEmpty()) {
			return found;
		}
		Set<ProjectPath> around = new LinkedHashSet<>();
		for (ProjectPath includer : includers(path)) {
			around.add(includer);
			around.addAll(includes(includer));
		}
		around.remove(path);
		return find(around, word);
	}

	/**
	 * Return the line, from 0, the name of symbol stands in in its
	 * file, for a symbol find gave; -1 if the file is not known.
	 */
	public int line(Symbol symbol) {
		ProjectParseResult<ProjectPath> path = symbol.file() != null
				? ProjectPath.parse(symbol.file()) : null;
		Entry entry = path != null && path.isSuccess()
				? entries.get(path.value()) : null;
		String text = entry != null ? SourceText.decode(entry.bytes) : null;
		if (text == null) {
			return -1;
		}
		int line = 0;
		for (int i = Math.min(symbol.start(), text.length()); i-- > 0; ) {
			if (text.charAt(i) == '\n') {
				++line;
			}
		}
		return line;
	}

	/**
	 * Read every text file of a project folder, with the revision of
	 * each: what the uses of a declaration are asked of. Null if a
	 * folder cannot be listed or a file cannot be read, since then the
	 * files are not all known.
	 */
	public static ProjectKnowledge read(ProjectFiles files) {
		Map<ProjectPath, byte[]> texts = new LinkedHashMap<>();
		Map<ProjectPath, String> revisions = new HashMap<>();
		List<ProjectPath> folders = new ArrayList<>();
		folders.add(null);
		for (int i = 0; i < folders.size(); ++i) {
			ProjectPath folder = folders.get(i);
			if (!(files.list(folder) instanceof ProjectFiles.Entries
					entries) || folders.size() > MAX_FOLDERS) {
				return null;
			}
			for (ProjectFiles.Entry entry : entries.entries()) {
				ProjectParseResult<ProjectPath> path = ProjectPath.parse(
						folder != null ? folder + "/" + entry.name()
								: entry.name());
				if (!path.isSuccess()) {
					// A name no path spells is no file of the project.
					continue;
				} else if (entry.folder()) {
					folders.add(path.value());
					continue;
				} else if (ShaderLanguage.of(path.value().toString()) !=
						ShaderLanguage.GLSL || FileKind.hint(entry.name(),
						null) != FileKind.TEXT) {
					continue;
				}
				ProjectFiles.Read read = files.read(path.value(),
						ProjectLimits.DEFAULT.sourceBytes());
				if (!(read instanceof ProjectFiles.Content content)) {
					return null;
				} else if (FileKind.of(content.bytes()) == FileKind.TEXT) {
					texts.put(path.value(), content.bytes());
					revisions.put(path.value(), content.revision());
				}
			}
		}
		Map<ProjectPath, Entry> entries = new LinkedHashMap<>();
		for (Map.Entry<ProjectPath, byte[]> text : texts.entrySet()) {
			entries.put(text.getKey(), read(text.getKey(), text.getValue()));
		}
		ProjectKnowledge knowledge = new ProjectKnowledge(entries);
		knowledge.revisions = revisions;
		return knowledge;
	}

	private static final int MAX_FOLDERS = 4096;

	/**
	 * Something that stands between a declaration and a place, see
	 * {@link Knowledge.Cause}, with the line it is defined or stands
	 * in, from 0, or -1 if it is in no file.
	 */
	public record Reason(Knowledge.Use.Why why, String name,
			ProjectPath file, int line) {
	}

	/**
	 * A place in a file where a name stands, with all a list of places
	 * shows: nobody has to read the file again for it.
	 *
	 * @param file     the file
	 * @param revision the revision of the file the place was read in
	 * @param line     its line, from 0
	 * @param start    where the name starts in that line
	 * @param end      where it ends in that line
	 * @param offset   where the name starts in the file's text
	 * @param text     the line as it is, without its line end; of a
	 *                 line of more than 400 characters what stands
	 *                 around the name, and start and end are in that
	 * @param why      what the place is: the declaration, a use for
	 *                 certain, or why it is not certain
	 * @param reasons  what stands between the declaration and the
	 *                 place, in the order of the program: places with
	 *                 the same reasons can be shown and settled
	 *                 together
	 */
	public record Place(ProjectPath file, String revision, int line,
			int start, int end, int offset, String text,
			Knowledge.Use.Why why, List<Reason> reasons) {
		/** Whether the place means the declaration for certain. */
		public boolean certain() {
			return why == Knowledge.Use.Why.DECLARATION ||
					why == Knowledge.Use.Why.USE;
		}

		/** Return the name of the first reason, or null. */
		public String about() {
			return reasons.isEmpty() ? null : reasons.get(0).name();
		}
	}

	/**
	 * The uses of a declaration, and what a rename needs to keep its
	 * promise.
	 *
	 * @param places the places, the declaring file first and each file
	 *               in the order of its text
	 * @param taken  where the name that was asked about already means
	 *               something at one of the places: the declarations
	 *               it means
	 * @param read   every file a program read, with the revision it
	 *               was read at: a rename may change the text only if
	 *               each of them still has that revision, not only the
	 *               files with places, since a file without one can
	 *               change what places mean
	 * @param depends the conditions the text does not decide on which
	 *               it depends what the name means at one of the
	 *               places, with their lines; empty if on none. With
	 *               one, a place may mean the declaration with one
	 *               outcome and something else with another, so a
	 *               rename can refuse or warn instead of leaving such
	 *               a place behind
	 */
	public record Uses(List<Place> places, List<Place> taken,
			Map<ProjectPath, String> read, List<Reason> depends,
			Knowledge.Answer answer) {
		/**
		 * Return the place the name at offset of file is, or null: a
		 * rename may start only from the declaration or from a place
		 * that is certain, and says the reasons of any other.
		 */
		public Place at(ProjectPath file, int offset) {
			for (Place place : places) {
				if (place.file().equals(file) && offset >= place.offset() &&
						offset <= place.offset() + place.end() -
								place.start()) {
					return place;
				}
			}
			return null;
		}

		/**
		 * Compare what every name means here with what it means in the
		 * programs as they were read again after names were changed;
		 * see {@link Knowledge.Answer#compare}. A rename that finds a
		 * name CHANGED takes itself back; one that finds UNPROVEN has
		 * nothing that vouches for it where a macro that was not
		 * executed or a condition that is not decided stands, and says
		 * so.
		 */
		public Knowledge.Answer.Bindings compare(Uses later) {
			return later == null ? Knowledge.Answer.Bindings.CHANGED
					: answer.compare(later.answer);
		}

		/**
		 * Whether every name is proven to mean what it meant here, in
		 * the programs as they were read again after names were
		 * changed: false also where that is only not proven.
		 */
		public boolean sameBindings(Uses later) {
			return compare(later) == Knowledge.Answer.Bindings.SAME;
		}
	}

	/**
	 * Return whether symbol can be renamed at all: not what the host
	 * finds by its name, one of hostNames, a uniform the engine sets
	 * or a macro it reads, which would be lost under any other; not
	 * the function a shader starts at; and not a file a text names.
	 * What GLSL has itself is declared nowhere and has no symbol to ask
	 * about.
	 */
	public static boolean renameable(Symbol symbol, Set<String> hostNames) {
		Knowledge.Kind kind = symbol.kind();
		return kind != Knowledge.Kind.INCLUDE && kind != Knowledge.Kind.FILE &&
				!symbol.name().isEmpty() &&
				!((kind == Knowledge.Kind.UNIFORM ||
						kind == Knowledge.Kind.MACRO) &&
						hostNames.contains(symbol.name())) &&
				!(kind == Knowledge.Kind.FUNCTION &&
						symbol.name().equals(Glsl.ENTRY_POINT));
	}

	/**
	 * Return every place where symbol, which the file at path declares,
	 * is used, asked of a knowledge of the whole folder as
	 * {@link #read} made it: what a rename changes and what it asks
	 * about, each place with its line, the range of the name in it and
	 * the line's text.
	 *
	 * The programs are every file that nothing includes and that
	 * reaches the declaring file. Each is made as the build makes it,
	 * its includes expanded, its host directives blanked and its text
	 * as the driver gets it ({@link CompiledSource}), and read by the
	 * language of the declaring file; see {@link Syntax#uses} and
	 * "Uses, third shape" in docs/project-mode/LANGUAGE.md for when a
	 * place is certain, which means proven, and for every other
	 * reason. A place in a file that a program compiles which was not
	 * read is {@link Knowledge.Use.Why#NOT_INCLUDED}.
	 *
	 * Null when it cannot be told exactly: this is no knowledge of a
	 * whole folder; the file is not at the revision the symbol is of;
	 * a program does not build; or a text makes its language guess.
	 *
	 * @param revision the revision of the file the symbol was made of
	 * @param name     a name the declaration may be given, to tell
	 *                 where it is taken, or null
	 * @param builtins what the engine sets, as a build is given it
	 */
	public Uses uses(ProjectPath path, String revision, Symbol symbol,
			String name, Builtins builtins) {
		if (revisions == null || revision == null ||
				!revision.equals(revisions.get(path))) {
			return null;
		}
		ProjectFiles files = new ProjectFiles() {
			@Override
			public Read read(ProjectPath file) {
				byte[] bytes = bytes(file);
				return bytes == null ? null : new Content(bytes,
						revisions.get(file));
			}
		};
		// The programs that compile the file: itself if it is one,
		// and every program that includes it.
		Set<ProjectPath> all = programs(files, builtins);
		List<ProjectPath> roots = roots(path, all);
		Map<ProjectPath, String> read = new LinkedHashMap<>();
		List<Syntax.Program> programs = new ArrayList<>();
		Map<ProjectPath, Text> texts = new HashMap<>();
		for (ProjectPath root : roots) {
			PassSource source = ShaderLanguage.of(root.toString()).pass(
					files, root, ProjectLimits.DEFAULT, builtins);
			for (ProjectDiagnostic diagnostic : source.diagnostics()) {
				if (diagnostic.severity() ==
						ProjectDiagnostic.Severity.ERROR) {
					// What does not build has no uses to prove.
					return null;
				}
			}
			read.put(root, revisions.get(root));
			for (ProjectPath included : includes(root)) {
				read.put(included, revisions.get(included));
			}
			programs.add(new Compiled(CompiledSource.of(source.lines(),
					source.head(), source.sourceMap()), texts));
		}
		String declaring = path.toString();
		Knowledge.Answer answer = ShaderLanguage.of(declaring).syntax()
				.uses(programs, symbol.in(declaring), name);
		if (answer == null) {
			return null;
		}
		// File by file, the declaring one first.
		Map<String, List<Knowledge.Use>> byFile = new LinkedHashMap<>();
		byFile.put(declaring, new ArrayList<>());
		for (Knowledge.Use use : answer.uses()) {
			List<Knowledge.Use> of = byFile.get(use.file());
			if (of == null) {
				of = new ArrayList<>();
				byFile.put(use.file(), of);
			}
			of.add(use);
		}
		List<Place> places = new ArrayList<>(answer.uses().size());
		for (Map.Entry<String, List<Knowledge.Use>> of :
				byFile.entrySet()) {
			ProjectPath file = ProjectPath.parse(of.getKey()).value();
			// Certain only if every program that compiles the file was
			// read.
			boolean whole = file.equals(path) || roots.containsAll(
					roots(file, all));
			Collections.sort(of.getValue(), (a, b) -> a.start() - b.start());
			for (Knowledge.Use use : of.getValue()) {
				// Also another head of a function: in a program that
				// was not read, nothing says it is the same one.
				if (!whole && use.certain()) {
					use = use.because(Knowledge.Use.Why.NOT_INCLUDED,
							Collections.emptyList());
				}
				places.add(place(texts, use));
			}
		}
		List<Place> taken = new ArrayList<>();
		for (Knowledge.Use use : answer.taken()) {
			taken.add(place(texts, use));
		}
		return new Uses(places, taken, read, reasons(texts,
				answer.depends()), answer);
	}

	private byte[] bytes(ProjectPath file) {
		Entry entry = entries.get(file);
		return entry == null ? null : entry.bytes;
	}

	// The programs of the folder, for a knowledge of all of it.
	private Set<ProjectPath> programs;
	private static final byte[] PRAGMA = {'p', 'r', 'a', 'g', 'm', 'a'};

	/**
	 * Return the programs of the folder: every file that is given to
	 * a compiler as the first of a program. That is a file nothing
	 * includes; a file whose own text defines what a shader starts
	 * at, which runs when it is chosen to, whatever includes it; and
	 * a file another one reads as a pass. Which files include which
	 * says what a program is made of, not which files are programs.
	 */
	private Set<ProjectPath> programs(ProjectFiles files,
			Builtins builtins) {
		if (programs != null) {
			return programs;
		}
		Set<ProjectPath> found = new LinkedHashSet<>();
		for (Map.Entry<ProjectPath, Entry> e : entries.entrySet()) {
			ProjectPath path = e.getKey();
			byte[] bytes = e.getValue().bytes;
			String text = SourceText.decode(bytes);
			if (!includers.containsKey(path) || (text != null &&
					ShaderLanguage.of(path.toString()).runnable(
							de.markusfisch.android.shadereditor.editor
									.Text.of(text)))) {
				found.add(path);
			}
			if (mentions(bytes, PRAGMA)) {
				// What it asks of the host, as the build reads it.
				for (HostDirective.Input input : ShaderLanguage.of(
						path.toString()).pass(files, path,
						ProjectLimits.DEFAULT, builtins).inputs()) {
					if (input.source() != null &&
							entries.containsKey(input.source())) {
						found.add(input.source());
					}
				}
			}
		}
		return programs = found;
	}

	// The programs that compile path: itself if it is one, and every
	// program among the files that include it.
	private List<ProjectPath> roots(ProjectPath path,
			Set<ProjectPath> programs) {
		List<ProjectPath> roots = new ArrayList<>();
		if (programs.contains(path)) {
			roots.add(path);
		}
		for (ProjectPath includer : includers(path)) {
			if (programs.contains(includer)) {
				roots.add(includer);
			}
		}
		return roots;
	}

	// The longest text of a line a place carries.
	private static final int MAX_LINE_TEXT = 400;

	// A text of the folder with where its lines start.
	// With whether it has a character of two units at all: where it
	// has none, a column is an offset in its line.
	private record Text(String text, int[] starts, String revision,
			boolean wide) {
	}

	private Text text(Map<ProjectPath, Text> texts, ProjectPath file) {
		Text text = texts.get(file);
		if (text == null) {
			Entry entry = entries.get(file);
			String decoded = entry == null ? ""
					: SourceText.decode(entry.bytes);
			decoded = decoded == null ? "" : decoded;
			int count = 1;
			for (int i = decoded.indexOf('\n'); i >= 0;
					i = decoded.indexOf('\n', i + 1)) {
				++count;
			}
			int[] starts = new int[count];
			count = 1;
			for (int i = decoded.indexOf('\n'); i >= 0;
					i = decoded.indexOf('\n', i + 1)) {
				starts[count++] = i + 1;
			}
			boolean wide = false;
			for (int i = 0, n = decoded.length(); i < n && !wide; ++i) {
				wide = Character.isSurrogate(decoded.charAt(i));
			}
			text = new Text(decoded, starts, revisions == null ? null
					: revisions.get(file), wide);
			texts.put(file, text);
		}
		return text;
	}

	// The place of a use: its line, found among the starts of the
	// lines of its file. Of a line longer than anyone reads, the text
	// is what stands around the name, and the name's range is in that.
	private Place place(Map<ProjectPath, Text> texts, Knowledge.Use use) {
		ProjectPath file = ProjectPath.parse(use.file()).value();
		Text text = text(texts, file);
		int offset = Math.max(0, Math.min(use.start(), text.text.length()));
		int line = lineOf(text, offset);
		int from = text.starts[line];
		int to = line + 1 < text.starts.length ? text.starts[line + 1] - 1
				: text.text.length();
		if (to - from > MAX_LINE_TEXT) {
			from = Math.max(from, offset - MAX_LINE_TEXT / 4);
			to = Math.min(to, from + MAX_LINE_TEXT);
		}
		return new Place(file, text.revision, line, offset - from,
				Math.max(offset, Math.min(use.end(), to)) - from, offset,
				text.text.substring(from, to), use.why(), reasons(texts,
				use.causes()));
	}

	private List<Reason> reasons(Map<ProjectPath, Text> texts,
			List<Knowledge.Cause> causes) {
		List<Reason> reasons = new ArrayList<>(causes.size());
		for (Knowledge.Cause cause : causes) {
			ProjectPath in = cause.file() == null ? null
					: ProjectPath.parse(cause.file()).value();
			reasons.add(new Reason(cause.why(), cause.name(), in,
					in == null || cause.start() < 0 ? -1
							: lineOf(text(texts, in), cause.start())));
		}
		return reasons;
	}

	private static int lineOf(Text text, int offset) {
		int line = Arrays.binarySearch(text.starts, offset);
		return line < 0 ? -line - 2 : line;
	}

	// A program as the driver gets it, for a language to read: the
	// lines of its text, and where each came from in the files of the
	// folder.
	private final class Compiled implements Syntax.Program {
		private final CompiledSource source;
		private final String[] lines;
		private final Map<ProjectPath, Text> texts;

		Compiled(CompiledSource source, Map<ProjectPath, Text> texts) {
			this.source = source;
			this.texts = texts;
			List<String> split = SourceText.lines(source.text());
			lines = split.toArray(new String[0]);
		}

		@Override
		public int version() {
			return source.version();
		}

		@Override
		public int lines() {
			return lines.length;
		}

		@Override
		public CharSequence line(int n) {
			return lines[n];
		}

		@Override
		public String file(int n) {
			SourceLocation at = source.sourceMap().locate(n + 1);
			return at == null || at.isGenerated() ? null
					: at.file().toString();
		}

		// The text the driver gets has one character where the file
		// has one, also where that takes two units of the file's text
		// and became a space.
		@Override
		public int offset(int n, int column) {
			SourceLocation at = source.sourceMap().locate(n + 1);
			if (at == null || at.isGenerated()) {
				return -1;
			}
			Text text = text(texts, at.file());
			int line = at.line() - 1;
			if (line < 0 || line >= text.starts.length) {
				return -1;
			}
			int offset = text.starts[line];
			if (!text.wide) {
				// Not counted character by character: a line of many
				// names would cost its length for each of them.
				return Math.min(offset + column, text.text.length());
			}
			for (int c = 0; c < column && offset < text.text.length(); ++c) {
				offset += Character.charCount(text.text.codePointAt(offset));
			}
			return offset;
		}
	}

	// What is known of the file at path: as it was read with the
	// others, or read now, for a file that nothing includes and that
	// includes nothing. Null for a file this was not given.
	private Knowledge known(ProjectPath path) {
		Entry entry = entries.get(path);
		return entry == null ? null : entry.knowledge != null
				? entry.knowledge : read(path, entry.bytes).knowledge;
	}

	private List<Symbol> find(Set<ProjectPath> files, CharSequence word) {
		List<Symbol> found = new ArrayList<>();
		for (ProjectPath file : files) {
			Knowledge knowledge = of(file);
			if (knowledge == null) {
				continue;
			}
			for (Symbol symbol : knowledge.at(-1, word)) {
				// A function gathers its forms from every file; of
				// anything else the first declaration stands.
				boolean function = symbol.kind() == Knowledge.Kind.FUNCTION;
				if (found.isEmpty() || (function && found.get(0).kind() ==
						Knowledge.Kind.FUNCTION)) {
					found.add(symbol.in(file.toString()));
				}
			}
		}
		return found;
	}
}
