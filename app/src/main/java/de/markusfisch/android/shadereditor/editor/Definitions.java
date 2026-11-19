package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * Where a name may be declared, as the lexer alone tells: every place
 * in reach where the word is written where a declaration names it, and
 * every macro that could make one. Complete by construction, as the
 * words of the Uses list are, it knows no scope and expands no macro:
 * it jumps only where there is one place and nothing else could be.
 * Pure, to be run off the main thread.
 */
public final class Definitions {
	/** What a place is. */
	public enum Kind {
		/** Written where a declaration names it. */
		DECLARED,
		/** The name of a #define. */
		DEFINED,
		/** In the body of a #define: it may become a declaration. */
		MENTIONED,
		/** Where a macro may make it a declaration. */
		MAYBE
	}

	/**
	 * A place of a word, the offset of its start in its file's text;
	 * for a function, the types of its parameters, and whether it is a
	 * prototype.
	 */
	public record Place(String file, int start, Kind kind,
			List<String> types, boolean prototype) {
	}

	/** The answer, with the places nearest first. */
	public enum Form {
		/** The one place. */
		JUMP,
		/** Several places, or one that may not be all. */
		LIST,
		/** Written nowhere else in reach. */
		NONE,
		/** Nothing found, but it cannot be told there is none. */
		UNKNOWN
	}

	public record Answer(Form form, List<Place> places) {
		public static final Answer UNKNOWN = new Answer(Form.UNKNOWN,
				Collections.emptyList());
	}

	/** What the lexer tells of one text. */
	public static final class Index {
		private final Map<String, List<Place>> places = new HashMap<>();
		private final Map<String, Integer> written = new HashMap<>();
		private final Set<String> macros = new HashSet<>();
		// Macros whose body puts a parameter where a declaration names.
		private final Set<String> declaring = new HashSet<>();
		// Per #define: its parameters' offsets by name, its body's end.
		private final List<Object[]> parameters = new ArrayList<>();
		// Function bodies, "{" and "}" offsets in pairs.
		private final List<int[]> functions = new ArrayList<>();
		private final List<String> includes = new ArrayList<>();
		// The names of the macros that glue names, where they stand.
		private final List<Place> gluing = new ArrayList<>();
		private final String file;

		private Index(String file) {
			this.file = file;
		}

		/** Return the paths its #include lines name, as written. */
		public List<String> includes() {
			return includes;
		}

		private void add(String word, int start, Kind kind) {
			add(word, new Place(file, start, kind, null, false));
		}

		private void add(String word, Place place) {
			List<Place> list = places.get(word);
			if (list == null) {
				list = new ArrayList<>();
				places.put(word, list);
			}
			list.add(place);
		}

		private int function(int offset) {
			for (int[] f : functions) {
				if (f[0] < offset && offset < f[1]) {
					return f[0];
				}
			}
			return -1;
		}
	}

	private Definitions() {
	}

	/** Read text, the text of file. */
	public static Index index(CharSequence text, String file) {
		String s = text.toString();
		Tokens all = GlslLexer.lex(s);
		Index index = new Index(file);
		// The code's tokens, comments left out; directives apart.
		List<int[]> code = new ArrayList<>();
		List<int[]> directive = new ArrayList<>();
		for (int i = 0, n = all.size(); i < n; ++i) {
			Style style = all.style(i);
			if (style == Style.COMMENT) {
				continue;
			}
			int[] t = {all.start(i), all.end(i), style.ordinal()};
			if (!all.inDirective(i)) {
				code.add(t);
			} else {
				if (style == Style.DIRECTIVE && is(s, t, "#") &&
						!directive.isEmpty()) {
					directive(s, directive, index);
					directive.clear();
				}
				directive.add(t);
			}
			if (word(t)) {
				String w = s.substring(t[0], t[1]);
				Integer n0 = index.written.get(w);
				index.written.put(w, n0 == null ? 1 : n0 + 1);
			}
		}
		if (!directive.isEmpty()) {
			directive(s, directive, index);
		}
		code(s, code, index);
		return index;
	}

	private static void directive(String s, List<int[]> d, Index index) {
		if (d.size() > 2 && is(s, d.get(1), "include") &&
				d.get(2)[2] == Style.STRING.ordinal()) {
			index.includes.add(s.substring(d.get(2)[0] + 1,
					Math.max(d.get(2)[0] + 1, d.get(2)[1] - 1)));
			return;
		}
		if (d.size() < 3 || !is(s, d.get(1), "define") || !word(d.get(2))) {
			return;
		}
		int[] name = d.get(2);
		String macro = s.substring(name[0], name[1]);
		index.macros.add(macro);
		index.add(macro, name[0], Kind.DEFINED);
		Map<String, Integer> params = new HashMap<>();
		int i = 3;
		if (i < d.size() && is(s, d.get(i), "(") && d.get(i)[0] == name[1]) {
			for (++i; i < d.size() && !is(s, d.get(i), ")"); ++i) {
				if (word(d.get(i))) {
					params.put(s.substring(d.get(i)[0], d.get(i)[1]),
							d.get(i)[0]);
				}
			}
			++i;
		}
		int end = d.get(d.size() - 1)[1];
		index.parameters.add(new Object[]{params, end});
		for (int k = i; k < d.size(); ++k) {
			int[] t = d.get(k);
			int[] before = k > i ? d.get(k - 1) : null;
			if (!word(t)) {
				continue;
			}
			String w = s.substring(t[0], t[1]);
			if (!params.containsKey(w)) {
				index.add(w, t[0], Kind.MENTIONED);
			} else if (before != null && (names(before) ||
					is(s, before, "]") || is(s, before, ","))) {
				index.declaring.add(macro);
			}
		}
		if (gluing(s, d, i)) {
			index.declaring.add(macro);
			index.gluing.add(new Place(index.file, name[0], Kind.MENTIONED,
					null, false));
		}
	}

	// Whether the body of a #define, from token from of d, holds ##.
	private static boolean gluing(String s, List<int[]> d, int from) {
		for (int k = from + 1; k < d.size(); ++k) {
			if (is(s, d.get(k), "#") && is(s, d.get(k - 1), "#") &&
					d.get(k - 1)[1] == d.get(k)[0]) {
				return true;
			}
		}
		return false;
	}

	private static void code(String s, List<int[]> code, Index index) {
		int n = code.size();
		int[] match = new int[n];
		List<Integer> open = new ArrayList<>();
		// Whether the brace is a struct's, whose names are fields.
		boolean[] struct = new boolean[n];
		List<Integer> braces = new ArrayList<>();
		for (int i = 0; i < n; ++i) {
			match[i] = -1;
			int[] t = code.get(i);
			if (is(s, t, "(") || is(s, t, "[") || is(s, t, "{")) {
				open.add(i);
				if (is(s, t, "{")) {
					struct[i] = (i > 0 && is(s, code.get(i - 1), "struct")) ||
							(i > 1 && is(s, code.get(i - 2), "struct"));
					if (braces.isEmpty() && i > 0 &&
							is(s, code.get(i - 1), ")")) {
						index.functions.add(new int[]{t[0], -1, i});
					}
					braces.add(i);
				}
			} else if ((is(s, t, ")") || is(s, t, "]") || is(s, t, "}")) &&
					!open.isEmpty()) {
				int o = open.remove(open.size() - 1);
				match[o] = i;
				match[i] = o;
				if (is(s, t, "}") && !braces.isEmpty()) {
					braces.remove(braces.size() - 1);
					List<int[]> f = index.functions;
					if (!f.isEmpty() && f.get(f.size() - 1)[2] == o) {
						f.get(f.size() - 1)[1] = t[0];
					}
				}
			}
		}
		// Words in a struct's braces, or a prototype's parentheses.
		boolean[] skip = new boolean[n];
		for (int i = 0; i < n; ++i) {
			int m = match[i];
			if (m > i && (struct[i] || (is(s, code.get(i), "(") &&
					m + 1 < n && is(s, code.get(m + 1), ";") && i > 1 &&
					names(code.get(i - 1)) && names(code.get(i - 2))))) {
				for (int k = i + 1; k < m; ++k) {
					skip[k] = true;
				}
			}
		}
		for (int i = 1; i < n; ++i) {
			int[] t = code.get(i);
			if (!word(t) || skip[i]) {
				continue;
			}
			int[] p = code.get(i - 1);
			if (is(s, p, ".")) {
				continue;
			}
			Kind kind = null;
			if (word(p) && index.macros.contains(s.substring(p[0], p[1]))) {
				kind = Kind.MAYBE;
			} else if (names(p)) {
				kind = Kind.DECLARED;
			} else if (is(s, p, "]") && match[i - 1] > 0 &&
					names(code.get(match[i - 1] - 1))) {
				kind = Kind.DECLARED;
			} else if (is(s, p, ")") && match[i - 1] > 0 && word(code.get(
					match[i - 1] - 1)) && index.macros.contains(s.substring(
					code.get(match[i - 1] - 1)[0],
					code.get(match[i - 1] - 1)[1]))) {
				kind = Kind.MAYBE;
			} else if (is(s, p, "}") && i + 1 < n && (is(s, code.get(i + 1),
					";") || is(s, code.get(i + 1), ",") ||
					is(s, code.get(i + 1), "["))) {
				kind = Kind.DECLARED;
			} else if (is(s, p, ",") && declarators(s, code, match, i - 1)) {
				kind = Kind.DECLARED;
			} else if (inDeclaringMacro(s, code, index, i)) {
				kind = Kind.MAYBE;
			}
			if (kind == Kind.DECLARED && i + 1 < n &&
					is(s, code.get(i + 1), "(") && match[i + 1] > i) {
				int close = match[i + 1];
				index.add(s.substring(t[0], t[1]), new Place(index.file, t[0],
						kind, types(s, code, i + 2, close), close + 1 < n &&
						is(s, code.get(close + 1), ";")));
			} else if (kind != null) {
				index.add(s.substring(t[0], t[1]), t[0], kind);
			}
		}
	}

	// The types of the parameters between the parentheses at from - 1
	// and to: of each, what stands before its name, qualifiers left out.
	private static List<String> types(String s, List<int[]> code, int from,
			int to) {
		List<String> types = new ArrayList<>();
		List<int[]> param = new ArrayList<>();
		for (int k = from; k <= to; ++k) {
			int[] t = code.get(k);
			if (k < to && !is(s, t, ",")) {
				if (t[2] != Style.TYPE_QUALIFIER.ordinal() &&
						t[2] != Style.PRECISION.ordinal()) {
					param.add(t);
				}
				continue;
			}
			int name = param.size() - 1;
			while (name > 0 && !word(param.get(name))) {
				--name;
			}
			int end = name > 0 ? name : param.size();
			StringBuilder type = new StringBuilder();
			for (int a = 0; a < end; ++a) {
				type.append(s, param.get(a)[0], param.get(a)[1]);
			}
			if (type.length() > 0 && !(k == to && types.isEmpty() &&
					type.toString().equals("void"))) {
				types.add(type.toString());
			}
			param.clear();
		}
		return types;
	}

	// Whether token i stands in the arguments of a macro that may
	// declare one of them.
	private static boolean inDeclaringMacro(String s, List<int[]> code,
			Index index, int i) {
		if (index.declaring.isEmpty()) {
			return false;
		}
		for (int k = i - 1, depth = 0; k >= 0; --k) {
			int[] q = code.get(k);
			if (is(s, q, ")") || is(s, q, "]")) {
				++depth;
			} else if (is(s, q, "(") || is(s, q, "[")) {
				if (depth > 0) {
					--depth;
				} else if (k > 0 && is(s, q, "(") && word(code.get(k - 1)) &&
						index.declaring.contains(s.substring(
								code.get(k - 1)[0], code.get(k - 1)[1]))) {
					return true;
				}
			} else if (depth == 0 && (is(s, q, ";") || is(s, q, "{") ||
					is(s, q, "}"))) {
				return false;
			}
		}
		return false;
	}

	// Whether the comma at c is in a declarator list: its statement, or
	// the header of a "for", begins "type name" or "type[...] name".
	private static boolean declarators(String s, List<int[]> code,
			int[] match, int c) {
		int k = c;
		for (; k >= 0; --k) {
			int[] q = code.get(k);
			if ((is(s, q, ")") || is(s, q, "]")) && match[k] >= 0) {
				k = match[k];
			} else if (is(s, q, ";") || is(s, q, "{") || is(s, q, "}") ||
					is(s, q, ":") || is(s, q, "(")) {
				break;
			}
		}
		if (k >= 0 && is(s, code.get(k), "(") &&
				!(k > 0 && is(s, code.get(k - 1), "for"))) {
			return false;
		}
		int a = k + 1;
		while (a < c && code.get(a)[2] == Style.TYPE_QUALIFIER.ordinal()) {
			++a;
		}
		return a + 1 < c && names(code.get(a)) && (word(code.get(a + 1)) ||
				is(s, code.get(a + 1), "["));
	}

	/** Resolves a path an #include names, from the file it is in. */
	public interface Paths {
		/** Return the file path names from file, or null. */
		String resolve(String file, String path);
	}

	/**
	 * Return the indexes of the texts in reach of shown, itself first:
	 * the files it includes, and what they include; then the files that
	 * include it, and theirs, and what those include. These are the
	 * texts a build may put around it; other files of the folder are
	 * not. Only a text that mentions "include", and the texts in reach,
	 * are read.
	 */
	public static List<Index> reach(Map<String, ? extends CharSequence> texts,
			String shown, Paths paths) {
		Map<String, Index> indexes = new HashMap<>();
		Map<String, List<String>> includes = new HashMap<>();
		for (Map.Entry<String, ? extends CharSequence> e : texts.entrySet()) {
			if (!e.getValue().toString().contains("include")) {
				continue;
			}
			Index index = index(e.getValue(), e.getKey());
			indexes.put(e.getKey(), index);
			List<String> named = new ArrayList<>();
			for (String path : index.includes) {
				String file = paths.resolve(e.getKey(), path);
				if (file != null) {
					named.add(file);
				}
			}
			includes.put(e.getKey(), named);
		}
		Set<String> reach = new java.util.LinkedHashSet<>();
		reach.add(shown);
		down(shown, includes, reach);
		List<String> up = new ArrayList<>();
		up.add(shown);
		for (int i = 0; i < up.size(); ++i) {
			for (Map.Entry<String, List<String>> e : includes.entrySet()) {
				if (e.getValue().contains(up.get(i)) &&
						!up.contains(e.getKey())) {
					up.add(e.getKey());
				}
			}
		}
		for (String includer : up) {
			reach.add(includer);
			down(includer, includes, reach);
		}
		List<Index> found = new ArrayList<>();
		for (String file : reach) {
			Index index = indexes.get(file);
			CharSequence text = texts.get(file);
			if (index == null && text != null) {
				index = index(text, file);
			}
			if (index != null) {
				found.add(index);
			}
		}
		return found;
	}

	private static void down(String file, Map<String, List<String>> includes,
			Set<String> reach) {
		List<String> named = includes.get(file);
		if (named == null) {
			return;
		}
		for (String include : named) {
			if (reach.add(include)) {
				down(include, includes, reach);
			}
		}
	}

	/**
	 * Return where word, which stands at offset of the file shown, may
	 * be declared, from the indexes of the files in reach, the file
	 * shown first: jump when there is one place and no macro mentions
	 * the word or glues names; no definition when the word is written
	 * nowhere else; else the places, nearest first.
	 */
	public static Answer of(List<Index> reach, int offset, String word) {
		Index shown = reach.get(0);
		// A parameter of the macro whose body the word stands in.
		for (Object[] p : shown.parameters) {
			@SuppressWarnings("unchecked")
			Map<String, Integer> params = (Map<String, Integer>) p[0];
			Integer at = params.get(word);
			if (at != null && at < offset && offset <= (Integer) p[1]) {
				return new Answer(Form.JUMP, List.of(new Place(shown.file,
						at, Kind.DEFINED, null, false)));
			}
		}
		List<Place> places = new ArrayList<>();
		boolean mentioned = false;
		boolean pastes = false;
		int written = 0;
		for (Index index : reach) {
			pastes |= !index.gluing.isEmpty();
			Integer n = index.written.get(word);
			written += n != null ? n : 0;
			List<Place> found = index.places.get(word);
			for (Place p : found != null ? found : List.<Place>of()) {
				mentioned |= p.kind() == Kind.MENTIONED;
				if (index != shown || p.start() != offset) {
					places.add(p);
				}
			}
		}
		// A prototype goes into the body it announces, in its file.
		for (int i = places.size(); i-- > 0; ) {
			Place p = places.get(i);
			if (!p.prototype()) {
				continue;
			}
			for (Place body : places) {
				if (!body.prototype() && body.types() != null &&
						body.file().equals(p.file()) &&
						body.types().equals(p.types())) {
					places.remove(i);
					break;
				}
			}
		}
		if (places.size() == 1 && !mentioned && !pastes) {
			return new Answer(Form.JUMP, places);
		} else if (places.isEmpty()) {
			// Written once: here, as a word; a word the lexer reads as
			// something else (a keyword of another version) is no word.
			return written == 1 && !pastes
					? new Answer(Form.NONE, places)
					: Answer.UNKNOWN;
		}
		// A macro that glues names may have made it: it is a place too.
		if (pastes) {
			for (Index index : reach) {
				places.addAll(index.gluing);
			}
		}
		int function = shown.function(offset);
		Collections.sort(places, (a, b) -> {
			int ra = rank(a, shown, function, reach);
			int rb = rank(b, shown, function, reach);
			if (ra != rb) {
				return Integer.compare(ra, rb);
			}
			boolean aAfter = a.start() > offset, bAfter = b.start() > offset;
			if (aAfter != bAfter) {
				return aAfter ? 1 : -1;
			}
			return Integer.compare(Math.abs(a.start() - offset),
					Math.abs(b.start() - offset));
		});
		return new Answer(Form.LIST, places);
	}

	// The same function first, then the file shown, then the others in
	// the order of reach.
	private static int rank(Place p, Index shown, int function,
			List<Index> files) {
		if (p.file().equals(shown.file)) {
			return function > -1 && shown.function(p.start()) == function
					? 0 : 1;
		}
		for (int i = 1; i < files.size(); ++i) {
			if (files.get(i).file.equals(p.file())) {
				return 1 + i;
			}
		}
		return Integer.MAX_VALUE;
	}

	private static boolean is(String s, int[] t, String text) {
		return t[1] - t[0] == text.length() && s.startsWith(text, t[0]);
	}

	// A name a user may write and look up.
	private static boolean word(int[] t) {
		return t[2] == Style.IDENTIFIER.ordinal() ||
				t[2] == Style.TYPE.ordinal() ||
				t[2] == Style.BUILTIN_FUNCTION.ordinal();
	}

	// Whether a name declared may follow t: a type, a name, a qualifier.
	private static boolean names(int[] t) {
		Style style = Style.values()[t[2]];
		return switch (style) {
			case IDENTIFIER, TYPE, PRIMITIVE, TYPE_VECTOR, TYPE_MATRIX,
					TYPE_SAMPLER, TYPE_IMAGE, ATOMIC_COUNTER, KEYWORD,
					TYPE_QUALIFIER, PRECISION -> true;
			default -> false;
		};
	}
}
