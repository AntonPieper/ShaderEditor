package de.markusfisch.android.shadereditor.editor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * What needs the whole text of a document, made for one revision of it,
 * as the syntax of its language reads it. It is immutable, so it can be
 * made on any thread.
 *
 * It reads the text line by line and keeps neither the text nor its
 * tokens, so its memory does not grow with the text: it keeps at most
 * {@link #MAX_DECLARATIONS} declarations whose names have at most
 * {@link #MAX_NAME_CHARS} characters together, the first in the text,
 * and it leaves the outline empty if a line is longer than
 * {@link #MAX_LINE_LENGTH}, since tokenizing a line takes memory in
 * proportion to its length.
 */
public final class Analysis {
	static final int MAX_DECLARATIONS = 10000;
	static final int MAX_NAME_CHARS = 1 << 17;
	static final int MAX_LINE_LENGTH = 1 << 16;
	private static final Pattern UNIFORM_LINE = Pattern.compile(
			"^([ \t]*uniform.+)$",
			Pattern.MULTILINE);
	private static final Pattern ENDIF = Pattern.compile("(#endif)\\b");
	/** Tells an analysis in progress to give up. */
	interface Cancel {
		/** Called between lines, on the thread that analyses. */
		boolean cancelled();
	}

	/** The analysis of the empty text, for no revision. */
	public static final Analysis NONE = new Analysis(-1, Outline.NONE,
			Collections.emptySet(), -1, -1);

	private final int revision;
	private final Outline outline;
	private final Set<String> typeNames;
	private final int uniformLineEnd;
	private final int endifEnd;

	private Analysis(int revision, Outline outline, Set<String> typeNames,
			int uniformLineEnd, int endifEnd) {
		this.revision = revision;
		this.outline = outline;
		this.typeNames = typeNames;
		this.uniformLineEnd = uniformLineEnd;
		this.endifEnd = endifEnd;
	}

	/**
	 * Return the analysis of text, which is the document at revision,
	 * as syntax reads it. It shares the set of type names of previous,
	 * the analysis before or null, if the names are the same.
	 */
	public static Analysis of(Syntax syntax, Text text, int revision,
			Analysis previous) {
		return of(syntax, text, revision, previous, () -> false);
	}

	/**
	 * Return the analysis like {@link #of(Syntax, Text, int, Analysis)},
	 * or null as soon as cancel tells so at the start of a line.
	 */
	static Analysis of(Syntax syntax, Text text, int revision,
			Analysis previous, Cancel cancel) {
		Outline.Builder builder = syntax.outline(MAX_DECLARATIONS,
				MAX_NAME_CHARS);
		boolean outlined = builder != null &&
				text.longestLineLength() <= MAX_LINE_LENGTH;
		LineTokens tokens = new LineTokens();
		Matcher uniformLine = UNIFORM_LINE.matcher("");
		Matcher endif = ENDIF.matcher("");
		int uniformLineEnd = -1;
		int endifEnd = -1;
		int state = Syntax.TEXT_START;
		for (int n = 0, lines = text.lineCount(); n < lines; ++n) {
			if (cancel.cancelled()) {
				return null;
			}
			String line = text.line(n);
			int lineStart = text.lineStart(n);
			if (outlined) {
				state = syntax.lexLine(line, n < lines - 1, state, tokens);
				builder.addLine(line, lineStart, tokens);
			}
			if (line.contains("uniform")) {
				uniformLineEnd = lastEnd(uniformLine.reset(line), lineStart,
						uniformLineEnd);
			}
			if (line.contains("#endif")) {
				endifEnd = lastEnd(endif.reset(line), lineStart, endifEnd);
			}
		}
		Outline outline = builder != null ? builder.build() : Outline.NONE;
		Set<String> typeNames = new HashSet<>();
		for (Outline.Declaration declaration : outline.declarations()) {
			if (declaration.kind() == Outline.Kind.TYPE) {
				typeNames.add(declaration.name());
			}
		}
		boolean same = previous != null &&
				previous.typeNames.equals(typeNames);
		return new Analysis(revision, outline, same
				? previous.typeNames
				: Collections.unmodifiableSet(typeNames),
				uniformLineEnd, endifEnd);
	}

	/**
	 * Return the end of the last line that starts with "uniform" after
	 * blanks, before its line break, or -1. Add uniform inserts there.
	 */
	public static int uniformLineEnd(CharSequence text) {
		return lastEnd(UNIFORM_LINE.matcher(text), 0, -1);
	}

	/**
	 * Return the offset after the last "#endif", or -1. Add uniform
	 * inserts behind it when there is no uniform line.
	 */
	public static int endifEnd(CharSequence text) {
		return lastEnd(ENDIF.matcher(text), 0, -1);
	}

	public int revision() {
		return revision;
	}

	/** Return the declarations; their names are what completion offers. */
	public Outline outline() {
		return outline;
	}

	/**
	 * Return the names of the declared struct types: the same set as the
	 * analysis before had while the names stay the same.
	 */
	public Set<String> typeNames() {
		return typeNames;
	}

	/** Return {@link #uniformLineEnd(CharSequence)} of the text. */
	public int uniformLineEnd() {
		return uniformLineEnd;
	}

	/** Return {@link #endifEnd(CharSequence)} of the text. */
	public int endifEnd() {
		return endifEnd;
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
