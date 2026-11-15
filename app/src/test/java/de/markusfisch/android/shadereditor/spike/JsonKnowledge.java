package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Text;

/**
 * SPIKE, throwaway: JSON behind the proposed seam, to show that a
 * language answers what it can and nothing else: its braces and
 * brackets fold, its keys are the outline, and for the manifest of a
 * project a key has a line of documentation and the entry is a file.
 */
final class JsonKnowledge implements Knowledge {
	private static final String[] MANIFEST = {
			"projectVersion", "The version of the project format.",
			"entry", "The file this project runs.",
	};

	private final List<Symbol> keys = new ArrayList<>();
	// Strings: start, end, and 1 if a ":" follows.
	private int[] strings = new int[48];
	private int stringCount;
	private int[] folds = new int[16];
	private int foldCount;
	private final String source;
	private final boolean manifest;

	private JsonKnowledge(String source, boolean manifest) {
		this.source = source;
		this.manifest = manifest;
	}

	static JsonKnowledge of(Text text, boolean manifest) {
		String s = text.toString();
		JsonKnowledge out = new JsonKnowledge(s, manifest);
		int[] stack = new int[16];
		int depth = 0;
		int line = 0;
		for (int i = 0, n = s.length(); i < n; ++i) {
			char c = s.charAt(i);
			if (c == '\n') {
				++line;
			} else if (c == '"') {
				int start = i;
				for (++i; i < n && s.charAt(i) != '"' &&
						s.charAt(i) != '\n'; ++i) {
					if (s.charAt(i) == '\\') {
						++i;
					}
				}
				int end = Math.min(n, i + 1);
				int k = end;
				while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
					++k;
				}
				boolean key = k < n && s.charAt(k) == ':';
				if (out.stringCount * 3 + 3 > out.strings.length) {
					out.strings = Arrays.copyOf(out.strings,
							out.strings.length * 2);
				}
				out.strings[out.stringCount * 3] = start;
				out.strings[out.stringCount * 3 + 1] = end;
				out.strings[out.stringCount * 3 + 2] = key ? 1 : 0;
				++out.stringCount;
				if (key && depth == 1) {
					String name = s.substring(start + 1, end - 1);
					out.keys.add(new Symbol(name, Kind.KEY, name,
							out.documentation(name), null, start + 1,
							end - 1, start, end));
				}
				if (i < n && s.charAt(i) == '\n') {
					++line;
				}
			} else if (c == '{' || c == '[') {
				if (depth == stack.length) {
					stack = Arrays.copyOf(stack, depth * 2);
				}
				stack[depth++] = line;
			} else if ((c == '}' || c == ']') && depth > 0) {
				int from = stack[--depth];
				if (line > from) {
					if (out.foldCount + 2 > out.folds.length) {
						out.folds = Arrays.copyOf(out.folds,
								out.folds.length * 2);
					}
					out.folds[out.foldCount++] = from;
					out.folds[out.foldCount++] = line;
				}
			}
		}
		return out;
	}

	private String documentation(String key) {
		for (int i = 0; manifest && i < MANIFEST.length; i += 2) {
			if (MANIFEST[i].equals(key)) {
				return MANIFEST[i + 1];
			}
		}
		return null;
	}

	@Override
	public List<Symbol> symbols() {
		return keys;
	}

	@Override
	public Symbol at(int offset) {
		for (int i = 0; i < stringCount; ++i) {
			int start = strings[i * 3];
			int end = strings[i * 3 + 1];
			if (start <= offset && offset < end) {
				String text = source.substring(start + 1,
						Math.max(start + 1, end - 1));
				if (strings[i * 3 + 2] == 1) {
					return new Symbol(text, Kind.KEY, text,
							documentation(text), null, start + 1, end - 1,
							start, end);
				}
				// The value of "entry" names a file.
				boolean entry = manifest && i > 0 &&
						strings[i * 3 - 1] == 1 && source.substring(
						strings[i * 3 - 3] + 1, strings[i * 3 - 2] - 1)
						.equals("entry");
				return entry ? new Symbol(text, Kind.FILE, text, null, text,
						-1, -1, 0, 0) : null;
			}
		}
		return null;
	}

	@Override
	public Call callAt(int offset) {
		return null;
	}

	@Override
	public int[] occurrences(Symbol symbol) {
		return new int[0];
	}

	@Override
	public int[] folds() {
		return Arrays.copyOf(folds, foldCount);
	}
}
