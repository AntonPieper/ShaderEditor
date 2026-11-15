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
 * each with its range and the comment that belongs to it. Function
 * bodies are not read; only their braces are matched.
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
		/** A "0" behind "#if". */
		IF,
		/** Nothing behind "#if 0", which then disables code. */
		IF_ZERO,
		/** The path, behind "#include". */
		INCLUDE,
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

	// What was read: never more than maxSymbols symbols with maxChars
	// characters of text, of which a quarter is for names alone.
	private final int maxSymbols;
	private final int maxChars;
	private final List<Draft> drafts = new ArrayList<>();
	private final List<Symbol> links = new ArrayList<>();
	private final Set<String> structs = new HashSet<>();
	private int nameChars;
	private int chars;
	private boolean full;

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

	// Conditionals: how many are open, the level "#if 0" disabled code
	// at or -1, and for each level where the reader was at its "#if".
	private int level;
	private int disabled = -1;
	private final int[] ifLine = new int[MAX_NESTING];
	private final Mode[] ifMode = new Mode[MAX_NESTING];
	private final int[] ifBraces = new int[MAX_NESTING];
	private final Draft[] ifFunction = new Draft[MAX_NESTING];

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

	GlslDeclarations(int maxSymbols, int maxChars) {
		this.maxSymbols = maxSymbols;
		this.maxChars = maxChars;
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
		if (!tokens.directiveGoesOn()) {
			// A directive ends with its line, before the next line
			// declares anything.
			endDirective();
		}
	}

	@Override
	public GlslKnowledge build() {
		endDirective();
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
		return new GlslKnowledge(symbols, links, sortedFolds());
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
			case IF:
				return disabled < 0 && is(line, start, end, "0")
						? Directive.IF_ZERO : Directive.OTHER;
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
				ifLine[level] = lineNumber;
				ifMode[level] = mode;
				ifBraces[level] = braces;
				ifFunction[level] = function;
			}
			++level;
			return is(line, start, end, "if") ? Directive.IF
					: Directive.OTHER;
		} else if (is(line, start, end, "elif") ||
				is(line, start, end, "else")) {
			if (disabled == level) {
				disabled = -1;
			}
			if (disabled < 0) {
				branch();
			}
		} else if (is(line, start, end, "endif")) {
			if (disabled == level) {
				disabled = -1;
			}
			if (level > 0) {
				--level;
				if (level < MAX_NESTING) {
					fold(ifLine[level], lineNumber);
				}
			}
		} else if (is(line, start, end, "include")) {
			return Directive.INCLUDE;
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
		if (directive == Directive.IF_ZERO) {
			disabled = level;
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

	// Another branch of a conditional starts: the nesting is what it
	// was at its "#if", so that a head written once per branch with one
	// body counts its braces once.
	private void branch() {
		int at = level - 1;
		if (at < 0 || at >= MAX_NESTING) {
			return;
		}
		head.clear();
		if (ifMode[at] == Mode.TOP && mode == Mode.BODY) {
			leaveBody(lastEnd);
		} else if (ifMode[at] == Mode.BODY && mode != Mode.FIELDS) {
			mode = Mode.BODY;
			function = ifFunction[at];
			braces = ifBraces[at];
			statement.clear();
			nesting = 0;
		}
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
			if (c == '(' && startsFunction()) {
				recover();
			}
			head.clear();
		}
		if (mode == Mode.BODY) {
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
			statement.add(line, start, end, offset, kind);
			return;
		}
		if (!statement.isEmpty() && nesting == 0 && lineFirst &&
				statement.endsBefore(kind)) {
			// It lost its ";".
			declare(statement.size(), lastEnd);
		}
		if (statement.isEmpty()) {
			if (!word && c != '{') {
				// A statement starts with a word.
				return;
			}
			documentation = commentAbove();
			initializer = false;
		}
		if (c == '{' && (nesting == 0 || !initializer)) {
			// No "{" stands in brackets but in an initializer: they
			// lost their closing ones.
			nesting = 0;
			openBrace(line, start, end, offset);
			return;
		}
		boolean ends = nesting == 0 && (c == ';' || c == '}');
		if (!ends) {
			statement.add(line, start, end, offset, kind);
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
			function = null;
			statement.clear();
		}
		mode = Mode.BODY;
		braces = 1;
	}

	// Declares what the first size tokens of the statement say, a
	// declaration that ends at offset end of the source, and forgets
	// them. Returns the first symbol declared, or null.
	private Draft declare(int size, int end) {
		List<Draft> declared = new ArrayList<>();
		GlslSymbols.of(statement, size, end, documentation, declared);
		for (int i = 0, n = declared.size(); i < n; ++i) {
			Draft draft = declared.get(i);
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
