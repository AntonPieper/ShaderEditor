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
	 * Something that stands between a declaration and a place and
	 * keeps the place from being certain: a macro that was not
	 * executed, a conditional that is not decided or not compiled.
	 * Places that share what stands between can be shown together.
	 *
	 * @param why   what kind of thing it is
	 * @param name  how the text names it: the macro's name, the
	 *              condition as it is written
	 * @param file  the file it is defined or stands in, as the host
	 *              names its files; null if it is in no file
	 * @param start where it is defined or stands in that file
	 */
	record Cause(Use.Why why, String name, String file, int start) {
	}

	/**
	 * A place where the name of a declaration stands, and whether it
	 * means that declaration for certain. Certain means proven: the
	 * program the compiler gets has the name there, and there it means
	 * the declaration. Whatever cannot be proven has another reason.
	 *
	 * @param file   the file the place is in, as the host names its
	 *               files
	 * @param start  where the name starts in that file
	 * @param end    where it ends
	 * @param why    what it is, or why it is not certain
	 * @param causes what stands between, in the order of the program;
	 *               empty where the reason has nothing to name
	 */
	record Use(String file, int start, int end, Why why,
			List<Cause> causes) {
		/** What a use is. */
		public enum Why {
			/** The name where it is declared. */
			DECLARATION,
			/** Code that is compiled and means the declaration. */
			USE,
			/**
			 * In the text of a macro, or behind a macro that was not
			 * executed: macros are not expanded, so nothing behind
			 * one is proven.
			 */
			IN_MACRO,
			/** In code a conditional keeps from being compiled. */
			INACTIVE,
			/**
			 * In or behind a conditional that the text does not decide,
			 * which may change what the name means there.
			 */
			CONDITIONAL,
			/**
			 * It means another declaration of the name, made outside of
			 * functions in another file, or means the declaration in
			 * one program and something else in another; or it is
			 * another definition of a macro of that name.
			 */
			REDECLARED,
			/**
			 * In a file that a program compiles which does not have
			 * the declaration, or that is not read with every program
			 * that compiles it.
			 */
			NOT_INCLUDED,
			/** A member of a value whose type cannot be told. */
			UNTYPED,
			/** A call whose form cannot be told from its arguments. */
			AMBIGUOUS,
			/**
			 * No word of the name: the invocation of a macro that
			 * builds names with "##" and may build this one.
			 */
			PASTES,
			/**
			 * A directive asks whether the name is defined, where the
			 * macro asked about is not: an include guard before its
			 * "#define".
			 */
			TEST
		}

		/** Whether the use means the declaration for certain. */
		public boolean certain() {
			return why == Why.DECLARATION || why == Why.USE;
		}

		/** Return the name of the first thing that stands between. */
		public String about() {
			return causes.isEmpty() ? null : causes.get(0).name();
		}

		/** Return this use for another reason. */
		public Use because(Why why, List<Cause> causes) {
			return new Use(file, start, end, why, causes);
		}
	}

	/**
	 * The uses of a declaration in the programs that were read, and
	 * what a rename needs beside them.
	 *
	 * @param uses     the places, in no order
	 * @param taken    the declarations a name that was asked about
	 *                 already means at one of the places, each as the
	 *                 place of its name
	 * @param bindings for each program that was read, for each name of
	 *                 its compiled code in its order, which name of
	 *                 that program declares what it means, as its
	 *                 number among those names: for a function the
	 *                 first head of its form, for a member its field;
	 *                 {@link #NOTHING} for a name that means nothing
	 *                 the program declares, {@link #UNKNOWN} where
	 *                 that could not be told, less for a macro
	 * @param proven   for each of those names, whether what bindings
	 *                 says of it is proven: its line is compiled for
	 *                 sure, no macro that was not executed stands
	 *                 before it, no condition that is not decided may
	 *                 have changed what it means, and its form or its
	 *                 type was proven. Where it is not, bindings has
	 *                 what the reader took it for, which is a guess
	 * @param depends  the conditions the text does not decide on which
	 *                 it depends what the name means at one of the
	 *                 places, or which form or type a place was to be
	 *                 proven by, each once, in the order of the
	 *                 programs; empty if it depends on none. With one,
	 *                 a place may mean the declaration with one
	 *                 outcome and something else with another, and no
	 *                 rename is right for both
	 */
	record Answer(List<Use> uses, List<Use> taken, int[][] bindings,
			boolean[][] proven, List<Cause> depends) {
		public static final int NOTHING = -1;
		public static final int UNKNOWN = -2;

		/** What two readings say of the bindings of the same names. */
		public enum Bindings {
			/** Every name is proven to mean what it meant. */
			SAME,
			/** A name means something else, or the names are others. */
			CHANGED,
			/**
			 * No name was seen to mean something else, but of some
			 * that is not proven, in one reading or in both: behind a
			 * macro that was not executed, under a condition that is
			 * not decided, or for a form or a type nobody could tell.
			 * Nothing vouches for those.
			 */
			UNPROVEN
		}

		/**
		 * Compare what every name means here with what it means in a
		 * reading of the same programs after names were changed.
		 */
		public Bindings compare(Answer later) {
			if (later == null || bindings.length != later.bindings.length) {
				return Bindings.CHANGED;
			}
			boolean all = true;
			for (int p = 0; p < bindings.length; ++p) {
				int[] a = bindings[p];
				int[] b = later.bindings[p];
				if (a.length != b.length) {
					return Bindings.CHANGED;
				}
				for (int i = 0; i < a.length; ++i) {
					if (a[i] != b[i]) {
						// Also where one of them is a guess: a guess
						// that changed is no reason to go on.
						return Bindings.CHANGED;
					}
					all &= proven[p][i] && later.proven[p][i];
				}
			}
			return all ? Bindings.SAME : Bindings.UNPROVEN;
		}

		/**
		 * Whether every name is proven to mean what it meant here, in
		 * a reading of the same programs after names were changed.
		 */
		public boolean sameBindings(Answer later) {
			return compare(later) == Bindings.SAME;
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
