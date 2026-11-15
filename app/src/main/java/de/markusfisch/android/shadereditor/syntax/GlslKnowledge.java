package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the editor knows about one version of a GLSL text: its top-level
 * declarations as {@link GlslDeclarations} read them, the files it
 * names and what folds. Immutable.
 */
public final class GlslKnowledge implements Knowledge {
	/** The knowledge of the empty text. */
	public static final GlslKnowledge NONE = new GlslKnowledge(
			Collections.emptyList(), Collections.emptyList(), new int[0]);

	private final List<Symbol> symbols;
	private final List<Symbol> links;
	private final int[] folds;
	// The symbols by name, those of one name in the order of the text.
	private final Symbol[] byName;
	// Each name once, sorted, and how many symbols have it.
	final String[] names;
	final int[] counts;
	// For each symbol the last one up to it that is no macro, or -1.
	private final int[] outer;

	GlslKnowledge(List<Symbol> symbols, List<Symbol> links, int[] folds) {
		// Its own copies: whoever made it may go on with theirs.
		this.symbols = Collections.unmodifiableList(
				new ArrayList<>(symbols));
		this.links = Collections.unmodifiableList(new ArrayList<>(links));
		this.folds = folds.clone();
		byName = symbols.toArray(new Symbol[0]);
		outer = new int[byName.length];
		for (int i = 0, last = -1; i < outer.length; ++i) {
			if (byName[i].kind() != Kind.MACRO) {
				last = i;
			}
			outer[i] = last;
		}
		// The sort is stable, so one name keeps the order of the text.
		Arrays.sort(byName, (a, b) -> a.name().compareTo(b.name()));
		String[] names = new String[byName.length];
		int[] counts = new int[byName.length];
		int size = 0;
		for (Symbol symbol : byName) {
			if (size > 0 && names[size - 1].equals(symbol.name())) {
				++counts[size - 1];
			} else {
				names[size] = symbol.name();
				counts[size++] = 1;
			}
		}
		this.names = Arrays.copyOf(names, size);
		this.counts = Arrays.copyOf(counts, size);
	}

	/**
	 * Return a builder of the knowledge of a text, see
	 * {@link Syntax#knowledge}.
	 */
	public static Knowledge.Builder builder(int maxSymbols, int maxChars) {
		return new GlslDeclarations(maxSymbols, maxChars);
	}

	/**
	 * Return the knowledge of a whole text, whose lines end in "\n".
	 * It reads every line, so not on the main thread for a long text.
	 */
	public static GlslKnowledge of(CharSequence text) {
		GlslDeclarations builder = new GlslDeclarations(Integer.MAX_VALUE,
				Integer.MAX_VALUE);
		LineTokens tokens = new LineTokens();
		int state = GlslLexer.TEXT_START;
		for (int start = 0, length = text.length(); start <= length; ) {
			int end = start;
			while (end < length && text.charAt(end) != '\n') {
				++end;
			}
			CharSequence line = text.subSequence(start, end);
			state = GlslLexer.lexLine(line, end < length, state, tokens);
			builder.addLine(line, start, tokens);
			start = end + 1;
		}
		return builder.build();
	}

	/**
	 * Return the knowledge of a whole text from the tokens
	 * {@link GlslLexer#lex} gave for it, without tokenizing it again.
	 */
	public static GlslKnowledge of(Tokens tokens, CharSequence text) {
		GlslDeclarations builder = new GlslDeclarations(Integer.MAX_VALUE,
				Integer.MAX_VALUE);
		LineTokens line = new LineTokens();
		int size = tokens.size();
		int length = text.length();
		for (int n = 0, lines = tokens.lineCount(), i = 0; n < lines; ++n) {
			int start = tokens.lineStart(n);
			int end = n + 1 < lines ? tokens.lineStart(n + 1) : length;
			// Without its line end.
			while (end > start && (text.charAt(end - 1) == '\n' ||
					text.charAt(end - 1) == '\r')) {
				--end;
			}
			line.clear();
			// Only a comment reaches over a line end: the line has its
			// piece of it.
			boolean open = false;
			for (int k = i; k < size && tokens.start(k) < end; ++k) {
				int from = Math.max(start, tokens.start(k));
				int to = Math.min(end, tokens.end(k));
				if (to > from) {
					line.add(from - start, to - start, tokens.style(k),
							tokens.inDirective(k));
				}
				open = tokens.end(k) > end;
				if (!open) {
					i = k + 1;
				}
			}
			// A directive goes on if the next line starts inside it.
			int next = open ? i + 1 : i;
			line.goOn(open, n + 1 < lines && next < size &&
					tokens.inDirective(next) && !(tokens.style(next) ==
					Style.DIRECTIVE && text.charAt(tokens.start(next)) ==
					'#') && tokens.start(next) < (n + 2 < lines
					? tokens.lineStart(n + 2) : length + 1));
			builder.addLine(text.subSequence(start, end), start, line);
		}
		return builder.build();
	}

	@Override
	public List<Symbol> symbols() {
		return symbols;
	}

	/**
	 * Return what word refers to at offset: a parameter of the function
	 * or macro around offset, else what is declared with that name. A
	 * local variable is not known yet, so a word that names one answers
	 * with the declaration of that name outside the function, if any.
	 */
	@Override
	public List<Symbol> at(int offset, CharSequence word) {
		Symbol around = around(offset);
		if (around != null) {
			for (Symbol part : around.parts()) {
				if (part.kind() == Kind.PARAMETER &&
						part.name().contentEquals(word)) {
					return Collections.singletonList(part);
				}
			}
		}
		int i = first(word);
		if (i < 0) {
			return Collections.emptyList();
		} else if (byName[i].kind() != Kind.FUNCTION) {
			return Collections.singletonList(byName[i]);
		}
		List<Symbol> forms = new ArrayList<>();
		for (; i < byName.length && byName[i].name().contentEquals(word);
				++i) {
			if (byName[i].kind() == Kind.FUNCTION) {
				forms.add(byName[i]);
			}
		}
		return forms;
	}

	@Override
	public List<Symbol> links() {
		return links;
	}

	@Override
	public int[] folds() {
		return folds.clone();
	}

	// Returns the function or macro whose range holds offset, or null.
	// Symbols are in the order they start, and only a macro may stand
	// inside a function.
	private Symbol around(int offset) {
		int low = 0;
		int high = symbols.size() - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (symbols.get(mid).from() <= offset) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		if (high < 0) {
			return null;
		} else if (offset < symbols.get(high).to()) {
			return symbols.get(high);
		}
		// Macros do not overlap: behind the last one that starts before
		// offset, only what they stand in can hold it.
		int i = outer[high];
		return i >= 0 && offset < symbols.get(i).to() ? symbols.get(i)
				: null;
	}

	// Returns the index of the first symbol named word, or -1.
	private int first(CharSequence word) {
		int low = 0;
		int high = byName.length - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (compare(byName[mid].name(), word) < 0) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return low < byName.length && byName[low].name().contentEquals(word)
				? low : -1;
	}

	private static int compare(String name, CharSequence word) {
		int length = Math.min(name.length(), word.length());
		for (int i = 0; i < length; ++i) {
			int d = name.charAt(i) - word.charAt(i);
			if (d != 0) {
				return d;
			}
		}
		return name.length() - word.length();
	}
}
