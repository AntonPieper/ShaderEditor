package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

// Where a word is written in the texts of a folder: as a whole name in code,
// and, apart from those, in comments. Nothing here knows what a name means: a
// name the compiler sees is written somewhere, so the code places are all of
// them, together with every other thing of the same name. Only a name glued
// together with ## cannot be found. It reads immutable texts, on any thread.
public final class WordPlaces {
	// line and column count from 0; code is that line's text.
	public record Place(String file, int start, int line, int column,
			String code, boolean comment) {
	}

	// The function whose body holds a local, in the shown file.
	public record Function(String name, int from, int to) {
		boolean holds(Place place, String shown) {
			return place.file().equals(shown) && place.start() >= from &&
					place.start() < to;
		}
	}

	// name is the file of a FILE group, the function of a FUNCTION group.
	// OTHER holds the files not connected to the shown one.
	public record Group(Kind kind, String name, List<Place> places) {
		public enum Kind {
			FUNCTION, ELSEWHERE, FILE, OTHER, COMMENTS;

			// Whether a rename changes its places unless told otherwise.
			public boolean ticked() {
				return this == FUNCTION || this == FILE;
			}
		}
	}

	private WordPlaces() {
	}

	// In the order of the text.
	public static List<Place> in(String file, Text text, String word) {
		List<Place> places = new ArrayList<>();
		String s = text.toString();
		if (word.isEmpty() || !s.contains(word)) {
			return places;
		}
		Tokens tokens = GlslLexer.lex(s);
		int length = word.length();
		for (int i = 0, n = tokens.size(); i < n; ++i) {
			int start = tokens.start(i);
			int end = tokens.end(i);
			Style style = tokens.style(i);
			if (style == Style.COMMENT) {
				for (int at = s.indexOf(word, start); at > -1 &&
						at + length <= end; at = s.indexOf(word, at + 1)) {
					if (isWhole(s, at, at + length)) {
						places.add(place(file, text, at, true));
					}
				}
			} else if (style != Style.STRING && end - start == length &&
					s.startsWith(word, start)) {
				places.add(place(file, text, start, false));
			}
		}
		return places;
	}

	// Whether the code of text glues names together with ##, which
	// leaves a name that is written nowhere.
	public static boolean glues(Text text) {
		String s = text.toString();
		int at = s.indexOf("##");
		if (at < 0) {
			return false;
		}
		Tokens tokens = GlslLexer.lex(s);
		for (; at > -1; at = s.indexOf("##", at + 2)) {
			int i = tokens.indexAt(at);
			if (i < 0 || (tokens.style(i) != Style.COMMENT &&
					tokens.style(i) != Style.STRING)) {
				return true;
			}
		}
		return false;
	}

	// The function of the local or parameter word means at offset of the
	// text known was made of, or null for anything else.
	public static Function functionOf(Knowledge known, int offset,
			String word) {
		List<Knowledge.Symbol> here = known.at(offset, word);
		if (here.size() != 1 || here.equals(known.at(-1, word))) {
			return null;
		}
		int at = here.get(0).start();
		for (Knowledge.Symbol symbol : known.symbols()) {
			if (symbol.kind() == Knowledge.Kind.FUNCTION &&
					symbol.from() <= at && at < symbol.to()) {
				return new Function(symbol.name(), symbol.from(),
						symbol.to());
			}
		}
		return null;
	}

	// For a local, the places in its function and those elsewhere; for any
	// other name, the places by file, that of the declaration first, then the
	// shown one, then the others by path, files not in connected (null for
	// all) together apart. Places in comments come last, apart.
	public static List<Group> group(List<Place> places, String shown,
			String declared, Function function, Set<String> connected) {
		List<Place> sorted = new ArrayList<>(places);
		Collections.sort(sorted, order(shown, declared));
		List<Place> inFunction = new ArrayList<>();
		List<Place> elsewhere = new ArrayList<>();
		List<Place> other = new ArrayList<>();
		List<Place> comments = new ArrayList<>();
		Map<String, List<Place>> files = new LinkedHashMap<>();
		for (Place place : sorted) {
			if (place.comment()) {
				comments.add(place);
			} else if (function == null && !inScope(place, shown, null,
					connected)) {
				other.add(place);
			} else if (function == null) {
				List<Place> inFile = files.get(place.file());
				if (inFile == null) {
					inFile = new ArrayList<>();
					files.put(place.file(), inFile);
				}
				inFile.add(place);
			} else if (function.holds(place, shown)) {
				inFunction.add(place);
			} else {
				elsewhere.add(place);
			}
		}
		List<Group> groups = new ArrayList<>();
		if (function != null) {
			add(groups, Group.Kind.FUNCTION, function.name(), inFunction);
			add(groups, Group.Kind.ELSEWHERE, null, elsewhere);
		}
		for (Map.Entry<String, List<Place>> file : files.entrySet()) {
			add(groups, Group.Kind.FILE, file.getKey(), file.getValue());
		}
		add(groups, Group.Kind.OTHER, null, other);
		add(groups, Group.Kind.COMMENTS, null, comments);
		return groups;
	}

	// The files connected to start through the files each names, in
	// either direction: what it includes, what includes it, and so on.
	public static Set<String> connected(Map<String, List<String>> names,
			String start) {
		Map<String, List<String>> edges = new HashMap<>();
		for (Map.Entry<String, List<String>> file : names.entrySet()) {
			for (String named : file.getValue()) {
				edge(edges, file.getKey(), named);
				edge(edges, named, file.getKey());
			}
		}
		Set<String> found = new HashSet<>();
		List<String> next = new ArrayList<>();
		next.add(start);
		while (!next.isEmpty()) {
			String file = next.remove(next.size() - 1);
			if (found.add(file) && edges.containsKey(file)) {
				next.addAll(edges.get(file));
			}
		}
		return found;
	}

	private static void edge(Map<String, List<String>> edges, String from,
			String to) {
		List<String> list = edges.get(from);
		if (list == null) {
			list = new ArrayList<>();
			edges.put(from, list);
		}
		list.add(to);
	}

	// The first code place of a name in the scope a rename stays in: the
	// function of a local, else the connected files (null for all), the
	// shown file first; null for none.
	public static Place firstInScope(List<Place> places, String shown,
			Function function, Set<String> connected) {
		Place first = null;
		Comparator<Place> order = order(shown, null);
		for (Place place : places) {
			if (!place.comment() &&
					inScope(place, shown, function, connected) &&
					(first == null || order.compare(place, first) < 0)) {
				first = place;
			}
		}
		return first;
	}

	private static boolean inScope(Place place, String shown,
			Function function, Set<String> connected) {
		return function != null
				? function.holds(place, shown)
				: connected == null || connected.contains(place.file());
	}

	private static Comparator<Place> order(String shown, String declared) {
		return (a, b) -> {
			int d = rank(a.file(), shown, declared) -
					rank(b.file(), shown, declared);
			if (d == 0) {
				d = a.file().compareTo(b.file());
			}
			return d != 0 ? d : Integer.compare(a.start(), b.start());
		};
	}

	private static void add(List<Group> groups, Group.Kind kind, String name,
			List<Place> places) {
		if (!places.isEmpty()) {
			groups.add(new Group(kind, name, places));
		}
	}

	private static int rank(String file, String shown, String declared) {
		return Objects.equals(file, declared) ? 0 : file.equals(shown) ? 1
				: 2;
	}

	private static Place place(String file, Text text, int start,
			boolean comment) {
		int line = text.lineOf(start);
		return new Place(file, start, line, start - text.lineStart(line),
				text.line(line), comment);
	}

	private static boolean isWhole(String s, int from, int to) {
		return (from == 0 || !isWordChar(s.charAt(from - 1))) &&
				(to == s.length() || !isWordChar(s.charAt(to)));
	}

	private static boolean isWordChar(char c) {
		return c == '_' || Character.isLetterOrDigit(c);
	}
}
