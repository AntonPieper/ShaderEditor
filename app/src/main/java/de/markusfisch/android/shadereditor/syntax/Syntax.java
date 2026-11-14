package de.markusfisch.android.shadereditor.syntax;

import java.util.List;

/**
 * How the editor treats the text of one language: what it colours, what
 * it learns from the whole text, what it completes and how it indents.
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
	 * {@link #lexLine} gives, for its declarations, or null when this
	 * language collects none; the text is then not tokenized for it.
	 */
	Outline.Builder outline(int maxDeclarations, int maxNameChars);

	/**
	 * Offer the completions for the cursor at column of line.
	 *
	 * @param line       the cursor's line as it was tokenized
	 * @param lineStart  the offset of the line in the text
	 * @param declared   the declarations of the last analysis
	 * @param current    whether declared is of the current text
	 * @param extraWords words the host offers besides the language's own
	 */
	void complete(LexedLine line, int column, int lineStart,
			Outline declared, boolean current, List<String> extraWords,
			Offer offer);

	/** Return how a new line is indented. */
	Indentation indentation();
}
