package de.markusfisch.android.shadereditor.syntax;

import java.util.Collections;
import java.util.List;

/**
 * What a language knows about one version of one text, in words no
 * language owns. The syntax of a file makes one off the main thread, see
 * {@link Syntax#knowledge}; the editor then asks it about the text it
 * was made of. It never changes, so it may be asked on any thread, and
 * its offsets are UTF-16 offsets into that text.
 *
 * A language answers what it can. One that knows nothing has none, and
 * the editor holds {@link #NONE}.
 */
public interface Knowledge {
	/** What a symbol is. */
	enum Kind {
		FUNCTION,
		VARIABLE,
		UNIFORM,
		CONSTANT,
		MACRO,
		TYPE,
		PARAMETER,
		FIELD,
		/** A file whose text is part of this one. */
		INCLUDE,
		/** Another file the text names. */
		FILE
	}

	/**
	 * Something with a name: a declaration, a parameter or field of
	 * one, or a file the text names.
	 *
	 * @param detail        how the language writes it: a signature, a
	 *                      type and a name, the line of a macro; for a
	 *                      file what names it. Never null
	 * @param documentation the comment that belongs to it, or null
	 * @param file          null for a symbol of this text; whoever hands
	 *                      a symbol to another file names its file here
	 * @param start         where its name stands, for a file its path
	 * @param end           the end of the name
	 * @param from          where the whole declaration starts
	 * @param to            where the whole declaration ends
	 * @param parts         the parameters of a function or a macro, the
	 *                      fields of a type, in their order; a parameter
	 *                      without a name has an empty one
	 * @param type          what the language calls its type, whole and
	 *                      without qualifiers: of a variable, a parameter
	 *                      or a field its own, of a function what it
	 *                      returns; null where there is none to tell
	 */
	record Symbol(String name, Kind kind, String detail,
			String documentation, String file, int start, int end, int from,
			int to, List<Symbol> parts, String type) {
		/** A symbol whose type is not told. */
		public Symbol(String name, Kind kind, String detail,
				String documentation, String file, int start, int end,
				int from, int to, List<Symbol> parts) {
			this(name, kind, detail, documentation, file, start, end, from,
					to, parts, null);
		}

		/** Return this symbol as one of file. */
		public Symbol in(String file) {
			return new Symbol(name, kind, detail, documentation, file, start,
					end, from, to, parts, type);
		}
	}

	/**
	 * What is declared outside of a text and known in it, as the files
	 * around it tell at the moment it is asked.
	 */
	interface Scope {
		/** Nothing is declared outside. */
		Scope NONE = new Scope() {
			@Override
			public List<String> names() {
				return Collections.emptyList();
			}

			@Override
			public List<Symbol> find(CharSequence word) {
				return Collections.emptyList();
			}

			@Override
			public int line(Symbol symbol) {
				return -1;
			}
		};

		/**
		 * Return the names declared outside, each once; the same list
		 * for as long as they stay the same.
		 */
		List<String> names();

		/**
		 * Return what word refers to outside of the text, as
		 * {@link Knowledge#at} does inside: a function with every form,
		 * else one symbol, or nothing; each with its file.
		 */
		List<Symbol> find(CharSequence word);

		/**
		 * Return every declaration of word outside of the text, each
		 * with its file: where find answers one symbol for a name that
		 * is no function, every one of them. By default what find
		 * answers.
		 */
		default List<Symbol> all(CharSequence word) {
			return find(word);
		}

		/**
		 * Return the line, from 0, the name of symbol stands in in its
		 * file, for a symbol find gave; -1 if it cannot be told.
		 */
		int line(Symbol symbol);
	}

	/** Reads a text line by line. */
	interface Builder {
		/**
		 * Add the next line, which starts at offset lineStart, with the
		 * tokens the language gave it.
		 */
		void addLine(CharSequence line, int lineStart, LineTokens tokens);

		/** Return the knowledge of the lines added; call it once, last. */
		Knowledge build();
	}

	/** Knows nothing. */
	Knowledge NONE = new Knowledge() {
		@Override
		public List<Symbol> symbols() {
			return Collections.emptyList();
		}

		@Override
		public List<Symbol> at(int offset, CharSequence word) {
			return Collections.emptyList();
		}

		@Override
		public List<Symbol> links() {
			return Collections.emptyList();
		}

		@Override
		public int[] folds() {
			return new int[0];
		}
	};

	/** Return the declarations of the text in its order: the outline. */
	List<Symbol> symbols();

	/**
	 * Return what word, which stands at offset, refers to: a function
	 * with every form it is declared in, else one symbol, or nothing.
	 * The word says what is asked for, the offset from where, so that a
	 * local or a parameter is found where it can be seen and hides what
	 * is declared outside; an offset of -1 asks from outside of the
	 * text, by the name alone.
	 */
	List<Symbol> at(int offset, CharSequence word);

	/**
	 * Return the files the text names, in its order, each with the path
	 * as written for a name and where the path stands.
	 */
	List<Symbol> links();

	/**
	 * Return whether this is all there is to know of the text: false
	 * when a limit cut it short. What is not complete still answers
	 * what it knows, but nothing that has to be certain may be built on
	 * it.
	 */
	default boolean complete() {
		return true;
	}

	/**
	 * Return the regions that fold, as pairs of the first and the last
	 * line, counted from 0, in the order they start.
	 */
	int[] folds();
}
