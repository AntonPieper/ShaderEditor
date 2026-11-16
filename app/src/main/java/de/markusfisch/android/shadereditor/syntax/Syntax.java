package de.markusfisch.android.shadereditor.syntax;

import java.util.List;

/**
 * How the editor treats the text of one language: what it colours, what
 * it knows of the whole text, what it completes and how it indents.
 * The editor holds one per open file, chosen from the file's language,
 * and asks nothing of a language but this. An implementation is
 * immutable, so the editor may call it on any thread.
 */
public interface Syntax {
	/** The state the first line of a text starts in. */
	int TEXT_START = 0;

	/** Receives the words completion offers. */
	interface Offer {
		/**
		 * Called with the words to offer and how many characters of the
		 * word before the cursor each repeats.
		 */
		void offer(List<String> words, int position);
	}

	/**
	 * Tokenize one line that starts in state and return the state the
	 * next line starts in, a value from 0 to 255, or {@link #TEXT_START}
	 * if no line break follows. Two states are equal exactly when the
	 * rest of the text would be tokenized the same.
	 *
	 * @param line         a line without its line end
	 * @param breakFollows whether a line break follows the line
	 * @param state        {@link #TEXT_START} or what the previous line
	 *                     returned
	 * @param out          receives the tokens, with offsets into the line
	 */
	int lexLine(CharSequence line, boolean breakFollows, int state,
			LineTokens out);

	/**
	 * Return a builder that reads a text line by line, with the tokens
	 * {@link #lexLine} gives, for what this language knows of it, or
	 * null when it knows nothing of a text; the text is then not
	 * tokenized for it. The knowledge keeps at most maxSymbols symbols
	 * whose texts have at most maxChars characters together, the first
	 * in the text. Names come first: a quarter of the characters is
	 * theirs alone, and a symbol there is no room to say more of keeps
	 * its name.
	 */
	Knowledge.Builder knowledge(int maxSymbols, int maxChars);

	/**
	 * Offer the completions for the cursor at column of line.
	 *
	 * @param line       the cursor's line as it was tokenized
	 * @param lineStart  the offset of the line in the text
	 * @param known      what the last analysis knows of the text
	 * @param current    whether known is of the current text
	 * @param extraWords words the host offers besides the language's own
	 */
	void complete(LexedLine line, int column, int lineStart,
			Knowledge known, boolean current, List<String> extraWords,
			Offer offer);

	/**
	 * As {@link #complete}, for a host that knows what is declared
	 * outside of the text for it: a language that tells types asks it
	 * what a name of another file is.
	 */
	default void complete(LexedLine line, int column, int lineStart,
			Knowledge known, boolean current, List<String> extraWords,
			Knowledge.Scope outside, Offer offer) {
		complete(line, column, lineStart, known, current, extraWords, offer);
	}

	/** Return how a new line is indented. */
	Indentation indentation();

	/** The lines of a text as they are now, tokenized when asked for. */
	interface Lines {
		LexedLine line(int n);
	}

	/**
	 * A call being written: the name called, the line it stands in and
	 * where in it, and which argument the caret is in, from 0.
	 */
	record Call(String name, int line, int start, int argument) {
	}

	/**
	 * Return the call the caret at column of line is an argument of,
	 * from the tokens before it alone, or null; a language without
	 * calls has none.
	 */
	default Call call(Lines lines, int line, int column) {
		return null;
	}

	/**
	 * Return the types of the arguments of call that are written before
	 * the caret at column of line, in their order and as the language
	 * names types; null for one whose type cannot be told. A language
	 * without types has none. It reads no more than {@link #call} did.
	 *
	 * @param lineStart the offset in the text of the line the name
	 *                  called stands in
	 * @param known     what the last analysis knows of the text
	 * @param current   whether known is of the current text
	 * @param outside   what is declared outside of the text for it
	 */
	default List<String> argumentTypes(Lines lines, Call call, int line,
			int column, int lineStart, Knowledge known, boolean current,
			Knowledge.Scope outside) {
		return java.util.Collections.emptyList();
	}
}
