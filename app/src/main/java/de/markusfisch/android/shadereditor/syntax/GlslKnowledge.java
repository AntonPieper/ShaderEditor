package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	private final GlslLocals locals;
	private final boolean complete;

	GlslKnowledge(List<Symbol> symbols, List<Symbol> links, int[] folds) {
		this(symbols, links, folds, GlslLocals.NONE, true);
	}

	GlslKnowledge(List<Symbol> symbols, List<Symbol> links, int[] folds,
			GlslLocals locals, boolean complete) {
		this.locals = locals;
		this.complete = complete;
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
		return new GlslDeclarations(maxSymbols, maxChars, maxSymbols);
	}

	/**
	 * Return the knowledge of a whole text, whose lines end in "\n".
	 * It reads every line, so not on the main thread for a long text.
	 */
	public static GlslKnowledge of(CharSequence text) {
		GlslDeclarations builder = new GlslDeclarations(Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE);
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
		// Whoever has the tokens of the whole text asks for names and
		// types of declarations, not for locals: bodies are passed over.
		GlslDeclarations builder = new GlslDeclarations(Integer.MAX_VALUE,
				Integer.MAX_VALUE, 0);
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

	/** Return whether a declared function has name. */
	public boolean isFunction(String name) {
		for (int i = first(name); i > -1 && i < byName.length &&
				byName[i].name().equals(name); ++i) {
			if (byName[i].kind() == Kind.FUNCTION) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the tokens with every identifier that names a declared type
	 * styled as {@link Style#TYPE}.
	 *
	 * @param tokens The tokens of the text this knowledge was made of.
	 * @param text   That text.
	 */
	public Tokens colourTypes(Tokens tokens, CharSequence text) {
		String[] types = new String[symbols.size()];
		int count = 0;
		for (Symbol symbol : symbols) {
			if (symbol.kind() == Kind.TYPE) {
				types[count++] = symbol.name();
			}
		}
		if (count == 0) {
			return tokens;
		}
		Arrays.sort(types, 0, count);
		int[] indices = new int[16];
		int retyped = 0;
		for (int i = 0, size = tokens.size(); i < size; ++i) {
			if (tokens.style(i) == Style.IDENTIFIER && GlslWords.contains(
					types, count, text, tokens.start(i), tokens.end(i))) {
				if (retyped == indices.length) {
					indices = Arrays.copyOf(indices, retyped * 2);
				}
				indices[retyped++] = i;
			}
		}
		return retyped == 0
				? tokens
				: tokens.withStyle(indices, retyped, Style.TYPE);
	}

	/**
	 * Return what word refers to at offset: a parameter of the function
	 * or macro around offset, else what is declared with that name. A
	 * local variable is not known yet, so a word that names one answers
	 * with the declaration of that name outside the function, if any.
	 */
	@Override
	public List<Symbol> at(int offset, CharSequence word) {
		int local = offset < 0 ? -1 : locals.find(word, offset);
		if (local >= 0) {
			return Collections.singletonList(locals.symbol(local));
		}
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
		}
		boolean function = byName[i].kind() == Kind.FUNCTION;
		if (offset >= 0 && i + 1 < byName.length &&
				byName[i + 1].name().contentEquals(word)) {
			// Declared more than once: the one whose name stands there,
			// else what is declared last before the place, as a macro
			// defined behind a variable of its name is, and a variable
			// declared behind the "#undef" of one. A function is
			// answered with every form the text has.
			for (int k = i; k < byName.length &&
					byName[k].name().contentEquals(word); ++k) {
				if (byName[k].kind() != Kind.FUNCTION &&
						offset >= byName[k].start() &&
						offset <= byName[k].end()) {
					return Collections.singletonList(byName[k]);
				}
			}
			List<Symbol> before = before(offset, word.toString());
			if (!before.isEmpty()) {
				function = before.get(0).kind() == Kind.FUNCTION;
				if (!function) {
					return before;
				}
			}
		}
		if (!function) {
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
	public boolean complete() {
		return complete;
	}

	/**
	 * Return what names mean at offset, for the type of an expression
	 * that stands there: the locals that can be seen, what the text
	 * declares, what outside declares for it, and the variables GLSL has
	 * itself.
	 */
	GlslExpression.Names names(int offset, Scope outside) {
		return new GlslExpression.Names() {
			// What outside was asked before: the other files are
			// asked once for a name, however often it is written.
			private Map<String, List<Symbol>> asked;

			// What the text says, and only if it says nothing what
			// outside declares: the other files are not asked for what
			// is known here.
			private List<Symbol> means(String name) {
				List<Symbol> here = at(offset, name);
				return here.isEmpty() ? outside(name) : here;
			}

			private List<Symbol> outside(String name) {
				if (asked == null) {
					asked = new HashMap<>();
				}
				List<Symbol> found = asked.get(name);
				if (found == null) {
					found = outside.find(name);
					asked.put(name, found);
				}
				return found;
			}

			// Whether a type is written with a word that is a macro: it
			// is then whatever the macro makes of it, which nobody
			// expands. A type that is not known is none.
			private boolean isMacro(String type) {
				if (type == null) {
					return false;
				}
				int end = type.indexOf('[');
				String word = end < 0 ? type : type.substring(0, end);
				boolean declared = false;
				for (int i = first(word); i > -1 && i < byName.length &&
						byName[i].name().equals(word); ++i) {
					if (byName[i].kind() == Kind.MACRO) {
						return true;
					}
					declared = true;
				}
				// The files around are asked only about a word that
				// neither the language nor the text knows.
				if (declared || GlslWords.style(word, 0, word.length()) !=
						Style.IDENTIFIER) {
					return false;
				}
				for (Symbol symbol : outside(word)) {
					if (symbol.kind() == Kind.MACRO) {
						return true;
					}
				}
				return false;
			}

			@Override
			public String variable(String name) {
				for (Symbol symbol : means(name)) {
					if (symbol.kind() != Kind.FUNCTION &&
							symbol.kind() != Kind.TYPE &&
							symbol.kind() != Kind.MACRO) {
						return isMacro(symbol.type()) ? null
								: symbol.type();
					}
				}
				return builtinVariable(name);
			}

			@Override
			public List<Symbol> functions(String name) {
				List<Symbol> found = means(name);
				if (found.isEmpty() || found.get(0).kind() !=
						Kind.FUNCTION) {
					return Collections.emptyList();
				}
				// A form with a type a macro writes is no form to
				// choose by: the name is then not known as a function.
				for (int i = 0, n = found.size(); i < n; ++i) {
					Symbol form = found.get(i);
					boolean macro = isMacro(form.type());
					for (Symbol part : form.parts()) {
						macro |= isMacro(part.type());
					}
					if (macro) {
						return Collections.emptyList();
					}
				}
				return found;
			}

			@Override
			public Symbol struct(String name) {
				for (int i = first(name); i > -1 && i < byName.length &&
						byName[i].name().equals(name); ++i) {
					if (byName[i].kind() == Kind.TYPE) {
						return byName[i];
					}
				}
				for (Symbol symbol : outside(name)) {
					if (symbol.kind() == Kind.TYPE) {
						return symbol;
					}
				}
				return null;
			}
		};
	}

	static String builtinVariable(String name) {
		switch (name) {
			case "gl_FragCoord":
			case "gl_FragColor":
				return "vec4";
			case "gl_PointCoord":
				return "vec2";
			case "gl_FragDepth":
				return "float";
			case "gl_FrontFacing":
				return "bool";
			default:
				return null;
		}
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

	/**
	 * Return the local or parameter name means at offset, or null: no
	 * guess of the function around a place, only what the scopes say.
	 */
	Symbol local(int offset, CharSequence name) {
		int local = locals.find(name, offset);
		return local < 0 ? null : locals.symbol(local);
	}

	/**
	 * Return what name means outside of functions for code at offset,
	 * as GLSL binds it: of what is declared before the place, the
	 * functions of the name if the last one is a function, else that
	 * last one alone; nothing if none is declared before it.
	 */
	List<Symbol> before(int offset, String name) {
		List<Symbol> found = new ArrayList<>();
		for (int i = first(name); i > -1 && i < byName.length &&
				byName[i].name().equals(name); ++i) {
			Symbol symbol = byName[i];
			// A function is seen from its name on, in its own body
			// too; anything else behind its declarator.
			if (symbol.kind() == Kind.FUNCTION ? symbol.start() < offset
					: symbol.to() <= offset) {
				if (symbol.kind() != Kind.FUNCTION || (!found.isEmpty() &&
						found.get(0).kind() != Kind.FUNCTION)) {
					found.clear();
				}
				found.add(symbol);
			}
		}
		return found;
	}

	/**
	 * Return the declaration outside of functions whose range holds
	 * offset, or null: of those that start together the first, a
	 * struct before the variables declared with it.
	 */
	Symbol holder(int offset) {
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
		}
		Symbol found = null;
		for (int i = high, from = symbols.get(high).from(); i >= 0 &&
				symbols.get(i).from() == from; --i) {
			if (offset < symbols.get(i).to()) {
				found = symbols.get(i);
			}
		}
		int i = outer[high];
		return found != null ? found : i >= 0 &&
				offset < symbols.get(i).to() ? symbols.get(i) : null;
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
