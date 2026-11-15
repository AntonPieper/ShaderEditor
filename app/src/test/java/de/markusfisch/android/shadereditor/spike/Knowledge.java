package de.markusfisch.android.shadereditor.spike;

import java.util.Collections;
import java.util.List;

/**
 * SPIKE, throwaway: the proposed seam. What a language knows about one
 * revision of one text, in words no language owns. The editor gets one
 * from the language off the main thread, for a revision, and asks it
 * about offsets of that text. It is immutable to its callers.
 *
 * A language that knows nothing returns {@link #NONE}; one that knows
 * only its brackets answers {@link #folds()} and nothing else; the
 * editor shows what it gets.
 */
interface Knowledge {
	/** What a symbol is; the editor picks an icon and a word by it. */
	enum Kind {
		FUNCTION,
		VARIABLE,
		PARAMETER,
		CONSTANT,
		MACRO,
		TYPE,
		FIELD,
		KEY,
		FILE
	}

	/**
	 * Something with a name: a declaration of the text, a built-in of
	 * the language, a name the host provides, a file the text names.
	 *
	 * @param detail        how the language writes it: a signature, a
	 *                      type, a macro's body; never null
	 * @param documentation the comment that belongs to it, a built-in's
	 *                      line, or null
	 * @param file          null if it is declared in this text, else
	 *                      the path as the text or the project names it
	 * @param start         where its name stands at its declaration, or
	 *                      -1 if it has none in a text
	 * @param from          the whole declaration, what an outline jumps
	 *                      to; from == to if unknown
	 */
	record Symbol(String name, Kind kind, String detail,
			String documentation, String file, int start, int end, int from,
			int to) {
	}

	/**
	 * A call being written.
	 *
	 * @param signatures the callee's signatures, as symbols whose detail
	 *                   is the signature
	 * @param parameters for each signature the offsets of its
	 *                   parameters in its detail, as pairs
	 * @param active     the signature the arguments so far fit, or the
	 *                   first
	 * @param argument   which argument the offset is in, from 0
	 */
	record Call(List<Symbol> signatures, List<int[]> parameters, int active,
			int argument) {
	}

	/**
	 * Names from outside the text: what the host provides whatever the
	 * text says, and what the files it includes declare, each with the
	 * file it comes from. A language reads the detail of a symbol of
	 * its own language as a declaration.
	 */
	interface Scope {
		Scope EMPTY = Collections::emptyList;

		List<Symbol> symbols();
	}

	Knowledge NONE = new Knowledge() {
		@Override
		public List<Symbol> symbols() {
			return Collections.emptyList();
		}

		@Override
		public Symbol at(int offset) {
			return null;
		}

		@Override
		public Call callAt(int offset) {
			return null;
		}

		@Override
		public int[] occurrences(Symbol symbol) {
			return new int[0];
		}

		@Override
		public int[] folds() {
			return new int[0];
		}
	};

	/** The declarations of the text, in its order: the outline. */
	List<Symbol> symbols();

	/**
	 * What the name at offset refers to, or null: its kind, how it is
	 * written, its documentation, and where it is declared.
	 */
	Symbol at(int offset);

	/** The call the offset is an argument of, or null. */
	Call callAt(int offset);

	/**
	 * Where symbol is named in this text, as pairs of start and end,
	 * its declaration included. The symbol may come from the knowledge
	 * of another file: then the places where this text means that one.
	 */
	int[] occurrences(Symbol symbol);

	/** The regions that fold, as pairs of first and last line. */
	int[] folds();
}
