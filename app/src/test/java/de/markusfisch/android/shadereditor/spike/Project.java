package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Text;

/**
 * SPIKE, throwaway: what knows across files. For every file of a
 * shader it keeps the knowledge of the text it last saw; a file is
 * read with the declarations of the files it includes in front of it.
 * A definition is looked up in the file itself first, then by name in
 * the other files; usages are asked of each file in turn.
 */
final class Project {
	final Map<String, String> sources;
	final Map<String, GlslKnowledge> knowledge = new LinkedHashMap<>();

	Project(Map<String, String> sources) {
		this.sources = sources;
	}

	/** Read every file, each after the files it includes. */
	void index() {
		for (String path : sources.keySet()) {
			read(path, 0);
		}
	}

	private GlslKnowledge read(String path, int depth) {
		GlslKnowledge known = knowledge.get(path);
		if (known != null || depth > 16) {
			return known;
		}
		String source = sources.get(path);
		if (source == null) {
			return null;
		}
		Text text = Text.of(source);
		// The includes, from the directives alone.
		TokenStream t = TokenStream.of(text);
		List<Knowledge.Symbol> outside = new ArrayList<>();
		for (int d = 0; d < t.directives; ++d) {
			if (t.directiveKinds[d] != TokenStream.D_INCLUDE) {
				continue;
			}
			int quote = source.indexOf('"', t.directiveStarts[d]);
			int close = quote < 0 ? -1 : source.indexOf('"', quote + 1);
			if (close < 0 || close > t.directiveEnds[d]) {
				continue;
			}
			String included = resolve(path, source.substring(quote + 1,
					close));
			GlslKnowledge other = read(included, depth + 1);
			if (other != null) {
				exports(other, included, outside);
			}
		}
		known = GlslKnowledge.of(text, () -> outside);
		knowledge.put(path, known);
		return known;
	}

	// What a file gives the files that include it: its own
	// declarations and those it got from its own includes.
	private static void exports(GlslKnowledge other, String path,
			List<Knowledge.Symbol> out) {
		for (Knowledge.Symbol s : other.symbols()) {
			out.add(new Knowledge.Symbol(s.name(), s.kind(), s.detail(),
					s.documentation(), path, s.start(), s.end(), s.from(),
					s.to()));
		}
	}

	static String resolve(String from, String path) {
		List<String> parts = new ArrayList<>();
		int slash = from.lastIndexOf('/');
		if (slash >= 0) {
			for (String p : from.substring(0, slash).split("/")) {
				parts.add(p);
			}
		}
		for (String p : path.split("/")) {
			if (p.equals("..")) {
				if (!parts.isEmpty()) {
					parts.remove(parts.size() - 1);
				}
			} else if (!p.equals(".") && !p.isEmpty()) {
				parts.add(p);
			}
		}
		return String.join("/", parts);
	}

	/**
	 * Where the name at offset of path is declared: in the file, in a
	 * file it includes, or, for a name the file itself does not
	 * explain, in any other file of the shader.
	 */
	Knowledge.Symbol definition(String path, int offset) {
		GlslKnowledge known = knowledge.get(path);
		Knowledge.Symbol symbol = known.at(offset);
		if (symbol != null) {
			return symbol;
		}
		int i = known.t.tokenAt(offset + 1) - 1;
		if (i < 0 || known.t.ids[i] < 0) {
			return null;
		}
		String name = known.t.names.name(known.t.ids[i]);
		for (Map.Entry<String, GlslKnowledge> e : knowledge.entrySet()) {
			for (Knowledge.Symbol s : e.getValue().symbols()) {
				if (s.name().equals(name)) {
					return new Knowledge.Symbol(s.name(), s.kind(),
							s.detail(), s.documentation(), e.getKey(),
							s.start(), s.end(), s.from(), s.to());
				}
			}
		}
		return null;
	}

	/** Every place the files name symbol: path and pairs of offsets. */
	Map<String, int[]> usages(Knowledge.Symbol symbol, String declaredIn) {
		Map<String, int[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, GlslKnowledge> e : knowledge.entrySet()) {
			boolean home = e.getKey().equals(declaredIn);
			Knowledge.Symbol asked = home
					? new Knowledge.Symbol(symbol.name(), symbol.kind(),
							symbol.detail(), symbol.documentation(), null,
							symbol.start(), symbol.end(), symbol.from(),
							symbol.to())
					: new Knowledge.Symbol(symbol.name(), symbol.kind(),
							symbol.detail(), symbol.documentation(),
							declaredIn, symbol.start(), symbol.end(),
							symbol.from(), symbol.to());
			int[] places = e.getValue().occurrences(asked);
			if (places.length > 0) {
				out.put(e.getKey(), places);
			}
		}
		return out;
	}
}
