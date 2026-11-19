package de.markusfisch.android.shadereditor.editor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Syntax;

// What needs the whole text of a document, for one revision. Immutable, so it
// can be made on any thread. It reads the text line by line and keeps neither
// the text nor its tokens; what it knows is capped by MAX_SYMBOLS and
// MAX_CHARS, and is nothing if a line is longer than MAX_LINE_LENGTH, since
// tokenizing a line takes memory in proportion to its length.
public final class Analysis {
	static final int MAX_SYMBOLS = 10000;
	static final int MAX_CHARS = 1 << 19;
	static final int MAX_LINE_LENGTH = 1 << 16;
	private static final Pattern UNIFORM_LINE = Pattern.compile(
			"^([ \t]*uniform.+)$",
			Pattern.MULTILINE);
	private static final Pattern ENDIF = Pattern.compile("(#endif)\\b");
	interface Cancel {
		// Called between lines, on the thread that analyses.
		boolean cancelled();
	}

	public static final Analysis NONE = new Analysis(-1, Knowledge.NONE,
			Collections.emptySet());

	private final int revision;
	private final Knowledge knowledge;
	private final Set<String> typeNames;

	private Analysis(int revision, Knowledge knowledge,
			Set<String> typeNames) {
		this.revision = revision;
		this.knowledge = knowledge;
		this.typeNames = typeNames;
	}

	// Shares the type names of previous if they are the same.
	public static Analysis of(Syntax syntax, Text text, int revision,
			Analysis previous) {
		return of(syntax, text, revision, previous, () -> false);
	}

	// Null as soon as cancel says so at the start of a line.
	static Analysis of(Syntax syntax, Text text, int revision,
			Analysis previous, Cancel cancel) {
		Knowledge.Builder builder = syntax.knowledge(MAX_SYMBOLS,
				MAX_CHARS);
		boolean outlined = builder != null &&
				text.longestLineLength() <= MAX_LINE_LENGTH;
		LineTokens tokens = new LineTokens();
		int state = Syntax.TEXT_START;
		for (int n = 0, lines = text.lineCount(); n < lines; ++n) {
			if (cancel.cancelled()) {
				return null;
			}
			String line = text.line(n);
			if (outlined) {
				state = syntax.lexLine(line, n < lines - 1, state, tokens);
				builder.addLine(line, text.lineStart(n), tokens);
			}
		}
		// What could not be read is not known, nor complete.
		Knowledge knowledge = outlined
				? builder.build()
				: Knowledge.NONE;
		Set<String> typeNames = new HashSet<>();
		for (Knowledge.Symbol symbol : knowledge.symbols()) {
			if (symbol.kind() == Knowledge.Kind.TYPE) {
				typeNames.add(symbol.name());
			}
		}
		boolean same = previous != null &&
				previous.typeNames.equals(typeNames);
		return new Analysis(revision, knowledge, same
				? previous.typeNames
				: Collections.unmodifiableSet(typeNames));
	}

	// The end of the last uniform line, before its line break, or -1. Add
	// uniform inserts there.
	public static int uniformLineEnd(CharSequence text) {
		return lastEnd(UNIFORM_LINE.matcher(text), 0, -1);
	}

	// Behind the last #endif, or -1. Add uniform inserts there when there is no
	// uniform line.
	public static int endifEnd(CharSequence text) {
		return lastEnd(ENDIF.matcher(text), 0, -1);
	}

	public int revision() {
		return revision;
	}

	// The names of its symbols are what completion offers.
	public Knowledge knowledge() {
		return knowledge;
	}

	// The same set as the analysis before had while the names stay the same.
	public Set<String> typeNames() {
		return typeNames;
	}

	// Return offset plus the end of the last match of m, or end if there
	// is none.
	private static int lastEnd(Matcher m, int offset, int end) {
		while (m.find()) {
			end = offset + m.end();
		}
		return end;
	}
}
