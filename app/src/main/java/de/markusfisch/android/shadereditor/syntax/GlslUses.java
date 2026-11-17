package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslPreprocessor.Conditional;
import de.markusfisch.android.shadereditor.syntax.GlslPreprocessor.Macro;
import de.markusfisch.android.shadereditor.syntax.GlslStatement.Token;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Answer;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Cause;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Kind;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Use;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Use.Why;

/**
 * Where a declaration is used in GLSL programs, made when it is asked
 * for; nothing of a use is kept by anyone.
 *
 * A place is certain only when a short list of conditions holds, each
 * from something that was executed; see "Uses, third shape" in
 * docs/project-mode/LANGUAGE.md. One reading of a program does this:
 * the text the driver gets is read in logical lines; the preprocessor
 * is executed ({@link GlslPreprocessor}); the lines that are compiled
 * are given to the reader of declarations, with the macros that are
 * one number replaced by their tokens, and their tokens are kept; and
 * then {@link Reading#kind} says of each word of the name what it is.
 * That is the one place a use becomes certain, and {@link #both} the
 * one place where two readings of a place are made one.
 *
 * Nothing is reasoned about what a macro may do. A macro that was not
 * executed makes everything behind it a place that is listed.
 */
final class GlslUses {
	// Where a word of the name stands that is in no compiled code.
	private static final int CODE = 4;
	private static final int NUMBER_MACRO = 5;
	private static final int PASTING = 6;
	// What a token is beside its kind.
	private static final int UNDECIDED = 1;
	private static final int MEMBER = 2;
	private static final int MENTION = 4;
	// The words the reader takes for words of the language that a
	// program of version 100 may use as names.
	private static final Set<String> NAMES_OF_100 = Set.of("uint", "uvec2",
			"uvec3", "uvec4", "mat2x2", "mat2x3", "mat2x4", "mat3x2",
			"mat3x3", "mat3x4", "mat4x2", "mat4x3", "mat4x4", "centroid",
			"smooth", "layout", "samplerCubeShadow", "sampler2DArray",
			"sampler2DArrayShadow", "isampler2D", "isampler3D",
			"isamplerCube", "isampler2DArray", "usampler2D", "usampler3D",
			"usamplerCube", "usampler2DArray", "case");

	private GlslUses() {
	}

	/** Return the uses of symbol, see {@link Syntax#uses}. */
	static Answer of(List<Syntax.Program> programs, Symbol symbol,
			String candidate) {
		if (symbol.name().isEmpty() || symbol.file() == null) {
			return null;
		}
		Map<String, Use> places = new LinkedHashMap<>();
		Map<String, Use> taken = new LinkedHashMap<>();
		Map<String, Cause> depends = new LinkedHashMap<>();
		int[][] bindings = new int[programs.size()][];
		boolean[][] proven = new boolean[programs.size()][];
		boolean declared = false;
		boolean written = false;
		for (int p = 0; p < programs.size(); ++p) {
			Reading reading = new Reading(programs.get(p), symbol, candidate);
			if (!reading.read()) {
				return null;
			}
			if (!reading.kinds(places, taken, depends)) {
				// Something else stands where the symbol says it is
				// declared: it is of another text.
				return null;
			}
			declared |= reading.declared;
			written |= reading.written;
			bindings[p] = reading.bindings;
			proven[p] = reading.proven;
		}
		if (!declared && !written) {
			// No program has the name there: the symbol is of another
			// text.
			return null;
		}
		// With the name there in lines that no program compiles, there
		// is an answer in which nothing is certain: the place of the
		// declaration is listed as not compiled, like every other.
		List<Use> uses = new ArrayList<>();
		for (Use use : places.values()) {
			if (use.why() != null) {
				uses.add(use);
			}
		}
		return new Answer(uses, new ArrayList<>(taken.values()), bindings,
				proven, new ArrayList<>(depends.values()));
	}

	/**
	 * Return what a place is to two readings of it, in a file that is
	 * read twice or in two programs. A reading in which it is not
	 * compiled says nothing; of two in which it is, it is certain if
	 * it is in both, listed if it is in one, and if it is a use in one
	 * and none in the other, listed for that. A use with a why of null
	 * is none; it has a cause if nothing of the name is declared there.
	 */
	static Use both(Use a, Use b) {
		if (a.why() == Why.INACTIVE || b.why() == Why.INACTIVE) {
			return a.why() == Why.INACTIVE ? b : a;
		} else if (a.why() == b.why()) {
			return a;
		} else if (a.why() == null || b.why() == null) {
			Use use = a.why() == null ? b : a;
			Use none = a.why() == null ? a : b;
			return !use.certain() ? use : use.because(
					none.causes().isEmpty() ? Why.REDECLARED
							: Why.NOT_INCLUDED, Collections.emptyList());
		}
		// Certain in both, as one thing here and another there: what
		// it is, is not one answer.
		return a.certain() && b.certain() ? a.because(Why.REDECLARED,
				Collections.emptyList()) : a.certain() ? b : a;
	}

	// A word of the name that stands in no compiled code: in a
	// directive, in code that is not compiled or not read, or where a
	// macro that is a number was replaced.
	private static final class Other {
		int line;
		int column;
		int where;
		int state;
		Macro macro;
		boolean maybe;
		Conditional condition;
		// How many macros that were not executed stand before it, and
		// where in the code that was read it stands.
		int causes;
		int at;
	}

	/** One program, read once. */
	private static final class Reading {
		private final Syntax.Program program;
		private final Symbol symbol;
		private final String name;
		private final String candidate;
		private final GlslPreprocessor preprocessor;
		private final GlslDeclarations reader = new GlslDeclarations(
				Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
		// The code that was given to the reader, its lines ended by a
		// line feed, and its tokens: where each stands in it, how long
		// it is, where it came from (-1 for one a macro wrote), what
		// it is, and how many macros that were not executed stand
		// before it.
		private final StringBuilder fed = new StringBuilder();
		private int[] at = new int[256];
		private int[] length = new int[256];
		private int[] line = new int[256];
		private int[] column = new int[256];
		private byte[] kind = new byte[256];
		private byte[] flags = new byte[256];
		private int[] before = new int[256];
		private int count;
		private final List<Other> others = new ArrayList<>();
		// The macros that were not executed where the code mentions
		// them, each once, in the order of the program.
		private final List<Cause> causes = new ArrayList<>();
		private final Map<String, Integer> caused = new HashMap<>();
		// For a name that an arm of a conditional that is not decided
		// mentions, the first such conditional; and the first one
		// whose arms are not whole statements.
		private final Map<String, List<Conditional>> undecided =
				new HashMap<>();
		// For such a name, where the first of them outside of a body
		// starts: what it names is not trusted to the end.
		private final Map<String, Integer> undecidedOutside =
				new HashMap<>();
		private Conditional uneven;
		// Where candidate is defined as a macro, if anywhere.
		private final List<int[]> candidateMacros = new ArrayList<>();
		// The logical line being read: where each of its parts starts
		// in it, and the line and column of the program it is from.
		private int[] partStart = new int[4];
		private int[] partLine = new int[4];
		private int[] partColumn = new int[4];
		private int parts;
		private GlslKnowledge known;
		// The names of the code in their order, as where each stands.
		private int[] names;
		private boolean failed;
		boolean declared;
		// Whether the name stands where the symbol says in a line that
		// is not compiled, or not for sure.
		boolean written;
		// Whether the declaration asked about is one outside of
		// functions, and where it stands in the code that was read.
		private boolean outside = true;
		private int declaredAt = -1;
		// What keeps the declaration asked about from being proven to
		// be one where it stands, as its place that is listed, or
		// null: then nothing of this reading is certain.
		private Use doubt;
		// The first head of each form of a function, by the name and
		// the parameters of the form, and by where each head stands.
		private final Map<String, Integer> forms = new HashMap<>();
		private final Map<Integer, Integer> heads = new HashMap<>();
		// The conditions that are not decided which a type or a form
		// that was asked for last depends on.
		private final List<Conditional> doubts = new ArrayList<>();
		int[] bindings;
		boolean[] proven;

		Reading(Syntax.Program program, Symbol symbol, String candidate) {
			this.program = program;
			this.symbol = symbol;
			this.candidate = candidate;
			name = symbol.name();
			preprocessor = new GlslPreprocessor(program.version());
		}

		// ---- Reading the program ----------------------------------------

		// Reads the program; false if it cannot be read as its
		// compiler reads it.
		boolean read() {
			LineTokens tokens = new LineTokens();
			int lexed = GlslLexer.TEXT_START;
			StringBuilder logical = new StringBuilder();
			for (int n = 0, lines = program.lines(); n < lines && !failed; ) {
				// A logical line: joined over the backslashes that end
				// its lines.
				logical.setLength(0);
				parts = 0;
				int first = n;
				for (boolean goesOn = true; goesOn && n < lines; ++n) {
					CharSequence text = program.line(n);
					int end = text.length();
					goesOn = end > 0 && text.charAt(end - 1) == '\\';
					if (goesOn && program.version() < 300) {
						// A compiler of that version may read the next
						// line for itself.
						return false;
					}
					part(logical.length(), n, 0);
					logical.append(text, 0, goesOn ? end - 1 : end);
				}
				lexed = GlslLexer.lexLine(logical, n < lines, lexed, tokens);
				boolean directive = false;
				for (int i = 0, size = tokens.size(); i < size; ++i) {
					directive |= tokens.inDirective(i);
					if (isWord(tokens.style(i)) && partOf(tokens.start(i)) !=
							partOf(tokens.end(i) - 1)) {
						// A name across a join has no one place.
						return false;
					}
				}
				if (directive) {
					if (tokens.commentGoesOn()) {
						// A comment that is open where a directive's
						// line ends: compilers differ in what follows.
						return false;
					}
					directive(logical, tokens, first);
				} else {
					code(logical, tokens);
				}
			}
			if (failed || preprocessor.failed()) {
				return false;
			}
			known = reader.build();
			// A text the reader had to guess at proves nothing.
			return known.complete();
		}

		private void part(int start, int ofLine, int ofColumn) {
			if (parts == partStart.length) {
				partStart = Arrays.copyOf(partStart, parts * 2);
				partLine = Arrays.copyOf(partLine, parts * 2);
				partColumn = Arrays.copyOf(partColumn, parts * 2);
			}
			partStart[parts] = start;
			partLine[parts] = ofLine;
			partColumn[parts++] = ofColumn;
		}

		// The part of the logical line a column of it lies in.
		private int partOf(int logicalColumn) {
			int part = parts - 1;
			while (part > 0 && partStart[part] > logicalColumn) {
				--part;
			}
			return part;
		}

		private static boolean isWord(Style style) {
			return style != Style.COMMENT && style != Style.OPERATOR &&
					style != Style.NUMBER && style != Style.STRING;
		}

		private static boolean isWord(CharSequence text, int start) {
			char c = text.charAt(start);
			return c == '_' || (c >= 'a' && c <= 'z') ||
					(c >= 'A' && c <= 'Z');
		}

		private boolean isName(CharSequence text, int start, int end) {
			return end - start == name.length() &&
					GlslStatement.is(text, start, end, name);
		}

		private void directive(CharSequence text, LineTokens tokens,
				int number) {
			preprocessor.directive(text, tokens, number, fed.length(),
					(token, role, state, macro, maybe) -> {
						int start = tokens.start(token);
						int end = tokens.end(token);
						if (candidate != null && role ==
								GlslPreprocessor.DEFINED &&
								state != GlslPreprocessor.INACTIVE &&
								end - start == candidate.length() &&
								GlslStatement.is(text, start, end, candidate)) {
							int part = partOf(start);
							candidateMacros.add(new int[]{partLine[part],
									partColumn[part] + start -
											partStart[part]});
						}
						if (isName(text, start, end)) {
							Other other = other(start, role, state);
							other.macro = macro;
							other.maybe = maybe;
						}
					});
			Conditional closed = preprocessor.closed();
			if (closed != null && closed.uneven && uneven == null) {
				uneven = closed;
			}
		}

		private Other other(int logicalColumn, int where, int state) {
			int part = partOf(logicalColumn);
			Other other = new Other();
			other.line = partLine[part];
			other.column = partColumn[part] + logicalColumn - partStart[part];
			other.where = where;
			other.state = state;
			other.condition = state == GlslPreprocessor.ACTIVE ? null
					: preprocessor.innermost();
			other.causes = causes.size();
			other.at = fed.length();
			others.add(other);
			return other;
		}

		// A line of code.
		private void code(CharSequence text, LineTokens tokens) {
			int state = preprocessor.state();
			if (state == GlslPreprocessor.INACTIVE) {
				for (int i = 0, n = tokens.size(); i < n; ++i) {
					if (isWord(tokens.style(i)) && isName(text,
							tokens.start(i), tokens.end(i))) {
						other(tokens.start(i), CODE, state);
					}
				}
				return;
			}
			boolean read = state != GlslPreprocessor.SKIPPED;
			LineTokens out = new LineTokens();
			int lineStart = fed.length();
			for (int i = 0, n = tokens.size(); i < n; ++i) {
				Style style = tokens.style(i);
				if (style == Style.COMMENT) {
					continue;
				}
				int start = tokens.start(i);
				int end = tokens.end(i);
				preprocessor.code(style == Style.OPERATOR && end - start == 1
						? text.charAt(start) : 0);
				boolean word = isWord(style) && isWord(text, start);
				String written = word ? text.subSequence(start, end)
						.toString() : null;
				if (word && program.version() < 300 &&
						NAMES_OF_100.contains(written)) {
					// A name to this program's compiler, a word of the
					// language to the reader.
					failed = true;
				}
				if (word && state != GlslPreprocessor.ACTIVE &&
						style != Style.FIELD_SELECTION) {
					// What an arm that is not decided names is not to
					// be trusted behind it either.
					Conditional first = preprocessor.undecided();
					if (first != null && Token.of(style, text, start, end) ==
							Token.NAME) {
						// One entry for a conditional and a name.
						List<Conditional> naming = undecided.get(written);
						if (naming == null) {
							naming = new ArrayList<>();
							undecided.put(written, naming);
						}
						if (naming.isEmpty() || naming.get(
								naming.size() - 1) != first) {
							naming.add(first);
						}
					}
				}
				if (!read) {
					if (word && written.equals(name)) {
						other(start, CODE, state);
					}
					continue;
				}
				Macro macro = word ? preprocessor.macro(written) : null;
				boolean mention = macro != null ||
						(word && preprocessor.isUndecided(written));
				if (macro != null && macro.number != null) {
					// Executed: its tokens stand in its place.
					if (written.equals(name)) {
						other(start, NUMBER_MACRO, state).macro = macro;
					}
					for (String token : macro.number) {
						char c = token.charAt(0);
						Style made = (c >= '0' && c <= '9') || c == '.'
								? Style.NUMBER : Style.OPERATOR;
						add(out, lineStart, token, 0, token.length(), made,
								-1, 0, 0);
					}
					continue;
				}
				int flag = state == GlslPreprocessor.FED ? UNDECIDED : 0;
				if (style == Style.FIELD_SELECTION) {
					flag |= MEMBER;
				}
				if (mention) {
					// Not executed: nothing behind it is proven.
					flag |= MENTION;
					if (macro != null && pastes(macro)) {
						other(start, PASTING, state).macro = macro;
					}
				}
				int part = partOf(start);
				add(out, lineStart, text, start, end, style, partLine[part],
						partColumn[part] + start - partStart[part], flag);
				if (mention && !caused.containsKey(written)) {
					caused.put(written, causes.size());
					if (macro != null) {
						mentions.put(written, macro);
					}
					causes.add(macro == null
							? new Cause(Why.CONDITIONAL, written, null, -1)
							: new Cause(Why.IN_MACRO, written,
									macro.line < 0 ? null
											: program.file(macro.line),
									macro.line < 0 ? -1 : program.offset(
											macro.line, macro.column)));
				}
			}
			if (read) {
				reader.addLine(fed.subSequence(lineStart, fed.length()),
						lineStart, out);
				fed.append('\n');
			}
		}

		// Whether the text of a macro, or of one it names as they are
		// defined here, builds names with "##".
		private boolean pastes(Macro macro) {
			List<Macro> all = new ArrayList<>();
			all.add(macro);
			for (int k = 0; k < all.size() && k < 64; ++k) {
				if (all.get(k).pastes) {
					return true;
				}
				for (String word : all.get(k).words) {
					Macro named = preprocessor.macro(word);
					if (named != null && !all.contains(named)) {
						all.add(named);
					}
				}
			}
			return false;
		}

		// Adds a token to the line being given to the reader, behind a
		// space, so that no two tokens become one.
		private void add(LineTokens out, int lineStart, CharSequence text,
				int start, int end, Style style, int ofLine, int ofColumn,
				int flag) {
			if (fed.length() > lineStart) {
				fed.append(' ');
			}
			int from = fed.length();
			fed.append(text, start, end);
			out.add(from - lineStart, fed.length() - lineStart, style);
			if (count == at.length) {
				int capacity = count * 2;
				at = Arrays.copyOf(at, capacity);
				length = Arrays.copyOf(length, capacity);
				line = Arrays.copyOf(line, capacity);
				column = Arrays.copyOf(column, capacity);
				kind = Arrays.copyOf(kind, capacity);
				flags = Arrays.copyOf(flags, capacity);
				before = Arrays.copyOf(before, capacity);
			}
			at[count] = from;
			length[count] = end - start;
			line[count] = ofLine;
			column[count] = ofColumn;
			kind[count] = (byte) Token.of(style, text, start, end).ordinal();
			flags[count] = (byte) flag;
			before[count] = causes.size();
			++count;
		}

		// ---- What each word is ------------------------------------------

		private static final Token[] TOKENS = Token.values();

		private boolean is(int i, char c) {
			return length[i] == 1 && fed.charAt(at[i]) == c;
		}

		private String text(int i) {
			return fed.substring(at[i], at[i] + length[i]);
		}

		private boolean isIdentifier(int i) {
			return kind[i] == Token.NAME.ordinal();
		}

		// Adds what each word of the name is in this program to places,
		// and what candidate means at them to taken. False if
		// something else is declared where the symbol says it is.
		boolean kinds(Map<String, Use> places, Map<String, Use> taken,
				Map<String, Cause> depends) {
			names = new int[count];
			int named = 0;
			for (int i = 0; i < count; ++i) {
				if (isIdentifier(i)) {
					names[named++] = at[i];
				}
				if (line[i] >= 0 && isAsked(line[i], column[i]) &&
						isName(fed, at[i], at[i] + length[i])) {
					declaredAt = at[i];
				}
			}
			names = Arrays.copyOf(names, named);
			if (declaredAt >= 0) {
				// It has to be a declaration there, of that kind.
				Symbol here = declarationAt(declaredAt);
				if (here == null || !same(here)) {
					return false;
				}
				declared = true;
				outside = symbol.kind() != Kind.PARAMETER &&
						!inBody(declaredAt);
			}
			for (Symbol function : known.symbols()) {
				if (function.kind() == Kind.FUNCTION) {
					String form = function.name() + GlslExpression
							.parameters(function);
					Integer first = forms.get(form);
					if (first == null || function.start() < first) {
						first = function.start();
						forms.put(form, first);
					}
				}
			}
			for (Symbol function : known.symbols()) {
				if (function.kind() == Kind.FUNCTION) {
					heads.put(function.start(), forms.get(function.name() +
							GlslExpression.parameters(function)));
				}
			}
			if (declaredAt >= 0) {
				// The declaration itself is a place like any other: if
				// it is not proven to be one where it stands, nothing
				// is said to mean it for sure.
				Use asked = proof(tokenAt(declaredAt));
				doubt = asked.certain() ? null : asked;
			}
			for (Other other : others) {
				if (!isAsked(other.line, other.column)) {
					continue;
				} else if (other.state != GlslPreprocessor.ACTIVE) {
					written = true;
				} else if (other.where <= GlslPreprocessor.PARAMETER) {
					declared = true;
				}
			}
			bindings = new int[named];
			proven = new boolean[named];
			int statement = 0;
			int parens = 0;
			int squares = 0;
			int layout = 0;
			named = 0;
			for (int i = 0; i < count; ++i) {
				if (kind[i] == Token.PUNCT.ordinal() && length[i] == 1) {
					char c = fed.charAt(at[i]);
					parens = c == '(' ? parens + 1 : c == ')'
							? Math.max(0, parens - 1) : parens;
					squares = c == '[' ? squares + 1 : c == ']'
							? Math.max(0, squares - 1) : squares;
					layout = layout == 0 ? 0 : c == '(' ? layout + 1
							: c != ')' ? layout : layout > 2 ? layout - 1 : 0;
					if (c == '{' || c == '}' || (c == ';' && parens == 0)) {
						statement = i + 1;
						parens = 0;
						squares = 0;
					}
					continue;
				}
				if (layout < 2) {
					layout = kind[i] == Token.LAYOUT.ordinal() ? 1 : 0;
				}
				if (!isIdentifier(i)) {
					continue;
				}
				doubts.clear();
				int means = means(i, statement, squares > 0, layout > 1);
				bindings[named] = means;
				// What is said of it is proven only where a place of
				// the name would be: compiled for sure, behind no macro
				// that was not executed, under no condition that may
				// have changed it, by a form or a type that was told.
				proven[named++] = means != Answer.UNKNOWN &&
						(flags[i] & (UNDECIDED | MENTION)) == 0 &&
						before[i] == 0 && (uneven == null ||
						at[i] < uneven.mark) && (undecided.isEmpty() ||
						naming(text(i), at[i]) == null);
				if (isName(fed, at[i], at[i] + length[i])) {
					Use use = kind(i, statement, means);
					if (line[i] >= 0) {
						put(places, use);
						depends(use, depends);
						// Also where the place is listed for another
						// reason: what the name means there depends
						// on a condition that is not decided.
						Conditional c = (flags[i] & UNDECIDED) != 0
								? innermostAt(i) : uneven != null &&
								at[i] >= uneven.mark ? uneven
								: naming(name, at[i]);
						if (c != null) {
							depends(use(i, Why.CONDITIONAL, cause(c,
									Why.CONDITIONAL)), depends);
						}
						// And where its form or its type could not be
						// told for a condition: which declaration it
						// means depends on that one.
						for (Conditional doubted : doubts) {
							depends(use(i, Why.CONDITIONAL, cause(doubted,
									Why.CONDITIONAL)), depends);
						}
					}
					if (candidate != null && use.why() != null) {
						taken(i, taken);
					}
				}
			}
			for (Other other : others) {
				Use use = kind(other);
				put(places, use);
				depends(use, depends);
			}
			return true;
		}

		// A place that is listed for a condition that is not decided:
		// what the name means there depends on it.
		private void depends(Use use, Map<String, Cause> depends) {
			if (use.why() != Why.CONDITIONAL) {
				return;
			}
			for (Cause cause : use.causes().isEmpty()
					? Collections.singletonList(new Cause(Why.CONDITIONAL,
					name, null, -1)) : use.causes()) {
				depends.put(cause.name() + "\n" + cause.file() + "\n" +
						cause.start(), cause);
			}
		}

		private static void put(Map<String, Use> places, Use use) {
			if (use.file() == null || use.start() < 0) {
				return;
			}
			String key = use.file() + "\n" + use.start();
			Use before = places.get(key);
			places.put(key, before == null ? use : both(before, use));
		}

		// Whether a place of the program is where the symbol asked
		// about has its name.
		private boolean isAsked(int ofLine, int ofColumn) {
			return symbol.file().equals(program.file(ofLine)) &&
					program.offset(ofLine, ofColumn) == symbol.start();
		}

		// The declaration whose name stands at an offset of the code
		// that was read, or null.
		private Symbol declarationAt(int offset) {
			Symbol local = known.local(offset, name);
			if (local != null && local.start() == offset) {
				return local;
			}
			Symbol around = known.holder(offset);
			if (around == null) {
				return null;
			} else if (around.start() == offset) {
				return around;
			}
			for (Symbol part : around.parts()) {
				if (part.start() == offset) {
					return part;
				}
			}
			return null;
		}

		// Whether a declaration of the program is what the symbol asked
		// about says it is: a symbol of another text may stand where
		// something else is declared now. The size of an array is not
		// compared: the caller's symbol has it as it is written, and
		// the program has the number of a macro in its place.
		private boolean same(Symbol here) {
			if (!here.name().equals(name)) {
				return false;
			} else if (here.kind() == Kind.FUNCTION ||
					symbol.kind() == Kind.FUNCTION) {
				if (here.kind() != symbol.kind()) {
					return false;
				}
				List<String> read = GlslExpression.parameters(here);
				List<String> asked = GlslExpression.parameters(symbol);
				if (read.size() != asked.size()) {
					return false;
				}
				for (int i = 0; i < read.size(); ++i) {
					if (!unsized(read.get(i)).equals(unsized(asked.get(i)))) {
						return false;
					}
				}
				return true;
			}
			return symbol.kind() != Kind.MACRO &&
					(here.kind() == Kind.PARAMETER) ==
							(symbol.kind() == Kind.PARAMETER) &&
					(here.kind() == Kind.TYPE) == (symbol.kind() == Kind.TYPE) &&
					(here.kind() == Kind.FIELD) ==
							(symbol.kind() == Kind.FIELD) &&
					(symbol.type() == null || here.type() == null ||
							unsized(symbol.type()).equals(
									unsized(here.type())));
		}

		// A type without what stands in its square brackets.
		private static String unsized(String type) {
			if (type == null || type.indexOf('[') < 0) {
				return String.valueOf(type);
			}
			StringBuilder sb = new StringBuilder();
			int depth = 0;
			for (int i = 0; i < type.length(); ++i) {
				char c = type.charAt(i);
				if (c == ']') {
					--depth;
				}
				if (depth <= 0 || c == '[') {
					sb.append(c);
				}
				if (c == '[') {
					++depth;
				}
			}
			return sb.toString();
		}

		private Use use(int ofLine, int ofColumn, Why why,
				List<Cause> because) {
			int offset = ofLine < 0 ? -1 : program.offset(ofLine, ofColumn);
			return new Use(ofLine < 0 ? null : program.file(ofLine), offset,
					offset < 0 ? -1 : offset + name.length(), why, because);
		}

		private Use use(int i, Why why, List<Cause> because) {
			return use(line[i], column[i], why, because);
		}

		private static final List<Cause> NONE = Collections.emptyList();
		// Nothing of the name is declared: said by a cause without one.
		private static final List<Cause> UNDECLARED = Collections
				.singletonList(new Cause(null, "", null, -1));

		private List<Cause> cause(Conditional c, Why why) {
			return c == null ? NONE : Collections.singletonList(new Cause(why,
					c.written, program.file(c.line), program.offset(c.line,
					c.column)));
		}

		// What keeps an offset of the code that was read from being
		// proven, as a use that is listed, or null: a macro that was
		// not executed before it, or a conditional that is not decided
		// and may have changed what the name means there.
		private Use unproven(int ofLine, int ofColumn, int offset,
				int macrosBefore) {
			if (uneven != null && offset >= uneven.mark) {
				return use(ofLine, ofColumn, Why.CONDITIONAL, cause(uneven,
						Why.CONDITIONAL));
			} else if (macrosBefore > 0) {
				return use(ofLine, ofColumn, Why.IN_MACRO, causes.subList(0,
						macrosBefore));
			}
			Conditional c = naming(name, offset);
			return c != null ? use(ofLine, ofColumn, Why.CONDITIONAL,
					cause(c, Why.CONDITIONAL)) : null;
		}

		/**
		 * Return a conditional that is not decided and names word
		 * before offset where that still counts, or null: one outside
		 * of bodies counts to the end of the program, one in a body to
		 * the end of its function. Conditionals lie in the order of
		 * the code and functions do not lie in each other, so the
		 * nearest before the offset is the only one in a body that
		 * can count.
		 */
		private Conditional naming(String word, int offset) {
			List<Conditional> naming = undecided.get(word);
			if (naming == null) {
				return null;
			}
			Integer outside = undecidedOutside.get(word);
			if (outside == null) {
				outside = naming.size();
				for (int k = 0; k < naming.size(); ++k) {
					if (reach(naming.get(k).mark) == Integer.MAX_VALUE) {
						outside = k;
						break;
					}
				}
				undecidedOutside.put(word, outside);
			}
			if (outside < naming.size() &&
					naming.get(outside).mark <= offset) {
				return naming.get(outside);
			}
			int low = 0;
			int high = naming.size() - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				if (naming.get(mid).mark <= offset) {
					low = mid + 1;
				} else {
					high = mid - 1;
				}
			}
			return high >= 0 && offset < reach(naming.get(high).mark)
					? naming.get(high) : null;
		}

		/**
		 * The kind of a word of the name in the code that was read:
		 * the one place where a use becomes certain. It is, only if
		 * each of these holds: its line is compiled for sure; no macro
		 * that was not executed stands before it, and it is none; no
		 * conditional before it that is not decided names it or leaves
		 * the code in another shape; it stands where code refers to
		 * something; and what the scopes say it means there is the
		 * declaration, by a form and a type that are proven the same
		 * way. A use with a why of null is none.
		 */
		private Use kind(int i, int statement, int means) {
			Use use = proof(i);
			if (use.certain() && at[i] != declaredAt) {
				use = meaning(i, statement, means);
			}
			// Certain only if the declaration asked about is.
			return doubt != null && use.certain()
					? use(i, doubt.why(), doubt.causes()) : use;
		}

		// Whether the word at token i is proven to be what the reader
		// took it for, whatever that is: as a use that is listed if it
		// is not, as the declaration if it is. The declaration asked
		// about passes this like every other place.
		private Use proof(int i) {
			if ((flags[i] & UNDECIDED) != 0) {
				return use(i, Why.CONDITIONAL, cause(
						innermostAt(i), Why.CONDITIONAL));
			}
			Use unproven = unproven(line[i], column[i], at[i], before[i]);
			if (unproven != null) {
				return unproven;
			} else if (at[i] == declaredAt && isInvoked(i)) {
				// The name is a macro there, or may be one: what the
				// reader took for a declaration is what that writes.
				Macro macro = mentions.get(text(i));
				return use(i, macro == null ? Why.CONDITIONAL : Why.IN_MACRO,
						Collections.singletonList(causes.get(caused.get(
								text(i)))));
			}
			return use(i, Why.DECLARATION, NONE);
		}

		// What a word of the name is that is proven to stand in
		// compiled code behind nothing that was not executed.
		private Use meaning(int i, int statement, int means) {
			boolean macroAsked = symbol.kind() == Kind.MACRO;
			if (isInvoked(i)) {
				// The first macro that was not executed, or a name
				// nobody can tell whether it is one.
				Macro macro = mentions.get(text(i));
				return use(i, macro == null ? Why.CONDITIONAL
						: !macroAsked || macro.line < 0 ? null
						: isAsked(macro.line, macro.column) ? Why.USE
						: Why.REDECLARED, NONE);
			} else if (macroAsked || isMacroParameter()) {
				return use(i, null, NONE);
			} else if ((flags[i] & MEMBER) != 0) {
				// Behind a ".": a use if it is that field.
				Symbol field = symbol.kind() != Kind.FIELD ? NO_FIELD
						: member(i, statement);
				return use(i, field == UNTYPED_FIELD ? Why.UNTYPED
						: field.start() == declaredAt && declaredAt >= 0
						? Why.USE : null, NONE);
			} else if (means == Answer.UNKNOWN) {
				// A call whose form is not proven: of the function
				// asked about if a head of it can be seen there.
				for (Symbol form : known.before(at[i], name)) {
					if (isHeadOfAsked(form)) {
						return use(i, Why.AMBIGUOUS, NONE);
					}
				}
				return use(i, null, NONE);
			} else if (means < 0) {
				return use(i, null, means == Answer.NOTHING
						? UNDECLARED : NONE);
			}
			// What it means is a declaration of the program, a function
			// as the first head of its form: the one asked about, by
			// that same number, or another.
			int asked = declaredAt < 0 ? Answer.NOTHING : entity(declaredAt);
			Integer head = heads.get(at[i]);
			if (head != null) {
				// The name of a head: one more declaration of the
				// function asked about, or of another.
				return use(i, names[means] == asked ? Why.DECLARATION : null,
						NONE);
			} else if (names[means] == asked) {
				return use(i, Why.USE, NONE);
			} else if (names[means] == at[i]) {
				// The name of another declaration is no place.
				return use(i, null, NONE);
			}
			// A use of another declaration of the name: said if another
			// file declares it, since that file means something else
			// by the name.
			Symbol other = declarationAt(names[means]);
			int token = tokenAt(names[means]);
			return use(i, !outside || token < 0 || line[token] < 0 ||
					symbol.file().equals(program.file(line[token])) ||
					(other != null && other.kind() == Kind.FUNCTION &&
							symbol.kind() == Kind.FUNCTION)
					? null : Why.REDECLARED, NONE);
		}

		// Where the declaration at an offset has the name that stands
		// for it among the names: for a function the first head of its
		// form, for anything else its own.
		private int entity(int offset) {
			Integer head = heads.get(offset);
			return head == null ? offset : head;
		}

		// Whether a function of the program is a head of the form asked
		// about.
		private boolean isHeadOfAsked(Symbol function) {
			return declaredAt >= 0 && function.kind() == Kind.FUNCTION &&
					entity(function.start()) == entity(declaredAt);
		}

		// The kind of a word of the name that stands in no code that
		// was read.
		private Use kind(Other other) {
			boolean macroAsked = symbol.kind() == Kind.MACRO;
			Macro macro = other.macro;
			if (other.where == PASTING) {
				// Not the name: the name of the macro where it is
				// invoked.
				int offset = program.offset(other.line, other.column);
				return new Use(program.file(other.line), offset, offset < 0
						? -1 : offset + macro.name.length(), Why.PASTES,
						Collections.singletonList(causes.get(caused.get(
								macro.name))));
			} else if (other.where <= GlslPreprocessor.PARAMETER &&
					other.state == GlslPreprocessor.ACTIVE &&
					isAsked(other.line, other.column)) {
				return use(other.line, other.column, Why.DECLARATION, NONE);
			} else if (other.state == GlslPreprocessor.INACTIVE) {
				return use(other.line, other.column, Why.INACTIVE, cause(
						other.condition, Why.INACTIVE));
			} else if (other.state != GlslPreprocessor.ACTIVE) {
				return use(other.line, other.column, Why.CONDITIONAL, cause(
						other.condition, Why.CONDITIONAL));
			}
			switch (other.where) {
				case GlslPreprocessor.DEFINED:
					// Another macro of the name.
					return use(other.line, other.column, macroAsked
							? Why.REDECLARED : null, NONE);
				case GlslPreprocessor.PARAMETER:
					// A parameter is its macro's own; the one asked
					// about is used in the text of its macro.
					return use(other.line, other.column, isMacroParameter() &&
							macro != null && macro.line == declaringLine()
							? Why.USE : null, NONE);
				case GlslPreprocessor.TEXT:
					return use(other.line, other.column, isMacroParameter()
							? null : Why.IN_MACRO, macro == null ? NONE
							: Collections.singletonList(new Cause(Why.IN_MACRO,
							macro.name, program.file(macro.line),
							program.offset(macro.line, macro.column))));
				case GlslPreprocessor.TEST:
					// A directive asks about the name, or undefines it.
					return use(other.line, other.column, !macroAsked ? null
							: other.maybe ? Why.CONDITIONAL : macro == null
							? Why.TEST : macro.line < 0 ? null
							: isAsked(macro.line, macro.column) ? Why.USE
							: Why.REDECLARED, NONE);
				case NUMBER_MACRO:
					// Replaced by its number where it stood.
					Use unproven = unproven(other.line, other.column,
							other.at, other.causes);
					return unproven != null ? unproven : use(other.line,
							other.column, !macroAsked ? null
									: isAsked(macro.line, macro.column)
									? Why.USE : Why.REDECLARED, NONE);
				default:
					return use(other.line, other.column, null, NONE);
			}
		}

		// The line of the program the declaration asked about stands
		// in, if it is a parameter of a macro, else -1.
		private int declaringLine() {
			for (Other other : others) {
				if (other.where == GlslPreprocessor.PARAMETER &&
						isAsked(other.line, other.column)) {
					return other.macro == null ? -1 : other.macro.line;
				}
			}
			return -1;
		}

		private boolean isMacroParameter() {
			return symbol.kind() == Kind.PARAMETER && declaringLine() >= 0;
		}

		// Whether token i is a macro that was not executed and is
		// invoked there, or a name nobody can tell whether it is a
		// macro: a macro with parameters is one only before a "(".
		private boolean isInvoked(int i) {
			if ((flags[i] & MENTION) == 0) {
				return false;
			}
			Macro macro = mentions.get(text(i));
			return macro == null || !macro.function ||
					(i + 1 < count && is(i + 1, '('));
		}

		// The macros that were not executed, as each was defined
		// where the code first mentions it.
		private final Map<String, Macro> mentions = new HashMap<>();

		// The innermost conditional a token of an arm that is not
		// decided stands in is not kept with it: the first one that
		// names it is what is told.
		private Conditional innermostAt(int i) {
			List<Conditional> naming = undecided.get(text(i));
			if (naming == null) {
				return null;
			}
			// They lie in the order of the code: the last that starts
			// before the token, found by halving.
			int low = 0;
			int high = naming.size() - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				if (naming.get(mid).mark <= at[i]) {
					low = mid + 1;
				} else {
					high = mid - 1;
				}
			}
			return high < 0 ? null : naming.get(high);
		}

		// ---- What a name means ------------------------------------------

		// Returns the token that stands at an offset of the code that
		// was read, or -1.
		private int tokenAt(int offset) {
			int low = 0;
			int high = count - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				if (at[mid] < offset) {
					low = mid + 1;
				} else if (at[mid] > offset) {
					high = mid - 1;
				} else {
					return mid;
				}
			}
			return -1;
		}

		// Returns which of the names stands at an offset, or NOTHING.
		private int nameAt(int offset) {
			int index = Arrays.binarySearch(names, offset);
			return index < 0 ? Answer.NOTHING : index;
		}

		/**
		 * Return what the name at token i means, as the number of the
		 * name that declares it: itself where it is declared. NOTHING
		 * if it means nothing the program declares or stands where no
		 * code refers to anything, UNKNOWN for a call whose form is
		 * not proven, and less for a macro that was not executed.
		 */
		private int means(int i, int statement, boolean inSquares,
				boolean inLayout) {
			if (isInvoked(i)) {
				return invoked(i);
			} else if ((flags[i] & MEMBER) != 0) {
				// A field of a struct or of a block, if the value
				// before the "." is proven to be one.
				Symbol field = member(i, statement);
				return field == UNTYPED_FIELD ? Answer.UNKNOWN
						: field == NO_FIELD ? Answer.NOTHING
						: nameAt(field.start());
			}
			int offset = at[i];
			String word = text(i);
			if (!inBody(offset)) {
				// Outside of bodies a word refers to something only
				// behind the name being declared, in its initializer
				// or its brackets, or as its type before it.
				Symbol around = known.holder(offset);
				if (around == null) {
					return Answer.NOTHING;
				}
				Symbol declares = around;
				for (Symbol part : around.parts()) {
					if (offset >= part.from() && offset < part.to()) {
						declares = part;
					}
				}
				if (offset == declares.start()) {
					return nameAt(entity(offset));
				} else if (!inSquares && (offset < declares.start()
						? inLayout || !isType(declares.type(), word)
						: declares.kind() == Kind.TYPE ||
						declares.kind() == Kind.FUNCTION)) {
					return Answer.NOTHING;
				}
			}
			Symbol local = known.local(offset, word);
			if (local != null) {
				return nameAt(local.start());
			}
			List<Symbol> meant = known.before(offset, word);
			if (meant.isEmpty()) {
				return Answer.NOTHING;
			} else if (meant.get(0).kind() != Kind.FUNCTION) {
				return nameAt(meant.get(0).start());
			}
			// A function is its form, whichever of its heads is seen.
			// One form alone is meant without looking at the
			// arguments, unless the language has a function of that
			// name too, which other arguments would mean.
			int one = entity(meant.get(0).start());
			boolean several = GlslWords.style(fed, offset, offset +
					length[i]) == Style.BUILTIN_FUNCTION;
			for (int k = 1; k < meant.size() && !several; ++k) {
				several = entity(meant.get(k).start()) != one;
			}
			if (!several) {
				return nameAt(one);
			}
			Symbol form = form(i, meant);
			return form == null ? Answer.UNKNOWN
					: nameAt(entity(form.start()));
		}

		// What an invocation of a macro that was not executed is to
		// the bindings: which macro, and what each name of its text,
		// and of the texts it names, would mean where it is invoked.
		// That is no expansion and proves nothing; but if one of those
		// names comes to mean something else there, as when a local it
		// meant is renamed and the text is not, this is another
		// number, and two readings are not the same.
		private int invoked(int i) {
			String written = text(i);
			Integer cause = caused.get(written);
			int hash = cause == null ? 0 : cause;
			Macro macro = mentions.get(written);
			if (macro != null) {
				List<Macro> all = new ArrayList<>();
				all.add(macro);
				for (int k = 0; k < all.size() && k < 64; ++k) {
					for (String word : all.get(k).words) {
						Macro named = mentions.get(word);
						if (named != null) {
							if (!all.contains(named)) {
								all.add(named);
							}
							continue;
						}
						Symbol local = known.local(at[i], word);
						int means = Answer.NOTHING;
						if (local != null) {
							means = nameAt(local.start());
						} else {
							List<Symbol> meant = known.before(at[i], word);
							if (!meant.isEmpty()) {
								means = nameAt(meant.get(0).start());
							}
						}
						hash = hash * 31 + means;
					}
				}
			}
			return -3 - (hash & 0x3fffffff);
		}

		// Whether a type, as a declaration has it, is word, alone or
		// as an array.
		private static boolean isType(String type, String word) {
			return type != null && type.startsWith(word) &&
					(type.length() == word.length() ||
							type.charAt(word.length()) == '[');
		}

		// Whether an offset of the code that was read lies in the body
		// of a function: behind its head, which ends with its
		// parameters.
		private boolean inBody(int offset) {
			Symbol around = known.holder(offset);
			if (around == null || around.kind() != Kind.FUNCTION ||
					offset <= around.end()) {
				return false;
			}
			List<Symbol> parts = around.parts();
			return parts.isEmpty() || offset >= parts.get(parts.size() - 1)
					.to();
		}

		// How far what was declared at an offset may be seen: in the
		// body of a function to its end, anywhere else to the end of
		// the program.
		private int reach(int from) {
			return inBody(from) ? known.holder(from).to()
					: Integer.MAX_VALUE;
		}

		// The tokens from from to to, as a statement to tell types
		// from, or null if they are more than a statement keeps.
		private GlslStatement statement(int from, int to) {
			GlslStatement statement = new GlslStatement();
			for (int i = from; i < to; ++i) {
				if (!statement.add(fed, at[i], at[i] + length[i], at[i],
						TOKENS[kind[i]])) {
					return null;
				}
			}
			return statement;
		}

		// The form of a function that the call at token i means, or
		// null if that is not proven: read from all of its arguments,
		// each of a type that is proven.
		private Symbol form(int i, List<Symbol> forms) {
			if (i + 1 >= count || !is(i + 1, '(')) {
				return null;
			}
			int close = i + 1;
			for (int depth = 0; close < count; ++close) {
				depth += is(close, '(') ? 1 : is(close, ')') ? -1 : 0;
				if (depth == 0) {
					break;
				}
			}
			GlslStatement call = close < count ? statement(i, close + 1)
					: null;
			if (call == null) {
				return null;
			}
			List<String> arguments = GlslExpression.arguments(call, 2,
					call.size() - 1, proven(at[i]));
			Symbol taken = null;
			for (Symbol form : forms) {
				List<String> parameters = GlslExpression.parameters(form);
				if (parameters.size() != arguments.size()) {
					continue;
				}
				for (String parameter : parameters) {
					if (!isProven(parameter)) {
						return null;
					}
				}
				if (GlslTypes.accepts(parameters, arguments)) {
					if (arguments.contains(null) || (taken != null &&
							entity(taken.start()) != entity(form.start()))) {
						return null;
					}
					// Another head of the same form is the same
					// function.
					taken = taken == null ? form : taken;
				}
			}
			return taken;
		}

		// No field: the name behind the "." is none of a struct or a
		// block of the program, as in "p.xy". And one that cannot be
		// told, since the type of the value before the "." cannot.
		private static final Symbol NO_FIELD = new Symbol("", Kind.FIELD,
				"", null, null, -1, -1, -1, -1, Collections.emptyList(),
				null);
		private static final Symbol UNTYPED_FIELD = new Symbol("",
				Kind.FIELD, "", null, null, -1, -1, -1, -1,
				Collections.emptyList(), null);
		// The names of every field the program declares, and the
		// answer for the token asked last.
		private Set<String> fields;
		private final Set<String> structs = new HashSet<>();
		private int memberAt = -1;
		private Symbol memberIs;

		/**
		 * Return the field the name at token i, behind a ".", means:
		 * of the struct the value before it is of, or of the block the
		 * value is the instance of. NO_FIELD if it is none,
		 * UNTYPED_FIELD if the type of that value is not proven.
		 */
		private Symbol member(int i, int statement) {
			if (memberAt == i) {
				return memberIs;
			}
			memberAt = i;
			return memberIs = memberOf(i, statement);
		}

		private Symbol memberOf(int i, int statement) {
			if (fields == null) {
				// Once for a reading, not for every member in it.
				fields = new HashSet<>();
				for (Symbol holder : known.symbols()) {
					if (holder.kind() == Kind.TYPE) {
						structs.add(holder.name());
					}
					for (Symbol part : holder.parts()) {
						if (part.kind() == Kind.FIELD) {
							fields.add(part.name());
						}
					}
				}
			}
			String word = text(i);
			if (!fields.contains(word)) {
				return NO_FIELD;
			}
			GlslStatement value = i > statement ? statement(statement, i - 1)
					: null;
			if (value == null) {
				return UNTYPED_FIELD;
			}
			int size = value.size();
			int start = GlslExpression.valueStart(value, size);
			GlslExpression.Names names = proven(at[i]);
			Symbol holder = null;
			if (start == size - 1 && value.kind(start) == Token.NAME) {
				// One name: the instance of a block, whose fields the
				// reader keeps with it.
				holder = instance(value.text(start), at[i]);
			}
			if (holder == null) {
				String type = start < size ? GlslExpression.type(value,
						start, size, names) : null;
				if (type == null) {
					return UNTYPED_FIELD;
				}
				// A struct of that name declared later would be
				// another.
				holder = names.struct(type);
				if (holder == null) {
					// No struct that is proven: none at all, as for
					// "p.xy" of a vector, or one that a condition
					// keeps from being trusted, which is not told.
					int size1 = type.indexOf('[');
					return structs.contains(size1 < 0 ? type
							: type.substring(0, size1)) ? UNTYPED_FIELD
							: NO_FIELD;
				}
			}
			for (Symbol part : holder.parts()) {
				if (part.kind() == Kind.FIELD && part.name().equals(word)) {
					return part;
				}
			}
			return NO_FIELD;
		}

		// The instance of a block that word names at an offset, if
		// that is proven, else null.
		private Symbol instance(String word, int offset) {
			if (naming(word, offset) != null ||
					known.local(offset, word) != null) {
				return null;
			}
			List<Symbol> meant = known.before(offset, word);
			if (meant.isEmpty()) {
				return null;
			}
			Symbol first = meant.get(0);
			if (first.kind() == Kind.TYPE || first.kind() == Kind.FUNCTION ||
					first.parts().isEmpty() ||
					first.parts().get(0).kind() != Kind.FIELD) {
				return null;
			}
			return isProven(first) ? first : null;
		}

		// Whether a type is one that is proven as it is written: the
		// size of an array is a number.
		private static boolean isProven(String type) {
			if (type == null) {
				return false;
			}
			for (int k = type.indexOf('[') + 1; k > 0 && k < type.length();
					++k) {
				char c = type.charAt(k);
				if (c != ']' && c != '[' && (c < '0' || c > '9')) {
					return false;
				}
			}
			return true;
		}

		// Whether a declaration is proven where it stands: its line is
		// compiled for sure, and no conditional that is not decided
		// named it before.
		private boolean isProven(Symbol declared) {
			int token = tokenAt(declared.start());
			if (token < 0) {
				return false;
			} else if ((flags[token] & UNDECIDED) != 0) {
				doubted(innermostAt(token));
				return false;
			}
			Conditional c = naming(declared.name(), declared.start());
			doubted(c);
			return c == null;
		}

		// A condition that is not decided kept a type or a form from
		// being proven: what the place means depends on it.
		private void doubted(Conditional c) {
			if (c != null && !doubts.contains(c)) {
				doubts.add(c);
			}
		}

		// What names mean at a place of the code that was read, for a
		// type: only what is proven. A place asks this only if no
		// macro that was not executed stands before it, so before
		// every declaration it can see.
		private GlslExpression.Names proven(int offset) {
			return new GlslExpression.Names() {
				private boolean trusted(String word) {
					Conditional c = naming(word, offset);
					doubted(c);
					return c == null;
				}

				@Override
				public String variable(String word) {
					if (!trusted(word)) {
						return null;
					}
					Symbol local = known.local(offset, word);
					if (local != null) {
						return isProven(local) && isProven(local.type())
								? local.type() : null;
					}
					List<Symbol> meant = known.before(offset, word);
					if (meant.isEmpty()) {
						return GlslKnowledge.builtinVariable(word);
					}
					Symbol first = meant.get(0);
					return first.kind() == Kind.FUNCTION ||
							first.kind() == Kind.TYPE || !isProven(first) ||
							!isProven(first.type()) ? null : first.type();
				}

				@Override
				public List<Symbol> functions(String word) {
					if (!trusted(word) || known.local(offset, word) != null) {
						return UNPROVEN;
					}
					List<Symbol> meant = known.before(offset, word);
					if (meant.isEmpty() ||
							meant.get(0).kind() != Kind.FUNCTION) {
						return Collections.emptyList();
					}
					for (Symbol form : meant) {
						if (!isProven(form) || !isProven(form.type())) {
							return UNPROVEN;
						}
						for (String parameter : GlslExpression.parameters(
								form)) {
							if (!isProven(parameter)) {
								return UNPROVEN;
							}
						}
					}
					return meant;
				}

				@Override
				public Symbol struct(String word) {
					if (!trusted(word) || known.local(offset, word) != null) {
						return null;
					}
					List<Symbol> meant = known.before(offset, word);
					return meant.isEmpty() ||
							meant.get(0).kind() != Kind.TYPE ||
							!isProven(meant.get(0)) ? null : meant.get(0);
				}
			};
		}

		// A function nobody can tell what it returns: it stands for a
		// name whose forms are not all proven.
		private static final List<Symbol> UNPROVEN = Collections
				.singletonList(new Symbol("", Kind.FUNCTION, "", null, null,
						0, 0, 0, 0, Collections.emptyList(), null));

		// What candidate means where the name at token i stands: a
		// rename to it would meet that.
		private void taken(int i, Map<String, Use> taken) {
			Symbol local = known.local(at[i], candidate);
			List<Symbol> meant = local != null
					? Collections.singletonList(local)
					: known.before(at[i], candidate);
			for (Symbol symbol : meant) {
				int token = tokenAt(symbol.start());
				if (token >= 0 && line[token] >= 0) {
					int offset = program.offset(line[token], column[token]);
					Use use = new Use(program.file(line[token]), offset,
							offset + candidate.length(), Why.DECLARATION,
							NONE);
					taken.put(use.file() + "\n" + offset, use);
				}
			}
			for (int[] macro : candidateMacros) {
				int offset = program.offset(macro[0], macro[1]);
				Use use = new Use(program.file(macro[0]), offset,
						offset + candidate.length(), Why.DECLARATION, NONE);
				taken.put(use.file() + "\n" + offset, use);
			}
		}
	}
}
