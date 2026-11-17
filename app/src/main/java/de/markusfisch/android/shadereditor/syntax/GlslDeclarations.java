package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslStatement.Token;
import de.markusfisch.android.shadereditor.syntax.GlslSymbols.Draft;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Kind;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * The declarations half of the parser for GLSL ES 1.00 and 3.00: reads a
 * text line by line, with the tokens {@link GlslLexer#lexLine} gives,
 * for its top-level declarations. A function is read with its parameters,
 * a struct with its fields, a macro with its text and parameters, and
 * each with its range and the comment that belongs to it. Of a function
 * body its braces are matched and, if locals are asked for, the
 * statements that declare one are read, see {@link Local}; no other
 * statement is.
 *
 * Every token is one of three things, and each has its own state:
 *
 * a comment: the lines of comment right above the line being read are
 * kept, for what the next declaration or macro starts with;
 *
 * part of a directive, see {@link Directive}: a macro is declared, a file
 * linked, or a conditional opened, branched or closed;
 *
 * code, unless "#if 0" disabled it, see {@link Mode}: outside of bodies
 * it is collected in one {@link GlslStatement}, and {@link GlslSymbols}
 * tells what that declares when it ends.
 *
 * It accepts any text, never throws and never waits for a text to be
 * well-formed. What is being read, a body, the fields of a struct or a
 * declaration with a bracket open, is left for what can start a
 * declaration outside of it and cannot go on with it, see
 * {@link #cannotGoOn}; so a missing "}", ")" or "]" loses nothing below
 * the next function. Besides:
 *
 * a declaration that lost its ";" ends where a new line starts another
 * one, see {@link GlslStatement#endsBefore};
 *
 * after "#else" the nesting is what it was at "#if". Code between
 * "#if 0" and its "#elif", "#else" or "#endif" is not read; every other
 * branch of a conditional is.
 *
 * It keeps one top-level declaration at a time and of the text nothing
 * but what its symbols say, so its memory does not grow with the text.
 */
final class GlslDeclarations implements Knowledge.Builder {
	/**
	 * Where the reader of code is. It changes in five places only:
	 *
	 * TOP to FIELDS or BODY at a "{", in openBrace; FIELDS to TOP at the
	 * "}" of the fields, in top; BODY to TOP in leaveBody, with the "}"
	 * of the body or without it; FIELDS or BODY to TOP in recover, for a
	 * declaration that cannot stand in them; and to BODY in branch, at
	 * the "#else" of a conditional that began in a body.
	 */
	private enum Mode {
		/** In a statement outside of functions, or between two. */
		TOP,
		/** Between the braces of a struct or a block. */
		FIELDS,
		/** Between the braces of a function, or of nothing. */
		BODY
	}

	/**
	 * What the statement of a body is, as far as it was read: only one
	 * that starts with a type and a name is collected, so that reading
	 * bodies costs their declarations and not their expressions.
	 */
	private enum Local {
		/** Nothing of it was read. */
		START,
		/** Words so far: qualifiers, a type, a name. */
		WORDS,
		/** A declaration: it is collected to its ";". */
		DECLARATION,
		/** Anything else: passed over to its ";". */
		OTHER
	}

	/**
	 * Where the reader of code was at an "#if": the whole of what it
	 * goes on from, so that every branch starts from the same place.
	 */
	private static final class Checkpoint {
		// For the end of an arm: the locals it declared whose scopes
		// were open there.
		int[] open;
		int line;
		Mode mode;
		int braces;
		int nesting;
		boolean initializer;
		String documentation;
		Draft function;
		int statementSize;
		Local local;
		int parens;
		int untracked;
		int locals;
		int frames;
		final int[] frameKind = new int[MAX_NESTING];
		final int[] frameFirst = new int[MAX_NESTING];
		final int[] frameParens = new int[MAX_NESTING];
	}

	/** What the directive being read expects of its next token. */
	private enum Directive {
		/** No directive is being read. */
		NONE,
		/** The command, behind the "#". */
		COMMAND,
		/** The name of a macro, behind "#define". */
		DEFINE,
		/** A "(" right behind the name of a macro, or its text. */
		MACRO,
		/** The parameters of a macro, up to the ")". */
		PARAMETERS,
		/** The text of a macro. */
		TEXT,
		/** The path, behind "#include". */
		INCLUDE,
		/** The name, behind "#undef". */
		UNDEF,
		/** "se" behind "#pragma": what the host reads. */
		PRAGMA,
		/** "input" behind "#pragma se". */
		HOST,
		/** The file of an input, behind "#pragma se input". */
		HOST_INPUT,
		/** A file behind "initial=", in what else a host pragma says. */
		HOST_REST,
		/** Nothing. */
		OTHER
	}

	private static final int MAX_DOCUMENTATION = 1000;
	private static final int MAX_FOLDS = 1 << 16;
	private static final int MAX_NESTING = 64;
	// More words than the head of a declaration has.
	private static final int MAX_HEAD_WORDS = 8;
	// What a frame is, and how far its statement was read: see frames.
	private static final int BLOCK = 0;
	private static final int FOR = 1;
	private static final int WHILE = 2;
	private static final int IF = 3;
	private static final int SWITCH = 4;
	private static final int DO = 5;
	private static final int KEYWORD = 0;
	private static final int HEADER = 1;
	private static final int SUBSTATEMENT = 2;
	private static final int BEFORE_ELSE = 3;
	private static final int ELSE = 4;
	private static final int BEFORE_WHILE = 5;
	private static final int BEFORE_SEMICOLON = 6;
	private static final int STAGES = 8;
	// What keyword() answers for "else", which begins no statement.
	private static final int ELSE_WORD = -2;

	// What was read: never more than maxSymbols symbols with maxChars
	// characters of text, of which a quarter is for names alone.
	private final int maxSymbols;
	private final int maxChars;
	private final int maxLocals;
	private final List<Draft> drafts = new ArrayList<>();
	private final List<Symbol> links = new ArrayList<>();
	private final Set<String> structs = new HashSet<>();
	private final GlslLocals locals = new GlslLocals();
	private int nameChars;
	private int chars;
	private boolean full;
	// Whether a cap cut the locals short; and whether anything else was
	// dropped or guessed, so that this is not all there is to know: a
	// statement or a symbol that had more than is kept, statements
	// nested deeper than is tracked, text the reader had to recover
	// from.
	private boolean cut;
	private boolean unsure;

	private int lineNumber = -1;
	// The end of the last token of code, and whether that token ended
	// a statement or was a brace.
	private int lastEnd;
	private boolean boundary = true;

	// Code. Outside of bodies the statement is the declaration being
	// read, with how many of its brackets are open, whether an
	// initializer is being read and the comment above its first token;
	// in a body it is empty. In FIELDS and BODY, braces counts the
	// braces that are open, and function is the one whose body is read,
	// or null in a block that belongs to nothing.
	private GlslStatement statement = new GlslStatement();
	private Mode mode = Mode.TOP;
	private int nesting;
	private boolean initializer;
	private String documentation;
	private int braces;
	private Draft function;

	// In a body, if locals are asked for: the frames, one for each
	// statement that is not finished, innermost last. A frame is a block
	// or a statement with a keyword ("for", "while", "if", "switch",
	// "do") and how far that was read; it has the first local of its
	// scope, and the parentheses that were open where its header began.
	// A statement that ends ends every frame it was the last part of.
	// Beside them what the simple statement being read is, which
	// collects in the statement above, the parentheses open, braces
	// that could not get a frame, and the parameters of the function
	// whose head was declared last.
	private final int[] frameKind = new int[MAX_NESTING];
	private final int[] frameFirst = new int[MAX_NESTING];
	private final int[] frameParens = new int[MAX_NESTING];
	private int frames;
	private Local local = Local.START;
	private int parens;
	private int untracked;
	private List<Symbol> parameters = Collections.emptyList();

	// The words read last, from one that began a line or a statement
	// on: they may be the head of a declaration that what is being read
	// has to be left for. With them the end of the code before them,
	// their line and the comment above them.
	private GlslStatement head = new GlslStatement();
	private int headEnd;
	private int headLine;
	private String headDocumentation;

	// The comment lines right above the line being read, the line the
	// first of them stands in or -1, and whether the line before left a
	// comment open.
	private final StringBuilder comment = new StringBuilder();
	private int commentFrom = -1;
	private boolean inComment;

	// Conditionals. Which arm is compiled is not decided here: the
	// preprocessor is executed, the one that an exact reading of a
	// program asks too, and it says so. Kept here is what a reader of
	// every arm needs: how many conditionals are open; the level at
	// which "#if 0" switched code off that is then not read at all, or
	// -1; for each level where the reader was at its "#if", all it
	// needs to go on from there again, and where it was at the end of
	// the last arm that may be compiled, if there was one.
	private final GlslPreprocessor preprocessor = new GlslPreprocessor();
	private int level;
	private int disabled = -1;
	private final Checkpoint[] checkpoints = new Checkpoint[MAX_NESTING];
	private final Checkpoint[] ends = new Checkpoint[MAX_NESTING];
	private final boolean[] ended = new boolean[MAX_NESTING];
	// The directive being read as one line, if it goes on over lines,
	// and whether it opened a conditional.
	private StringBuilder directiveLine;
	private LineTokens directiveTokens;
	private boolean opened;
	// Where a macro stops being one: the names "#undef" names in
	// compiled lines, each with where it stands. And the arms that
	// are read and not compiled, from where to where: what they
	// declare is a name for the outline and means nothing outside of
	// them.
	private final List<String> undefined = new ArrayList<>();
	private final List<Integer> undefinedAt = new ArrayList<>();
	private final List<Integer> off = new ArrayList<>();
	private int offFrom = -1;

	// The directive being read, from where to where, and the macro it
	// defines, if any, with its text so far.
	private Directive directive = Directive.NONE;
	private int directiveFrom;
	private int directiveTo;
	private Draft macro;
	private StringBuilder macroText;

	// Regions that fold: pairs of lines, and the lines of open braces.
	private int[] folds = new int[64];
	private int foldCount;
	private final int[] braceLines = new int[MAX_NESTING];

	/**
	 * A reader that keeps at most maxSymbols declarations and maxLocals
	 * locals with maxChars characters; with no locals asked for, bodies
	 * are passed over but for their braces.
	 */
	GlslDeclarations(int maxSymbols, int maxChars, int maxLocals) {
		this.maxSymbols = maxSymbols;
		this.maxChars = maxChars;
		this.maxLocals = maxLocals;
	}

	@Override
	public void addLine(CharSequence line, int lineStart, LineTokens tokens) {
		++lineNumber;
		// What this line declares starts here, and its last character
		// of code: for the comment behind them.
		int first = drafts.size();
		char last = 0;
		int size = tokens.size();
		boolean code = false;
		for (int i = 0; i < size; ++i) {
			Style style = tokens.style(i);
			int start = tokens.start(i);
			int end = tokens.end(i);
			if (style == Style.COMMENT) {
				if (code) {
					first = commentBehind(line, start, end, first, last);
				}
				continue;
			}
			if (called != null) {
				bare(line, lineStart, start, end, tokens.inDirective(i));
			}
			if (tokens.inDirective(i)) {
				directive(line, lineStart, start, end, style);
			} else {
				endDirective();
				if (disabled < 0) {
					last = code(line, lineStart, start, end, style, !code);
				}
			}
			code = true;
		}
		boolean inside = inComment;
		inComment = tokens.commentGoesOn();
		if (code || (size == 0 && !inside)) {
			// Code, or an empty line: the comment above is about
			// something else.
			comment.setLength(0);
			endComments(lineNumber);
		} else {
			// A line of nothing but comment.
			appendComment(comment, line, 0, line.length());
			if (commentFrom < 0) {
				commentFrom = lineNumber;
			}
		}
		execute(line, tokens);
		if (!tokens.directiveGoesOn()) {
			// A directive ends with its line, before the next line
			// declares anything.
			endDirective();
		}
	}

	// The names of the macros with parameters this text defines, null
	// if there is none; and where code has one of those names without
	// a "(" behind it: there it is no macro, whatever is defined.
	private List<String> called;
	private final List<Integer> bare = new ArrayList<>();
	private int maybeBare = -1;

	private void bare(CharSequence line, int lineStart, int start, int end,
			boolean inDirective) {
		if (maybeBare >= 0) {
			if (inDirective || end - start != 1 ||
					line.charAt(start) != '(') {
				bare.add(maybeBare);
			}
			maybeBare = -1;
		}
		if (inDirective) {
			return;
		}
		for (int i = 0, n = called.size(); i < n; ++i) {
			if (is(line, start, end, called.get(i))) {
				maybeBare = lineStart + start;
				return;
			}
		}
	}

	// Gives a directive to the preprocessor when its last line was
	// read: as one line, without the backslashes it went on over.
	private void execute(CharSequence line, LineTokens tokens) {
		boolean directive = false;
		for (int i = 0, n = tokens.size(); i < n && !directive; ++i) {
			directive = tokens.inDirective(i);
		}
		if (!directive) {
			return;
		}
		boolean goesOn = tokens.directiveGoesOn();
		if (directiveLine == null && !goesOn) {
			executed(line, tokens);
			return;
		}
		if (directiveLine == null) {
			directiveLine = new StringBuilder();
			directiveTokens = new LineTokens();
		}
		int shift = directiveLine.length();
		int length = line.length();
		if (goesOn && length > 0 && line.charAt(length - 1) == '\\') {
			--length;
		}
		directiveLine.append(line, 0, length).append(' ');
		for (int i = 0, n = tokens.size(); i < n; ++i) {
			if (tokens.inDirective(i) && tokens.start(i) < length) {
				directiveTokens.add(shift + tokens.start(i), shift +
						Math.min(length, tokens.end(i)), tokens.style(i),
						true);
			}
		}
		if (!goesOn) {
			executed(directiveLine, directiveTokens);
			directiveLine = null;
			directiveTokens = null;
		}
	}

	private void executed(CharSequence line, LineTokens tokens) {
		preprocessor.directive(line, tokens, lineNumber, 0,
				(token, role, state, macro, maybe) -> {
				});
		if (opened && disabled < 0) {
			// "#if 0" is how code is put away: it is not read at all.
			GlslPreprocessor.Conditional c = preprocessor.innermost();
			if (c != null && "#if 0".equals(c.written)) {
				disabled = level;
			}
		}
		opened = false;
		if (isOff() && offFrom < 0) {
			offFrom = directiveTo;
		} else if (!isOff() && offFrom >= 0) {
			off.add(offFrom);
			off.add(directiveFrom);
			offFrom = -1;
		}
	}

	@Override
	public GlslKnowledge build() {
		endDirective();
		// A text that ends inside of something did not say all of it.
		unsure |= mode != Mode.TOP || !statement.isEmpty() || level > 0;
		if (mode == Mode.BODY) {
			leaveBody(lastEnd);
		}
		if (!statement.isEmpty()) {
			declare(statement.size(), lastEnd);
		}
		endComments(lineNumber + 1);
		// A macro is added when its line ends, which may be after a
		// declaration that began above it: in the order they start.
		Collections.sort(drafts, (a, b) -> Integer.compare(a.from, b.from));
		List<Symbol> symbols = new ArrayList<>(drafts.size());
		for (Draft draft : drafts) {
			symbols.add(draft.symbol());
		}
		locals.freeze();
		if (offFrom >= 0) {
			off.add(offFrom);
			off.add(Integer.MAX_VALUE);
			offFrom = -1;
		}
		if (maybeBare >= 0) {
			bare.add(maybeBare);
			maybeBare = -1;
		}
		return new GlslKnowledge(symbols, links, sortedFolds(), locals,
				!full && !cut && !unsure, undefined, undefinedAt, off, bare);
	}

	// ---- Comments -------------------------------------------------------

	// The comment right above the line being read, or null.
	private String commentAbove() {
		return comment.length() > 0 ? comment.toString() : null;
	}

	private void endComments(int line) {
		if (commentFrom >= 0) {
			fold(commentFrom, line - 1);
			commentFrom = -1;
		}
	}

	// A comment behind a declaration that ends on its line documents
	// it, unless a comment above does: the macro being read, or what
	// the line declared from draft first on if its code ends in last.
	// Returns the draft the next comment of the line starts from.
	private int commentBehind(CharSequence line, int start, int end,
			int first, char last) {
		boolean behindMacro = macro != null && macro.documentation == null;
		if (!behindMacro && last != ';' && last != '}') {
			return first;
		}
		StringBuilder text = new StringBuilder();
		appendComment(text, line, start, end);
		if (text.length() == 0) {
			return first;
		}
		String documentation = text.toString();
		if (behindMacro) {
			macro.documentation = documentation;
			return first;
		}
		for (int i = first; i < drafts.size(); ++i) {
			Draft draft = drafts.get(i);
			if (draft.documentation == null &&
					documentation.length() <= room()) {
				draft.documentation = documentation;
				chars += documentation.length();
			}
		}
		return drafts.size();
	}

	// Appends the text of a comment, or of a line of one, without its
	// marks; lines are joined by a space.
	private static void appendComment(StringBuilder out, CharSequence line,
			int start, int end) {
		while (start < end && line.charAt(start) <= ' ') {
			++start;
		}
		if (end - start >= 2 && line.charAt(start) == '/' &&
				(line.charAt(start + 1) == '/' ||
						line.charAt(start + 1) == '*')) {
			start += 2;
		}
		while (start < end && (line.charAt(start) == '*' ||
				line.charAt(start) == '/' || line.charAt(start) == '!')) {
			// "/**", "///", "//!" and the "*" that leads a line.
			++start;
		}
		if (end - start >= 2 && line.charAt(end - 1) == '/' &&
				line.charAt(end - 2) == '*') {
			end -= 2;
		} else if (end > start && line.charAt(end - 1) == '\\') {
			--end;
		}
		while (start < end && line.charAt(start) <= ' ') {
			++start;
		}
		while (end > start && line.charAt(end - 1) <= ' ') {
			--end;
		}
		if (start < end && out.length() < MAX_DOCUMENTATION) {
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(line, start, Math.min(end, start + MAX_DOCUMENTATION -
					out.length()));
		}
	}

	// ---- Directives -----------------------------------------------------

	// A token of a directive: a "#" starts one, any other token is what
	// the one being read expected or not.
	private void directive(CharSequence line, int lineStart, int start,
			int end, Style style) {
		if (directive == Directive.NONE || (style == Style.DIRECTIVE &&
				line.charAt(start) == '#')) {
			endDirective();
			directive = Directive.COMMAND;
			directiveFrom = lineStart + start;
		} else {
			directive = directiveWord(line, lineStart, start, end, style);
		}
		directiveTo = lineStart + end;
	}

	// Reads a token of the directive and returns what it expects next.
	private Directive directiveWord(CharSequence line, int lineStart,
			int start, int end, Style style) {
		switch (directive) {
			case COMMAND:
				return command(line, start, end);
			case DEFINE:
				return define(line, lineStart, start, end, style);
			case MACRO:
			case PARAMETERS:
			case TEXT:
				return macroToken(line, lineStart, start, end, style);
			case UNDEF:
				// From here on no macro has that name, if the line is
				// compiled.
				if (disabled < 0 && !isOff()) {
					undefined.add(line.subSequence(start, end).toString());
					undefinedAt.add(lineStart + start);
				}
				return Directive.OTHER;
			case INCLUDE:
				if (style == Style.STRING) {
					link(Kind.INCLUDE, "#include", line, lineStart, start, end);
				}
				return Directive.OTHER;
			case PRAGMA:
				return is(line, start, end, "se") ? Directive.HOST
						: Directive.OTHER;
			case HOST:
				return is(line, start, end, "input") ? Directive.HOST_INPUT
						: Directive.HOST_REST;
			case HOST_INPUT:
			case HOST_REST:
				// What names a file for the host: the source of an
				// input, and the first picture of a target.
				if (style == Style.STRING &&
						(directive == Directive.HOST_INPUT ||
								endsWith(line, start, "initial="))) {
					link(Kind.FILE, "#pragma se", line, lineStart, start, end);
				}
				return Directive.HOST_REST;
			default:
				return Directive.OTHER;
		}
	}

	private Directive command(CharSequence line, int start, int end) {
		if (is(line, start, end, "define")) {
			return Directive.DEFINE;
		} else if (is(line, start, end, "if") ||
				is(line, start, end, "ifdef") ||
				is(line, start, end, "ifndef")) {
			if (level < MAX_NESTING) {
				keep(level);
				ended[level] = false;
			} else {
				unsure = true;
			}
			++level;
			opened = true;
		} else if (is(line, start, end, "elif") ||
				is(line, start, end, "else")) {
			if (disabled == level) {
				disabled = -1;
			} else if (disabled < 0) {
				arm();
			}
			if (disabled < 0) {
				branch();
			}
		} else if (is(line, start, end, "endif")) {
			if (disabled == level) {
				disabled = -1;
			} else if (disabled < 0) {
				arm();
				if (isOff() && level > 0 && level <= MAX_NESTING) {
					// The last arm is not compiled: the code goes on
					// from the end of the last one that may be, or
					// from the "#if" if there is none.
					Checkpoint first = checkpoints[level - 1];
					Checkpoint last = ends[level - 1];
					if (!ended[level - 1]) {
						branch();
					} else if (last.statementSize == first.statementSize) {
						// What that arm declared and left open was
						// closed where the next one began.
						locals.reopen(last.open);
						restore(last);
					}
				}
			}
			if (level > 0) {
				--level;
				if (level < MAX_NESTING) {
					fold(checkpoints[level].line, lineNumber);
				}
			}
		} else if (is(line, start, end, "include")) {
			return Directive.INCLUDE;
		} else if (is(line, start, end, "undef")) {
			return Directive.UNDEF;
		} else if (is(line, start, end, "pragma")) {
			return Directive.PRAGMA;
		}
		return Directive.OTHER;
	}

	// The name behind "#define" starts a macro.
	private Directive define(CharSequence line, int lineStart, int start,
			int end, Style style) {
		if (disabled >= 0 || !isWord(line, start, style)) {
			return Directive.OTHER;
		}
		macro = new Draft();
		macro.name = line.subSequence(start, end).toString();
		macro.kind = Kind.MACRO;
		macro.start = lineStart + start;
		macro.end = lineStart + end;
		macro.from = directiveFrom;
		macro.documentation = commentAbove();
		macroText = new StringBuilder("#define ").append(macro.name);
		return Directive.MACRO;
	}

	// A token behind the name of a macro: its parameters, if a "("
	// stands right behind the name, and its text.
	private Directive macroToken(CharSequence line, int lineStart, int start,
			int end, Style style) {
		int offset = lineStart + start;
		char c = line.charAt(start);
		boolean space = offset > directiveTo;
		Directive next = Directive.TEXT;
		if (directive == Directive.MACRO) {
			if (c == '(' && offset == macro.end) {
				macro.parts = new ArrayList<>();
				next = Directive.PARAMETERS;
				if (called == null) {
					called = new ArrayList<>();
				}
				if (!called.contains(macro.name)) {
					called.add(macro.name);
				}
			} else {
				space = true;
			}
		} else if (directive == Directive.PARAMETERS && c != ')') {
			next = Directive.PARAMETERS;
			if (isWord(line, start, style)) {
				String name = line.subSequence(start, end).toString();
				macro.parts.add(new Symbol(name, Kind.PARAMETER, name,
						null, null, offset, lineStart + end, offset,
						lineStart + end, Collections.emptyList()));
			}
		}
		if (macroText.length() < GlslSymbols.MAX_DETAIL) {
			if (space) {
				macroText.append(' ');
			}
			macroText.append(line, start, Math.min(end, start +
					GlslSymbols.MAX_DETAIL - macroText.length()));
		}
		return next;
	}

	private void endDirective() {
		if (directive == Directive.NONE) {
			return;
		}
		directive = Directive.NONE;
		if (macro != null) {
			macro.detail = macroText.toString();
			macro.to = directiveTo;
			macro.parts = Collections.unmodifiableList(macro.parts);
			add(macro);
			macro = null;
			macroText = null;
		}
	}

	// A file the text names: the string from start to end, quotes and
	// all, of a directive.
	private void link(Kind kind, String detail, CharSequence line,
			int lineStart, int start, int end) {
		int from = start + 1;
		int to = end > from && line.charAt(end - 1) == '"' ? end - 1 : end;
		if (to <= from || links.size() >= maxSymbols) {
			return;
		}
		links.add(new Symbol(line.subSequence(from, to).toString(), kind,
				detail, null, null, lineStart + from, lineStart + to,
				directiveFrom, lineStart + end, Collections.emptyList()));
	}

	// Whether the arm being read is one the preprocessor decided off.
	private boolean isOff() {
		return preprocessor.state() == GlslPreprocessor.INACTIVE;
	}

	// An arm ends: if it may be compiled, where the reader is now is
	// where the code goes on if the arms behind it are not.
	private void arm() {
		int at = level - 1;
		if (at < 0 || at >= MAX_NESTING || isOff()) {
			return;
		}
		if (ends[at] == null) {
			ends[at] = new Checkpoint();
		}
		keep(ends[at]);
		ends[at].open = locals.open(checkpoints[at].locals);
		ended[at] = true;
	}

	// Keeps where the reader of code is, for the conditional of level.
	private void keep(int level) {
		if (checkpoints[level] == null) {
			checkpoints[level] = new Checkpoint();
		}
		keep(checkpoints[level]);
	}

	private void keep(Checkpoint c) {
		c.line = lineNumber;
		c.mode = mode;
		c.braces = braces;
		c.nesting = nesting;
		c.initializer = initializer;
		c.documentation = documentation;
		c.function = function;
		c.statementSize = statement.size();
		c.local = local;
		c.parens = parens;
		c.untracked = untracked;
		c.locals = locals.size();
		c.frames = frames;
		System.arraycopy(frameKind, 0, c.frameKind, 0, frames);
		System.arraycopy(frameFirst, 0, c.frameFirst, 0, frames);
		System.arraycopy(frameParens, 0, c.frameParens, 0, frames);
	}

	// Another branch of a conditional starts: the reader goes on from
	// where it was at its "#if", in every mode alike, so that a head
	// written once per branch with one body counts its braces once, and
	// a statement that the branches finish differently is finished by
	// each. What the branch before declared in scopes that are still
	// open is seen no further than here.
	private void branch() {
		int at = level - 1;
		if (at < 0 || at >= MAX_NESTING) {
			return;
		}
		restore(checkpoints[at]);
	}

	// Puts the reader of code back where it was when c was kept.
	private void restore(Checkpoint c) {
		head.clear();
		if (c.mode == Mode.TOP && c.statementSize == 0 &&
				mode != Mode.BODY && !statement.isEmpty()) {
			// A declaration the branch began and did not end ends with
			// the branch: what it says so far is declared.
			unsure = true;
			declare(statement.size(), lastEnd);
		}
		if (function != null && function != c.function) {
			// A body the branch opened ends with it.
			function.to = Math.max(function.end, lastEnd);
		}
		locals.close(c.locals, lastEnd);
		mode = c.mode;
		braces = c.braces;
		nesting = c.nesting;
		initializer = c.initializer;
		documentation = c.documentation;
		function = c.function;
		statement.truncate(c.statementSize);
		local = c.local;
		parens = c.parens;
		untracked = c.untracked;
		frames = c.frames;
		System.arraycopy(c.frameKind, 0, frameKind, 0, frames);
		System.arraycopy(c.frameFirst, 0, frameFirst, 0, frames);
		System.arraycopy(c.frameParens, 0, frameParens, 0, frames);
	}

	// ---- Code -----------------------------------------------------------

	// Reads a token of code and returns its character if it is
	// punctuation, else 0.
	private char code(CharSequence line, int lineStart, int start, int end,
			Style style, boolean lineFirst) {
		Token kind = Token.of(style, line, start, end);
		char c = kind == Token.PUNCT ? line.charAt(start) : 0;
		boolean word = kind.keyword || kind == Token.TYPE ||
				kind == Token.NAME;
		if (word) {
			word(line, lineStart, start, end, kind, lineFirst);
		} else {
			if (c == '(' ? startsFunction()
					: (c == '=' || c == ';') && startsVariable()) {
				recover();
			}
			head.clear();
		}
		if (mode == Mode.BODY) {
			if (maxLocals > 0) {
				local(line, lineStart, start, end, kind, c, word);
			}
			body(c, lineStart + end);
		} else {
			top(line, lineStart, start, end, kind, c, word, lineFirst);
		}
		lastEnd = lineStart + end;
		boundary = c == ';' || c == '{' || c == '}';
		return c;
	}

	// ---- Leaving what cannot go on --------------------------------------

	// A word: one that begins a statement or a line begins the words
	// that may be the head of a declaration, others go on with them.
	private void word(CharSequence line, int lineStart, int start, int end,
			Token kind, boolean lineFirst) {
		if (boundary || (lineFirst && (head.isEmpty() ||
				head.endsBefore(kind)))) {
			head.clear();
			headEnd = lastEnd;
			headLine = lineNumber;
			headDocumentation = commentAbove();
			if ((kind == Token.UNIFORM || (kind == Token.QUALIFIER &&
					(is(line, start, end, "varying") ||
							is(line, start, end, "attribute")))) &&
					cannotGoOn(true)) {
				// What only stands outside of everything.
				recover();
			}
		} else if (head.isEmpty() || head.size() >= MAX_HEAD_WORDS) {
			// Not from a start on, or more words than a head has.
			head.clear();
			return;
		}
		head.add(line, start, end, lineStart + start, kind);
	}

	// Whether the words read last, before a "(", are the head of a
	// function that what is being read has to be left for.
	private boolean startsFunction() {
		int size = head.size();
		return size > 1 && head.name(0, size) == size - 1 &&
				cannotGoOn(head.kind(size - 2) == Token.TYPE ||
						structs.contains(head.text(size - 2)));
	}

	// Whether the words read last, before a "=" or a ";", are a type
	// and a name that began a line inside the brackets of a declaration
	// that did not end: no parameter and no argument goes on like that,
	// so the brackets lost their closing ones.
	private boolean startsVariable() {
		int size = head.size();
		return mode == Mode.TOP && nesting > 0 && size > 1 &&
				head.name(0, size) == size - 1 &&
				cannotGoOn(head.kind(size - 2) == Token.TYPE ||
						structs.contains(head.text(size - 2)));
	}

	/**
	 * Tell whether what is being read cannot go on with the declaration
	 * the words read last start, so that it is left for it. The fields
	 * of a struct hold no function and no uniform. A body and a
	 * declaration that did not end hold neither as well, but a word the
	 * reader does not know before a name and a "(" may be a macro there:
	 * typed says that the type is one.
	 *
	 * A type and a name alone never count: they are a parameter, a
	 * field or a local just as well.
	 */
	private boolean cannotGoOn(boolean typed) {
		switch (mode) {
			case FIELDS:
				return true;
			case BODY:
				return typed;
			default:
				// A declaration that did not end has more before those
				// words than words, which would be their qualifiers.
				int before = statement.size();
				if (!head.isEmpty()) {
					int from = head.start(0);
					while (before > 0 &&
							statement.start(before - 1) >= from) {
						--before;
					}
				}
				return typed && before > 0 &&
						statement.afterWords(0, before) < before;
		}
	}

	// Leaves what is being read: it ends before the words read last,
	// and they are the statement that follows. The one way out of what
	// lost its closing brace or bracket.
	private void recover() {
		unsure = true;
		if (mode == Mode.BODY) {
			fold(braceLines[0], headLine - 1);
			leaveBody(headEnd);
		} else {
			int before = statement.size();
			if (!head.isEmpty()) {
				int from = head.start(0);
				while (before > 0 && statement.start(before - 1) >= from) {
					--before;
				}
			}
			declare(before, headEnd);
			mode = Mode.TOP;
		}
		GlslStatement words = head;
		head = statement;
		statement = words;
		head.clear();
		initializer = false;
		documentation = headDocumentation;
	}

	// ---- Outside of bodies ----------------------------------------------

	// A token outside of bodies: part of the declaration being read.
	private void top(CharSequence line, int lineStart, int start, int end,
			Token kind, char c, boolean word, boolean lineFirst) {
		int offset = lineStart + start;
		if (mode == Mode.FIELDS) {
			if (c == '{') {
				++braces;
			} else if (c == '}' && --braces == 0) {
				mode = Mode.TOP;
				fold(braceLines[0], lineNumber);
			}
			unsure |= !statement.add(line, start, end, offset, kind);
			return;
		}
		if (!statement.isEmpty() && nesting == 0 && lineFirst &&
				statement.endsBefore(kind)) {
			// It lost its ";".
			unsure = true;
			declare(statement.size(), lastEnd);
		}
		if (statement.isEmpty()) {
			if (!word && c != '{') {
				// A statement starts with a word; a ";" alone is an
				// empty one, anything else is astray.
				unsure |= c != ';';
				return;
			}
			documentation = commentAbove();
			initializer = false;
		}
		if (c == '{' && (nesting == 0 || !initializer)) {
			// No "{" stands in brackets but in an initializer: they
			// lost their closing ones.
			unsure |= nesting > 0;
			nesting = 0;
			openBrace(line, start, end, offset);
			return;
		}
		boolean ends = nesting == 0 && (c == ';' || c == '}');
		if (!ends && !statement.add(line, start, end, offset, kind)) {
			// Inside brackets a token that is not kept declares
			// nothing: a table of numbers may be as long as it likes.
			unsure |= nesting == 0;
		}
		if (c == '(' || c == '[' || c == '{') {
			++nesting;
		} else if (c == ')' || c == ']' || (c == '}' && nesting > 0)) {
			nesting = Math.max(0, nesting - 1);
		} else if (nesting > 0) {
			// Nothing ends a declaration inside brackets.
			return;
		} else if (c == '=') {
			initializer = true;
		} else if (c == ',') {
			initializer = false;
		} else if (c == ';') {
			declare(statement.size(), lineStart + end);
		} else if (c == '}') {
			// A "}" that nothing opened ends what stood before it.
			unsure = true;
			declare(statement.size(), offset);
		}
	}

	// A "{" outside of brackets: the body of a function, the fields of
	// a struct or a block, braces of an initializer, or a block that
	// belongs to nothing.
	private void openBrace(CharSequence line, int start, int end,
			int offset) {
		braceLines[0] = lineNumber;
		if (initializer) {
			statement.add(line, start, end, offset, Token.PUNCT);
			++nesting;
			return;
		}
		int size = statement.size();
		if (statement.functionName(size) >= 0) {
			function = declare(size, offset);
		} else if (size > 0 && (statement.has(Token.STRUCT) ||
				statement.kind(size - 1) == Token.NAME)) {
			statement.add(line, start, end, offset, Token.PUNCT);
			mode = Mode.FIELDS;
			braces = 1;
			return;
		} else {
			// A block that belongs to nothing.
			unsure = true;
			function = null;
			parameters = Collections.emptyList();
			statement.clear();
		}
		mode = Mode.BODY;
		braces = 1;
		if (maxLocals > 0) {
			// The body is a block, and the parameters are its first
			// locals: as the head declared them, whatever the budget
			// left of them in the symbol.
			frames = 0;
			untracked = 0;
			parens = 0;
			local = Local.START;
			push(BLOCK, SUBSTATEMENT);
			for (int i = 0, n = parameters.size(); i < n; ++i) {
				Symbol parameter = parameters.get(i);
				if (!parameter.name().isEmpty()) {
					addLocal(parameter.name(), Kind.PARAMETER,
							parameter.type(), parameter.start(),
							parameter.end(), offset);
				}
			}
		}
	}

	// Declares what the first size tokens of the statement say, a
	// declaration that ends at offset end of the source, and forgets
	// them. Returns the first symbol declared, or null.
	private Draft declare(int size, int end) {
		List<Draft> declared = new ArrayList<>();
		GlslSymbols.of(statement, size, end, documentation, declared);
		// Code outside of functions that declares nothing is astray,
		// unless it only sets a precision or a layout.
		unsure |= declared.isEmpty() && size > 0 &&
				!statement.isOnlyQualifiers(Math.min(size, statement.size()));
		parameters = declared.isEmpty() ||
				declared.get(0).kind != Kind.FUNCTION
				? Collections.emptyList() : declared.get(0).parts;
		for (int i = 0, n = declared.size(); i < n; ++i) {
			Draft draft = declared.get(i);
			// More parameters or fields than a symbol keeps.
			unsure |= draft.cut;
			if (add(draft) && draft.kind == Kind.TYPE) {
				structs.add(draft.name);
			}
		}
		statement.drop(size);
		nesting = 0;
		initializer = false;
		documentation = null;
		return declared.isEmpty() ? null : declared.get(0);
	}

	// ---- Bodies ---------------------------------------------------------

	// A token of a body, for its locals: braces and statements with a
	// keyword are frames, and a simple statement that starts as a
	// declaration does is collected and declares its locals when it
	// ends.
	private void local(CharSequence line, int lineStart, int start, int end,
			Token kind, char c, boolean word) {
		int offset = lineStart + start;
		int keyword = kind != Token.OTHER ? -1 : keyword(line, start, end);
		// An "if" whose statement ended is over unless "else" follows;
		// a "do" whose statement ended goes on with "while".
		while (frames > 0 && stage() == BEFORE_ELSE &&
				keyword != ELSE_WORD) {
			pop(lastEnd);
			finish(lastEnd);
		}
		if (frames > 0 && stage() == BEFORE_WHILE) {
			if (keyword == WHILE) {
				stage(KEYWORD);
				return;
			}
			unsure = true;
			pop(lastEnd);
			finish(lastEnd);
		}
		if (keyword == ELSE_WORD) {
			if (frames > 0 && stage() == BEFORE_ELSE) {
				stage(ELSE);
				frameFirst[frames - 1] = locals.size();
			} else {
				unsure = true;
			}
			return;
		} else if (keyword > 0) {
			// A statement with a keyword; what a "do" repeats follows
			// at once.
			endLocal(offset, false);
			push(keyword, keyword == DO ? SUBSTATEMENT : KEYWORD);
			return;
		}
		boolean header = frames > 0 && stage() == HEADER;
		if (c == '(') {
			if (frames > 0 && stage() == KEYWORD) {
				// The header: what it declares is seen to the end of
				// the whole statement.
				frameParens[frames - 1] = parens++;
				frameFirst[frames - 1] = locals.size();
				stage(HEADER);
				local = Local.START;
				return;
			}
			++parens;
		} else if (c == ')') {
			parens = Math.max(0, parens - 1);
			if (header && parens == frameParens[frames - 1]) {
				endLocal(offset, true);
				if (kind() == DO) {
					stage(BEFORE_SEMICOLON);
				} else {
					stage(SUBSTATEMENT);
					if (kind() == IF || kind() == SWITCH) {
						frameFirst[frames - 1] = locals.size();
					}
				}
				return;
			}
		} else if (c == '{' && !(local == Local.DECLARATION &&
				(nesting > 0 || statement.is(statement.size() - 1, '=')))) {
			// A block. (In a declaration, behind "=" or inside one, it
			// is the brace of an initializer.)
			unsure |= parens > 0 || (frames > 0 && stage() == KEYWORD);
			endLocal(offset, false);
			push(BLOCK, SUBSTATEMENT);
			parens = 0;
			return;
		} else if (c == '}' && !(local == Local.DECLARATION &&
				nesting > 0)) {
			endLocal(offset, false);
			if (untracked > 0) {
				--untracked;
				return;
			}
			// Statements that did not end before it end with it.
			while (frames > 0 && kind() != BLOCK) {
				unsure = true;
				pop(offset);
			}
			if (frames > 0) {
				parens = frameParens[frames - 1];
				pop(lineStart + end);
				finish(lineStart + end);
			}
			return;
		} else if (c == ';') {
			if (header && kind() == FOR &&
					parens == frameParens[frames - 1] + 1) {
				// Between the parts of the header of a "for".
				endLocal(lineStart + end, true);
				return;
			}
			// No ";" stands inside parentheses but there.
			unsure |= parens > 0;
			endLocal(lineStart + end, true);
			if (frames > 0 && stage() == BEFORE_SEMICOLON) {
				pop(lineStart + end);
			}
			finish(lineStart + end);
			return;
		}
		switch (local) {
			case START:
				if (word) {
					// A struct declared in a function is not read.
					unsure |= kind == Token.STRUCT;
					unsure |= !statement.add(line, start, end, offset,
							kind);
					local = Local.WORDS;
					nesting = 0;
				} else {
					local = Local.OTHER;
				}
				break;
			case WORDS:
				if (c == '[') {
					++nesting;
				} else if (c == ']' && nesting > 0) {
					--nesting;
				} else if (!word && nesting == 0) {
					// What stands behind the words tells.
					if ((c == '=' || c == ',') && statement.name(0,
							statement.size()) >= 0) {
						local = Local.DECLARATION;
					} else {
						local = Local.OTHER;
						statement.clear();
						break;
					}
				}
				unsure |= !statement.add(line, start, end, offset, kind);
				break;
			case DECLARATION:
				if (!statement.add(line, start, end, offset, kind)) {
					unsure |= nesting == 0;
				}
				if (c == '(' || c == '[' || c == '{') {
					++nesting;
				} else if (c == ')' || c == ']' || c == '}') {
					nesting = Math.max(0, nesting - 1);
				}
				break;
			default:
				if (c == ':') {
					// Behind a label a statement starts.
					local = Local.START;
				}
				break;
		}
	}

	// Which statement the word from start to end of line begins, as
	// the kind of its frame; ELSE_WORD for "else"; -1 for any other.
	private static int keyword(CharSequence line, int start, int end) {
		switch (end - start) {
			case 2:
				return is(line, start, end, "if") ? IF
						: is(line, start, end, "do") ? DO : -1;
			case 3:
				return is(line, start, end, "for") ? FOR : -1;
			case 4:
				return is(line, start, end, "else") ? ELSE_WORD : -1;
			case 5:
				return is(line, start, end, "while") ? WHILE : -1;
			case 6:
				return is(line, start, end, "switch") ? SWITCH : -1;
			default:
				return -1;
		}
	}

	private int kind() {
		return frameKind[frames - 1] / STAGES;
	}

	private int stage() {
		return frameKind[frames - 1] % STAGES;
	}

	private void stage(int stage) {
		frameKind[frames - 1] = kind() * STAGES + stage;
	}

	// A statement begins that is not finished at once. One nested
	// deeper than is tracked gets no frame, and its braces are counted.
	private void push(int kind, int stage) {
		if (frames == MAX_NESTING) {
			unsure = true;
			untracked += kind == BLOCK ? 1 : 0;
			return;
		}
		frameKind[frames] = kind * STAGES + stage;
		frameFirst[frames] = locals.size();
		frameParens[frames] = parens;
		++frames;
	}

	// The innermost frame ends at offset, and with it what was
	// declared in its scope.
	private void pop(int offset) {
		--frames;
		locals.close(frameFirst[frames], offset);
	}

	// A statement ended at offset: it ends every statement it was the
	// last part of. An "if" waits for an "else", a "do" for its
	// "while".
	private void finish(int offset) {
		while (frames > 0) {
			int stage = stage();
			if (stage != SUBSTATEMENT && stage != ELSE) {
				return;
			} else if (kind() == BLOCK) {
				return;
			} else if (kind() == IF && stage == SUBSTATEMENT) {
				locals.close(frameFirst[frames - 1], offset);
				stage(BEFORE_ELSE);
				return;
			} else if (kind() == DO) {
				locals.close(frameFirst[frames - 1], offset);
				stage(BEFORE_WHILE);
				return;
			}
			pop(offset);
		}
	}

	// The simple statement of a body ends at offset end, by right or
	// because something else begins: it declares what it says, if it
	// is a declaration.
	private void endLocal(int end, boolean byRight) {
		boolean declares = local == Local.DECLARATION ||
				(local == Local.WORDS && statement.name(0,
						statement.size()) >= 0);
		unsure |= !byRight && (declares || local == Local.WORDS);
		if (declares) {
			List<Draft> declared = new ArrayList<>();
			GlslSymbols.of(statement, statement.size(), end, null, false,
					declared);
			for (int i = 0, n = declared.size(); i < n; ++i) {
				Draft draft = declared.get(i);
				if (draft.kind != Kind.TYPE) {
					addLocal(draft.name, draft.kind, draft.type, draft.start,
							draft.end, draft.to);
				}
			}
		}
		statement.clear();
		local = Local.START;
		nesting = 0;
	}

	// Adds a local while there is room; names of declarations come
	// before it, so it takes of what they leave.
	private void addLocal(String name, Kind kind, String type, int start,
			int end, int from) {
		if (cut || locals.size() >= maxLocals || name.length() > room()) {
			cut = true;
			return;
		}
		chars += name.length();
		locals.add(name, kind, type, start, end, from);
	}

	// A token inside the body of a function, which ends at end: only
	// its braces count.
	private void body(char c, int end) {
		if (c == '{') {
			if (braces < MAX_NESTING) {
				braceLines[braces] = lineNumber;
			}
			++braces;
		} else if (c == '}') {
			--braces;
			if (braces < MAX_NESTING) {
				fold(braceLines[braces], lineNumber);
			}
			if (braces == 0) {
				leaveBody(end);
			}
		}
	}

	// The body ends at offset, with its closing brace or without: the
	// one way out of BODY.
	private void leaveBody(int offset) {
		if (function != null) {
			function.to = Math.max(function.end, offset);
		}
		function = null;
		mode = Mode.TOP;
		braces = 0;
		if (maxLocals > 0) {
			// Statements that did not end end with the body.
			unsure |= frames > 1 || local == Local.WORDS ||
					local == Local.DECLARATION;
			locals.close(frames > 0 ? frameFirst[0] : locals.size(), offset);
			frames = 0;
			untracked = 0;
			statement.clear();
			local = Local.START;
			nesting = 0;
		}
	}

	// ---- What was read --------------------------------------------------

	// Adds a symbol if there is room for its name. What it says beside
	// its name it keeps while there is room for that: names come first,
	// since completion needs nothing else.
	private boolean add(Draft draft) {
		if (full || drafts.size() >= maxSymbols ||
				draft.name.length() > maxChars / 4 - nameChars) {
			full = true;
			return false;
		}
		nameChars += draft.name.length();
		int length = draft.detail.length() + (draft.documentation == null
				? 0 : draft.documentation.length());
		for (int i = 0, n = draft.parts.size(); i < n && length >= 0; ++i) {
			Symbol part = draft.parts.get(i);
			length += part.name().length() + part.detail().length();
		}
		if (length < 0 || length > room()) {
			// What it is made of goes with what is shown of it: this is
			// no longer all there is to know.
			unsure = true;
			draft.detail = draft.name;
			draft.documentation = null;
			draft.parts = Collections.emptyList();
		} else {
			chars += length;
		}
		drafts.add(draft);
		return true;
	}

	// How many characters symbols may still say beside their names.
	private int room() {
		return maxChars - maxChars / 4 - chars;
	}

	private void fold(int from, int to) {
		if (to <= from || foldCount >= MAX_FOLDS) {
			return;
		}
		if (foldCount + 2 > folds.length) {
			folds = Arrays.copyOf(folds, folds.length * 2);
		}
		folds[foldCount++] = from;
		folds[foldCount++] = to;
	}

	// The folds in the order they start, the longer first.
	private int[] sortedFolds() {
		long[] keys = new long[foldCount / 2];
		for (int i = 0; i < keys.length; ++i) {
			keys[i] = ((long) folds[i * 2] << 32) |
					(Integer.MAX_VALUE - folds[i * 2 + 1]);
		}
		Arrays.sort(keys);
		int[] sorted = new int[foldCount];
		for (int i = 0; i < keys.length; ++i) {
			sorted[i * 2] = (int) (keys[i] >>> 32);
			sorted[i * 2 + 1] = Integer.MAX_VALUE - (int) keys[i];
		}
		return sorted;
	}

	// ---- Text -----------------------------------------------------------

	private static boolean isWord(CharSequence text, int start, Style style) {
		char c = text.charAt(start);
		return (Character.isLetter(c) || c == '_') &&
				style != Style.COMMENT && style != Style.STRING;
	}

	private static boolean is(CharSequence text, int start, int end,
			String word) {
		return GlslStatement.is(text, start, end, word);
	}

	// Whether text has word right before offset end.
	private static boolean endsWith(CharSequence text, int end,
			String word) {
		int start = end - word.length();
		return start >= 0 && is(text, start, end, word);
	}
}
