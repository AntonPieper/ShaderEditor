package de.markusfisch.android.shadereditor.syntax;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the built-in words of GLSL ES are, read from a text of one block
 * per word, blocks apart by an empty line. A block's first line is the
 * word, its kind and the versions that have it ("smoothstep function
 * 100 300"); each further line is a key and its text: form, detail,
 * text, note, example. Every block has one text; form and example may
 * repeat. Lines starting with # are comments. Immutable once read.
 */
public final class GlslDocs {
	public enum Kind {
		FUNCTION, TYPE, QUALIFIER, KEYWORD, VARIABLE, CONSTANT
	}

	/**
	 * The documentation of one word: its forms, what the forms leave to
	 * say, one sentence, a note and an example, each but the sentence
	 * possibly empty.
	 */
	public record Entry(@NonNull String name, @NonNull Kind kind,
			boolean es100, boolean es300, @NonNull List<String> forms,
			@Nullable String detail, @NonNull String text,
			@Nullable String note, @NonNull List<String> example) {
	}

	private final Map<String, Entry> entries;

	private GlslDocs(Map<String, Entry> entries) {
		this.entries = entries;
	}

	/**
	 * Read the blocks of reader; throw an IOException that names the
	 * line of the first block that does not read.
	 */
	@NonNull
	public static GlslDocs read(@NonNull Reader reader) throws IOException {
		BufferedReader lines = new BufferedReader(reader);
		Map<String, Entry> entries = new HashMap<>();
		Block block = null;
		int number = 0;
		for (String line; (line = lines.readLine()) != null; ) {
			++number;
			if (line.startsWith("#")) {
				continue;
			}
			if (line.trim().isEmpty()) {
				block = add(entries, block);
			} else if (block == null) {
				block = new Block(line, number);
			} else {
				block.add(line, number);
			}
		}
		add(entries, block);
		return new GlslDocs(entries);
	}

	/** Return the documentation of word, or null if it has none. */
	@Nullable
	public Entry lookup(@NonNull String word) {
		return entries.get(word);
	}

	/**
	 * Return the names of the parameters of form, a declaration such
	 * as "T step(T edge, T x)".
	 */
	@NonNull
	public static List<String> parameterNames(@NonNull String form) {
		List<String> names = new ArrayList<>();
		int open = form.indexOf('(');
		int close = form.lastIndexOf(')');
		if (open < 0 || close < open) {
			return names;
		}
		for (String parameter : form.substring(open + 1, close).split(",")) {
			String[] words = parameter.trim().split("\\s+");
			String name = words[words.length - 1];
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * Return the types of the parameters of a form, in their order: the
	 * word before each name, without what qualifies it, or the type
	 * alone where a parameter has no name.
	 */
	@NonNull
	public static List<String> parameterTypes(@NonNull String form) {
		List<String> types = new ArrayList<>();
		int open = form.indexOf('(');
		int close = form.lastIndexOf(')');
		if (open < 0 || close < open) {
			return types;
		}
		for (String parameter : form.substring(open + 1, close).split(",")) {
			String[] words = parameter.trim().split("\\s+");
			String type = words[words.length > 1 ? words.length - 2 : 0];
			if (!type.isEmpty()) {
				types.add(type);
			}
		}
		return types;
	}

	@NonNull
	public Collection<Entry> entries() {
		return Collections.unmodifiableCollection(entries.values());
	}

	private static Block add(Map<String, Entry> entries, Block block)
			throws IOException {
		if (block != null) {
			Entry entry = block.entry();
			if (entries.put(entry.name(), entry) != null) {
				throw block.error("Second block for " + entry.name());
			}
		}
		return null;
	}

	private static final class Block {
		private final String name;
		private final Kind kind;
		private final int line;
		private final List<String> forms = new ArrayList<>();
		private final List<String> example = new ArrayList<>();
		private boolean es100;
		private boolean es300;
		private String detail;
		private String text;
		private String note;

		private Block(String head, int line) throws IOException {
			this.line = line;
			String[] parts = head.trim().split(" +");
			if (parts.length < 3) {
				throw error("Expected word, kind and versions");
			}
			name = parts[0];
			try {
				kind = Kind.valueOf(parts[1].toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw error("Unknown kind " + parts[1]);
			}
			for (int i = 2; i < parts.length; ++i) {
				if ("100".equals(parts[i]) && !es100) {
					es100 = true;
				} else if ("300".equals(parts[i]) && !es300) {
					es300 = true;
				} else {
					throw error("Unknown version " + parts[i]);
				}
			}
		}

		private void add(String line, int number) throws IOException {
			int space = line.indexOf(' ');
			String key = space < 0 ? line : line.substring(0, space);
			String value = space < 0 ? "" : line.substring(space + 1).trim();
			if (value.isEmpty()) {
				throw new IOException("Line " + number + ": no text after " +
						key);
			}
			switch (key) {
				case "form" -> forms.add(value);
				case "example" -> example.add(line.substring(space + 1));
				case "detail" -> detail = once(detail, value, key, number);
				case "text" -> text = once(text, value, key, number);
				case "note" -> note = once(note, value, key, number);
				default -> throw new IOException("Line " + number +
						": unknown key " + key);
			}
		}

		private static String once(String old, String value, String key,
				int number) throws IOException {
			if (old != null) {
				throw new IOException("Line " + number + ": second " + key);
			}
			return value;
		}

		private Entry entry() throws IOException {
			if (text == null) {
				throw error("No text for " + name);
			}
			return new Entry(name, kind, es100, es300,
					Collections.unmodifiableList(forms), detail, text, note,
					Collections.unmodifiableList(example));
		}

		private IOException error(String message) {
			return new IOException("Line " + line + ": " + message);
		}
	}
}
