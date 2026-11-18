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

import de.markusfisch.android.shadereditor.editor.Definitions;
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

	// In the order the files were given.
	private final Map<ProjectPath, Entry> entries;
	// For each file the files that include it themselves.
	private final Map<ProjectPath, List<ProjectPath>> includers =
			new HashMap<>();
	// Answers given before: what a file includes, and the names it sees.
	private final Map<ProjectPath, Set<ProjectPath>> included =
			new HashMap<>();
	private final Map<ProjectPath, List<String>> names = new HashMap<>();
	private final Map<ProjectPath, List<Symbol>> macros = new HashMap<>();
	private final Map<ProjectPath, Boolean> wholes = new HashMap<>();
	private final Map<ProjectPath, int[]> lineStarts = new HashMap<>();

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
		return found.isEmpty() ? find(around(path), word) : found;
	}

	/**
	 * Return every declaration of word in the files path includes, then
	 * in those around it, each with its file, in their order: where
	 * find answers with the first declaration of a name that is no
	 * function, or asks the files around only when the includes have
	 * none, this answers with all.
	 */
	public List<Symbol> all(ProjectPath path, CharSequence word) {
		return all(reach(path), word);
	}

	/**
	 * Return the macros of the files all asks, each with its file, in
	 * their order.
	 */
	public List<Symbol> macros(ProjectPath path) {
		List<Symbol> found = macros.get(path);
		if (found == null) {
			found = new ArrayList<>();
			for (ProjectPath file : reach(path)) {
				Knowledge knowledge = of(file);
				for (Symbol symbol : knowledge != null
						? knowledge.symbols()
						: Collections.<Symbol>emptyList()) {
					if (symbol.kind() == Knowledge.Kind.MACRO) {
						found.add(symbol.in(file.toString()));
					}
				}
			}
			found = Collections.unmodifiableList(found);
			macros.put(path, found);
		}
		return found;
	}

	/**
	 * Return whether all and find know everything declared outside of
	 * the text of path whose knowledge is known: the build read path,
	 * and every file it names to include; each file around it was read
	 * whole; and no macro there glues names together with ##, which
	 * makes names that are declared nowhere.
	 */
	public boolean whole(ProjectPath path, Knowledge known) {
		if (!entries.containsKey(path)) {
			return false;
		}
		Set<ProjectPath> includes = includes(path);
		for (Symbol link : known.links()) {
			if (link.kind() != Knowledge.Kind.INCLUDE) {
				continue;
			}
			ProjectParseResult<ProjectPath> target =
					ProjectPath.resolve(path, link.name());
			if (!target.isSuccess() ||
					!includes.contains(target.value())) {
				return false;
			}
		}
		Boolean whole = wholes.get(path);
		if (whole == null) {
			whole = true;
			for (ProjectPath file : reach(path)) {
				Knowledge knowledge = of(file);
				whole &= knowledge != null && knowledge.complete();
				for (Symbol symbol : knowledge != null
						? knowledge.symbols()
						: Collections.<Symbol>emptyList()) {
					whole &= !Definitions.gluing(symbol);
				}
			}
			wholes.put(path, whole);
		}
		return whole;
	}

	// The files whose declarations path may see: those it includes,
	// then those around it.
	private Set<ProjectPath> reach(ProjectPath path) {
		Set<ProjectPath> reach = new LinkedHashSet<>(includes(path));
		reach.addAll(around(path));
		return reach;
	}

	// The files that include path, and what they include, but path.
	private Set<ProjectPath> around(ProjectPath path) {
		Set<ProjectPath> around = new LinkedHashSet<>();
		for (ProjectPath includer : includers(path)) {
			around.add(includer);
			around.addAll(includes(includer));
		}
		around.remove(path);
		return around;
	}

	private List<Symbol> all(Set<ProjectPath> files, CharSequence word) {
		List<Symbol> found = new ArrayList<>();
		for (ProjectPath file : files) {
			Knowledge knowledge = of(file);
			if (knowledge == null) {
				continue;
			}
			for (Symbol symbol : knowledge.symbols()) {
				if (symbol.name().contentEquals(word)) {
					found.add(symbol.in(file.toString()));
				}
			}
		}
		return found;
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
		if (entry == null) {
			return -1;
		}
		// Where each line starts, read once: this is asked on every
		// caret move that rests in a name of another file.
		int[] starts = lineStarts.get(path.value());
		if (starts == null) {
			String text = SourceText.decode(entry.bytes);
			starts = text == null ? new int[0] : starts(text);
			lineStarts.put(path.value(), starts);
		}
		if (starts.length == 0) {
			return -1;
		}
		int line = Arrays.binarySearch(starts, symbol.start());
		return line < 0 ? -line - 2 : line;
	}

	private static int[] starts(String text) {
		int count = 1;
		for (int i = 0, length = text.length(); i < length; ++i) {
			if (text.charAt(i) == '\n') {
				++count;
			}
		}
		int[] starts = new int[count];
		for (int i = 0, n = 1, length = text.length(); i < length; ++i) {
			if (text.charAt(i) == '\n') {
				starts[n++] = i + 1;
			}
		}
		return starts;
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
