package de.markusfisch.android.shadereditor.editor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * What needs the whole text of a document, made for one revision of it.
 * It is immutable, so it can be made on any thread.
 */
public final class Analysis {
	private static final Pattern UNIFORM_LINE = Pattern.compile(
			"^([ \t]*uniform.+)$",
			Pattern.MULTILINE);
	private static final Pattern ENDIF = Pattern.compile("(#endif)\\b");
	/** The analysis of the empty text, for no revision. */
	public static final Analysis NONE = of(Text.EMPTY, -1);

	private final int revision;
	private final Tokens tokens;
	private final Outline outline;
	private final Set<String> typeNames;
	private final int uniformLineEnd;
	private final int endifEnd;

	private Analysis(int revision, Tokens tokens, Outline outline,
			Set<String> typeNames, int uniformLineEnd, int endifEnd) {
		this.revision = revision;
		this.tokens = tokens;
		this.outline = outline;
		this.typeNames = typeNames;
		this.uniformLineEnd = uniformLineEnd;
		this.endifEnd = endifEnd;
	}

	/** Return the analysis of text, which is the document at revision. */
	public static Analysis of(Text text, int revision) {
		String string = text.toString();
		Tokens tokens = GlslLexer.lex(string);
		Outline outline = Outline.of(tokens, string);
		Set<String> typeNames = new HashSet<>();
		for (Outline.Declaration declaration : outline.declarations()) {
			if (declaration.kind() == Outline.Kind.TYPE) {
				typeNames.add(declaration.name());
			}
		}
		return new Analysis(revision, outline.colourTypes(tokens, string),
				outline, Collections.unmodifiableSet(typeNames),
				lastEnd(UNIFORM_LINE, string), lastEnd(ENDIF, string));
	}

	/**
	 * Return the end of the last line that starts with "uniform" after
	 * blanks, before its line break, or -1. Add uniform inserts there.
	 */
	public static int uniformLineEnd(CharSequence text) {
		return lastEnd(UNIFORM_LINE, text);
	}

	/**
	 * Return the offset after the last "#endif", or -1. Add uniform
	 * inserts behind it when there is no uniform line.
	 */
	public static int endifEnd(CharSequence text) {
		return lastEnd(ENDIF, text);
	}

	public int revision() {
		return revision;
	}

	/** Return the tokens, with declared type names styled as types. */
	public Tokens tokens() {
		return tokens;
	}

	/** Return the declarations; their names are what completion offers. */
	public Outline outline() {
		return outline;
	}

	/** Return the names of the declared struct types. */
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

	private static int lastEnd(Pattern pattern, CharSequence text) {
		int end = -1;
		for (Matcher m = pattern.matcher(text); m.find(); ) {
			end = m.end();
		}
		return end;
	}
}
