package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The preprocessor of GLSL, executed: the macros that are defined at a
 * line, the conditionals that are open there, and whether the line is
 * compiled. It is given the logical lines of one program in their
 * order, each joined over its backslashes, and does what a directive
 * says where its line is compiled; nothing here is estimated.
 *
 * What it executes of a macro is little, and said in one place,
 * {@link Macro#number}: a macro without parameters whose text is one
 * number is replaced by its tokens. Every other macro is known by its
 * name and where it is defined, and whoever reads the code has to treat
 * a mention of it as something that was not executed.
 *
 * A condition is decided if it is made of whole numbers, of such
 * macros, of "defined", "!", "&amp;&amp;", "||" and parentheses. Of a
 * conditional that is not decided, the first arm that may be compiled
 * is {@link #FED}, to be read so that the code keeps its shape, and
 * the others are {@link #SKIPPED}; a macro that an arm of it defines
 * or undefines is undecided from there on.
 */
final class GlslPreprocessor {
	/** The line is compiled. */
	static final int ACTIVE = 0;
	/** The line is not compiled. */
	static final int INACTIVE = 1;
	/** Nobody can tell, and the line is read. */
	static final int FED = 2;
	/** Nobody can tell, and the line is not read. */
	static final int SKIPPED = 3;

	/** A name where its macro is defined. */
	static final int DEFINED = 0;
	/** A name as a parameter of a macro, also in its text. */
	static final int PARAMETER = 1;
	/** A name in the text of a macro. */
	static final int TEXT = 2;
	/** A name a directive asks about or undefines. */
	static final int TEST = 3;

	private static final int MAX_NESTING = 64;

	/** A macro, as far as it is known without expanding it. */
	static final class Macro {
		String name;
		// Its number among the macros of the program, and where its
		// name stands: the line as the caller counts it, the column.
		int id;
		int line;
		int column;
		boolean function;
		List<String> parameters = Collections.emptyList();
		/**
		 * The tokens it is replaced by, if it is executed: it has no
		 * parameters and its text is one number, with signs and
		 * parentheses around it at most. Null for any other macro.
		 */
		String[] number;
		// Its text, to tell a second definition that is the same; the
		// words of it; and whether it pastes names with "##".
		String text = "";
		List<String> words = new ArrayList<>();
		boolean pastes;
	}

	/** A conditional, from its "#if" to its "#endif". */
	static final class Conditional {
		// What the caller said the place of its "#if" is, and what it
		// is written as.
		int mark;
		int line;
		int column;
		String written;
		int outer;
		boolean taken;
		boolean undecided;
		boolean fed;
		// Brackets the arm being read opened and closed, and whether
		// its code ends where a statement does.
		int braces;
		int parens;
		int low;
		boolean ended;
		/**
		 * Whether an arm that may be compiled is more or less than
		 * whole statements, so that the code behind the conditional
		 * is in other scopes with one arm than with another.
		 */
		boolean uneven;
	}

	/** Who is told of the names a directive has. */
	interface Mentions {
		/**
		 * A name stands at token of the directive's line.
		 *
		 * @param role  what it is there
		 * @param state whether the directive is compiled
		 * @param macro the macro of that name where the line stands,
		 *              for {@link #DEFINED} the one being defined, or
		 *              null if there is none
		 * @param maybe whether nobody can tell if a macro of that name
		 *              is defined there
		 */
		void mention(int token, int role, int state, Macro macro,
				boolean maybe);
	}

	/** What the host of this app defines before every shader. */
	static final String HOST = "SHADER_EDITOR";

	private int version;
	// Whether this reads one file as the editor has it, and not a
	// program: then its includes are not there, and what they define
	// is not known.
	private final boolean file;
	private boolean includes;
	// A name with null was undefined; one in maybe is defined or not.
	private final Map<String, Macro> macros = new HashMap<>();
	private final Set<String> maybe = new HashSet<>();
	private final Conditional[] conditionals = new Conditional[MAX_NESTING];
	private int level;
	private int state = ACTIVE;
	private int count;
	private boolean failed;
	// Parentheses that are open in all code that may be compiled, and
	// whether it ends where a statement does.
	private int parens;
	private boolean ended = true;
	private Conditional closed;

	/**
	 * @param version the version of the language the program is in,
	 *                which says what its compiler defines
	 */
	GlslPreprocessor(int version) {
		this(version, false);
	}

	/**
	 * A preprocessor for one file as the editor has it: its version is
	 * what its "#version" says, the host's own macro is defined, and
	 * behind an "#include" a name the file does not define itself is
	 * not decided, since the file that is included may define it. So
	 * a file that includes nothing is decided as a program of it
	 * alone is.
	 */
	GlslPreprocessor() {
		this(100, true);
		predefine(HOST, "1");
	}

	private GlslPreprocessor(int version, boolean file) {
		this.version = version;
		this.file = file;
		predefine("GL_ES", "1");
		predefine("__VERSION__", String.valueOf(version));
		if (version >= 300) {
			predefine("GL_FRAGMENT_PRECISION_HIGH", "1");
		}
		// Defined, with values nobody here knows.
		predefine("__LINE__", null);
		predefine("__FILE__", null);
	}

	private void predefine(String name, String number) {
		Macro macro = new Macro();
		macro.name = name;
		macro.id = count++;
		macro.line = -1;
		macro.number = number == null ? null : new String[]{number};
		macro.text = number == null ? "\0" : number;
		macros.put(name, macro);
	}

	int state() {
		return state;
	}

	/** Whether the program cannot be read as its compiler reads it. */
	boolean failed() {
		return failed || level > 0;
	}

	/**
	 * Return the macro that is defined under name where the reading
	 * is, or null: if none is, or if nobody can tell, see
	 * {@link #isUndecided}.
	 */
	Macro macro(String name) {
		return maybe.contains(name) ? null : macros.get(name);
	}

	/**
	 * Whether nobody can tell if a macro of that name is defined: an
	 * arm of a conditional that is not decided defined or undefined
	 * it, or its compiler may define it.
	 */
	boolean isUndecided(String name) {
		return maybe.contains(name) || (!macros.containsKey(name) &&
				(includes || name.startsWith("GL_") || name.contains("__")));
	}

	/** Return the innermost conditional that is open, or null. */
	Conditional innermost() {
		return level > 0 ? conditionals[level - 1] : null;
	}

	/** Return the outermost open conditional that is not decided. */
	Conditional undecided() {
		for (int k = 0; k < level; ++k) {
			if (conditionals[k].undecided) {
				return conditionals[k];
			}
		}
		return null;
	}

	/**
	 * Return the conditional that the directive read last closed, if
	 * it was not decided, or null; once.
	 */
	Conditional closed() {
		Conditional c = closed;
		closed = null;
		return c;
	}

	/**
	 * A token of code in a line that is or may be compiled: every
	 * conditional that is open counts what its arm opens and closes.
	 *
	 * @param operator the character of the token if it is one, else 0
	 */
	void code(char operator) {
		ended = operator == ';' || operator == '{' || operator == '}';
		parens = operator == '(' ? parens + 1 : operator == ')'
				? parens - 1 : operator == '{' || operator == '}' ? 0
				: parens;
		for (int k = 0; k < level; ++k) {
			Conditional open = conditionals[k];
			open.ended = ended;
			open.braces += operator == '{' ? 1 : operator == '}' ? -1 : 0;
			open.parens += operator == '(' ? 1 : operator == ')' ? -1 : 0;
			open.low = Math.min(open.low, Math.min(open.braces,
					open.parens));
		}
	}

	/**
	 * Execute the directive of a logical line.
	 *
	 * @param tokens its tokens; those of the directive are read,
	 *               comments are not
	 * @param number the line, as its macros and conditionals are to
	 *               remember it
	 * @param mark   the place in the code read so far, for a
	 *               conditional that starts here
	 */
	void directive(CharSequence line, LineTokens tokens, int number,
			int mark, Mentions out) {
		List<String> words = new ArrayList<>();
		List<Integer> at = new ArrayList<>();
		for (int i = 0, n = tokens.size(); i < n; ++i) {
			if (tokens.style(i) != Style.COMMENT && tokens.inDirective(i)) {
				words.add(line.subSequence(tokens.start(i), tokens.end(i))
						.toString());
				at.add(i);
			}
		}
		String command = words.size() > 1 ? words.get(1) : "";
		switch (command) {
			case "if":
			case "ifdef":
			case "ifndef":
				open(command, line, tokens, words, at, number, mark, out);
				return;
			case "elif":
			case "else":
				turn(command, line, tokens, words, at, out);
				return;
			case "endif":
				close();
				return;
			default:
				break;
		}
		if (state == INACTIVE) {
			// Not executed; its names are in text that is not compiled.
			if (command.equals("define") || command.equals("undef")) {
				for (int i = 2; i < words.size(); ++i) {
					if (isWord(words.get(i))) {
						out.mention(at.get(i), TEXT, state, null, false);
					}
				}
			}
			return;
		}
		switch (command) {
			case "define":
				define(line, tokens, words, at, number, out);
				break;
			case "undef":
				if (words.size() > 2) {
					String name = words.get(2);
					out.mention(at.get(2), TEST, state, macro(name),
							maybe.contains(name));
					if (state == ACTIVE) {
						macros.put(name, null);
						maybe.remove(name);
					} else {
						maybe.add(name);
					}
				}
				break;
			case "include":
				// The text a compiler gets has none: its build put the
				// files in their places. A file has, and what it
				// includes is not read.
				failed |= !file;
				includes = true;
				break;
			case "version":
				if (file && state == ACTIVE && words.size() > 2 &&
						words.get(2).matches("\\d{1,9}")) {
					version = Integer.parseInt(words.get(2));
					predefine("__VERSION__", words.get(2));
					if (version >= 300) {
						predefine("GL_FRAGMENT_PRECISION_HIGH", "1");
					}
				}
				break;
			case "error":
				// Compiled, it compiles nothing.
				failed |= state == ACTIVE;
				break;
			default:
				// "#pragma", "#extension", "#version", "#line": what
				// they say is no code and no name.
				break;
		}
	}

	private static boolean isWord(String text) {
		char c = text.isEmpty() ? 0 : text.charAt(0);
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private void define(CharSequence line, LineTokens tokens,
			List<String> words, List<Integer> at, int number, Mentions out) {
		if (words.size() < 3 || !isWord(words.get(2))) {
			failed |= state == ACTIVE;
			return;
		}
		Macro macro = new Macro();
		macro.name = words.get(2);
		macro.id = count++;
		macro.line = number;
		macro.column = tokens.start(at.get(2));
		out.mention(at.get(2), DEFINED, state, macro, false);
		int from = 3;
		// Parameters stand in a "(" right behind the name.
		if (words.size() > 3 && words.get(3).equals("(") &&
				tokens.start(at.get(3)) == tokens.end(at.get(2))) {
			macro.function = true;
			macro.parameters = new ArrayList<>();
			for (from = 4; from < words.size() &&
					!words.get(from).equals(")"); ++from) {
				if (isWord(words.get(from))) {
					macro.parameters.add(words.get(from));
					out.mention(at.get(from), PARAMETER, state, macro, false);
				}
			}
			++from;
		}
		StringBuilder text = new StringBuilder(macro.function ? "(" +
				String.join(",", macro.parameters) + ")" : "");
		int numbers = 0;
		int opened = 0;
		int closed = 0;
		boolean plain = !macro.function;
		for (int i = from; i < words.size(); ++i) {
			String word = words.get(i);
			text.append(' ').append(word);
			Style style = tokens.style(at.get(i));
			if (word.equals("#")) {
				macro.pastes = true;
				plain = false;
			} else if (isWord(word)) {
				plain = false;
				boolean pasted = (i > from && words.get(i - 1).equals("#")) ||
						(i + 1 < words.size() && words.get(i + 1).equals("#"));
				if (macro.parameters.contains(word)) {
					out.mention(at.get(i), PARAMETER, state, macro, false);
				} else if (!pasted) {
					macro.words.add(word);
					out.mention(at.get(i), TEXT, state, macro, false);
				}
			} else if (style == Style.NUMBER) {
				++numbers;
				plain &= closed == 0;
			} else if (word.equals("(")) {
				++opened;
				plain &= numbers == 0;
			} else if (word.equals(")")) {
				++closed;
				plain &= numbers == 1;
			} else {
				// A sign before the number, nothing else.
				plain &= numbers == 0 && (word.equals("-") ||
						word.equals("+"));
			}
		}
		macro.text = text.toString();
		if (plain && numbers == 1 && opened == closed) {
			macro.number = words.subList(from, words.size())
					.toArray(new String[0]);
		}
		Macro before = macro(macro.name);
		if (state != ACTIVE) {
			// Defined or not: nobody can tell from here on.
			maybe.add(macro.name);
		} else if (before != null && !before.text.equals(macro.text)) {
			// A macro defined again with another text does not compile.
			failed = true;
		} else if (before == null) {
			macros.put(macro.name, macro);
			maybe.remove(macro.name);
		}
	}

	// "#if", "#ifdef", "#ifndef".
	private void open(String command, CharSequence line, LineTokens tokens,
			List<String> words, List<Integer> at, int number, int mark,
			Mentions out) {
		if (level >= MAX_NESTING) {
			failed = true;
			return;
		}
		Conditional c = conditionals[level] = new Conditional();
		c.outer = state;
		c.mark = mark;
		c.line = number;
		c.column = tokens.start(at.get(0));
		c.written = written(words);
		// Between two statements only: in the middle of one, an arm
		// decides what the rest of it is.
		c.uneven = parens != 0 || !ended;
		++level;
		branch(c, decide(command, tokens, words, at, state, out));
	}

	// "#elif", "#else".
	private void turn(String command, CharSequence line, LineTokens tokens,
			List<String> words, List<Integer> at, Mentions out) {
		if (level == 0) {
			failed = true;
			return;
		}
		Conditional c = conditionals[level - 1];
		leave(c);
		c.written = written(words) + " of " + c.written;
		// What an "#elif" asks is asked where the lines around the
		// conditional are.
		branch(c, command.equals("else") ? Boolean.TRUE
				: decide("if", tokens, words, at, c.outer, out));
	}

	// "#endif".
	private void close() {
		if (level == 0) {
			failed = true;
			return;
		}
		Conditional c = conditionals[--level];
		leave(c);
		state = c.outer;
		if (c.undecided && c.outer != INACTIVE && c.outer != SKIPPED) {
			closed = c;
		}
	}

	// An arm of c starts, which is compiled if on, not if not on, and
	// not decided if on is null.
	private void branch(Conditional c, Boolean on) {
		c.braces = 0;
		c.parens = 0;
		c.low = 0;
		c.ended = true;
		if (c.outer == INACTIVE || c.outer == SKIPPED) {
			state = c.outer;
		} else if (c.taken || Boolean.FALSE.equals(on)) {
			state = INACTIVE;
		} else if (on != null && !c.undecided) {
			// Compiled for sure: as the lines around it are.
			state = c.outer;
			c.taken = true;
		} else {
			// Not decided, or behind an arm that was not: the first
			// such is read.
			state = c.fed ? SKIPPED : FED;
			c.fed = true;
			c.undecided = true;
			// An arm that is compiled if no other was ends the list of
			// those that may be.
			c.taken = on != null;
		}
	}

	// An arm of c ends. One that may be compiled has to be whole
	// statements: it closes what it opened and nothing else, and ends
	// where a statement does.
	private void leave(Conditional c) {
		if (state != INACTIVE && c.outer != INACTIVE &&
				c.outer != SKIPPED) {
			c.uneven |= c.braces != 0 || c.parens != 0 || c.low < 0 ||
					!c.ended;
		}
	}

	private static String written(List<String> words) {
		StringBuilder sb = new StringBuilder("#");
		for (int i = 1; i < words.size() && sb.length() < 60; ++i) {
			sb.append(i > 1 ? " " : "").append(words.get(i));
		}
		return sb.toString();
	}

	// Whether the condition of a directive holds where the lines are
	// in state: TRUE, FALSE, or null when it is not decided.
	private Boolean decide(String command, LineTokens tokens,
			List<String> words, List<Integer> at, int where, Mentions out) {
		for (int i = 2; i < words.size(); ++i) {
			String word = words.get(i);
			if (isWord(word) && !word.equals("defined")) {
				out.mention(at.get(i), TEST, where, macro(word),
						maybe.contains(word));
			}
		}
		if (!command.equals("if")) {
			Boolean defined = words.size() == 3 ? defined(words.get(2))
					: null;
			return defined == null ? null
					: defined == command.equals("ifdef");
		}
		int[] i = {2};
		Boolean value = or(tokens, words, at, i);
		return i[0] == words.size() ? value : null;
	}

	// Whether a macro of that name is defined where the reading is, or
	// null if nobody can tell.
	private Boolean defined(String name) {
		return isUndecided(name) ? null : macros.get(name) != null;
	}

	// How deep the parentheses of a condition may lie in each other
	// and still be worked out, and how deep the one being read is.
	private static final int MAX_DEPTH = 64;
	private int depth;

	private Boolean or(LineTokens tokens, List<String> words,
			List<Integer> at, int[] i) {
		Boolean value = and(tokens, words, at, i);
		while (pair(tokens, words, at, i[0], "|")) {
			i[0] += 2;
			Boolean right = and(tokens, words, at, i);
			value = Boolean.TRUE.equals(value) || Boolean.TRUE.equals(right)
					? Boolean.TRUE : value == null || right == null ? null
					: Boolean.FALSE;
		}
		return value;
	}

	private Boolean and(LineTokens tokens, List<String> words,
			List<Integer> at, int[] i) {
		Boolean value = not(tokens, words, at, i);
		while (pair(tokens, words, at, i[0], "&")) {
			i[0] += 2;
			Boolean right = not(tokens, words, at, i);
			value = Boolean.FALSE.equals(value) ||
					Boolean.FALSE.equals(right) ? Boolean.FALSE
					: value == null || right == null ? null : Boolean.TRUE;
		}
		return value;
	}

	private Boolean not(LineTokens tokens, List<String> words,
			List<Integer> at, int[] i) {
		if (i[0] < 0 || i[0] >= words.size()) {
			return null;
		}
		// Every "!" before the operand, but not "!=": counted, not
		// called, so that no text can use up the stack.
		boolean negated = false;
		while (i[0] < words.size() && words.get(i[0]).equals("!") &&
				!(i[0] + 1 < words.size() &&
						words.get(i[0] + 1).equals("="))) {
			negated = !negated;
			++i[0];
		}
		Boolean value = operand(tokens, words, at, i);
		if (value == null || !negated) {
			return value;
		}
		return !value;
	}

	private Boolean operand(LineTokens tokens, List<String> words,
			List<Integer> at, int[] i) {
		if (i[0] < 0 || i[0] >= words.size()) {
			i[0] = -1;
			return null;
		}
		String word = words.get(i[0]);
		if (word.equals("(")) {
			if (depth >= MAX_DEPTH) {
				// Deeper than anybody writes: not decided.
				i[0] = -1;
				return null;
			}
			++i[0];
			++depth;
			Boolean value = or(tokens, words, at, i);
			--depth;
			if (i[0] >= 0 && i[0] < words.size() &&
					words.get(i[0]).equals(")")) {
				++i[0];
				return value;
			}
			i[0] = -1;
			return null;
		} else if (word.equals("defined")) {
			boolean group = i[0] + 1 < words.size() &&
					words.get(i[0] + 1).equals("(");
			int operand = i[0] + (group ? 2 : 1);
			if (operand >= words.size() || (group && (operand + 1 >=
					words.size() || !words.get(operand + 1).equals(")")))) {
				i[0] = -1;
				return null;
			}
			i[0] = operand + (group ? 2 : 1);
			return defined(words.get(operand));
		}
		++i[0];
		// A macro that is one whole number is that number.
		Macro macro = isWord(word) ? macro(word) : null;
		if (macro != null && macro.number != null &&
				macro.number.length == 1) {
			word = macro.number[0];
		}
		// A whole number: 0 is not, any other is. Nothing else is
		// worked out: no other macro, no comparison, no sum.
		boolean digits = !word.isEmpty();
		boolean zero = true;
		for (int k = 0; k < word.length(); ++k) {
			char c = word.charAt(k);
			digits &= c >= '0' && c <= '9';
			zero &= c == '0';
		}
		return digits ? !zero : null;
	}

	// Whether the words at i and behind it are the same character
	// twice, written as one operator.
	private static boolean pair(LineTokens tokens, List<String> words,
			List<Integer> at, int i, String c) {
		return i >= 0 && i + 1 < words.size() && words.get(i).equals(c) &&
				words.get(i + 1).equals(c) && tokens.start(at.get(i + 1)) ==
				tokens.end(at.get(i));
	}
}
