package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslCompletion;
import de.markusfisch.android.shadereditor.syntax.GlslWords;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.Style;

/**
 * Where a word is declared, as the knowledge of the file lists it, seen
 * from a place: no more, no less. It never chooses: a name declared
 * twice, in two branches of an "#if", as a macro and a function, here
 * and in a file this one includes, in two files around, is declared
 * twice. Only a prototype goes into the body it announces, when both
 * are in this file, where the text tells a body by its brace. Lookups
 * only, cheap enough for every caret move.
 *
 * It also says whether the places are all there are, which is what a
 * jump to one of them, or "none", needs. They may not be when:
 * <ul>
 * <li>the knowledge of the text is not whole: a limit cut it, a line
 * was too long to read, a macro is called outside of functions, or
 * nothing was read yet;</li>
 * <li>the files around are not wholly known, see
 * {@link Knowledge.Scope#whole};</li>
 * <li>a macro of the text glues names together with ##, which makes
 * names that are declared nowhere;</li>
 * <li>the word stands in the body of a macro: it means what it means
 * where the macro is used;</li>
 * <li>the word stands in a function, and before it in that function
 * stands a directive, as "#if", or a statement that a macro starts:
 * either may declare a name the reader does not see, or declare one it
 * sees only in some builds. Where the files around are not wholly
 * known, any name that starts a statement and is known neither here nor
 * there may be a macro.</li>
 * </ul>
 * A local or a parameter needs no files around wholly known: it hides
 * what they declare. When it is not certain, it does not hide what is
 * declared outside: all of them are places.
 */
public final class Definitions {
	/**
	 * What was found.
	 *
	 * @param places  the declarations, those of this file first, in its
	 *                order, then those of the files around, each with
	 *                its file
	 * @param here    whether the place asked from is the name of one of
	 *                them in this file
	 * @param certain whether the places are all the declarations the
	 *                word can mean there, so that one of them is the
	 *                one, and none means none
	 */
	public record Found(List<Knowledge.Symbol> places, boolean here,
			boolean certain) {
		/** Return the one declaration the word surely means, or null. */
		public Knowledge.Symbol only() {
			return certain && places.size() == 1 ? places.get(0) : null;
		}

		/** Return whether the word is surely declared nowhere. */
		public boolean none() {
			return certain && places.isEmpty();
		}
	}

	private Definitions() {
	}

	/**
	 * Return where word, which stands at offset of text, is declared,
	 * as known of text and scope of what is around it.
	 */
	public static Found of(Knowledge known, Knowledge.Scope scope,
			Text text, int offset, String word) {
		List<Knowledge.Symbol> places = new ArrayList<>();
		for (Knowledge.Symbol symbol : known.symbols()) {
			if (symbol.name().equals(word)) {
				places.add(symbol);
			}
		}
		List<Knowledge.Symbol> at = known.at(offset, word);
		boolean local = at.size() == 1 && !holds(places, at.get(0));
		// A local hides what the files around declare, also when they
		// are not wholly known.
		boolean whole = scope.whole(known);
		boolean certain = (whole || local) && known.complete() &&
				!glues(known) && readable(known, scope, whole, text, offset,
				local ? at.get(0) : null);
		if (local && certain) {
			// A local or a parameter hides what is declared outside.
			places = at;
		} else {
			if (local) {
				places.add(0, at.get(0));
			}
			places.addAll(scope.all(word));
			dropPrototypes(places, text);
		}
		boolean here = false;
		for (Knowledge.Symbol place : places) {
			here |= place.file() == null && offset >= place.start() &&
					offset <= place.end();
		}
		return new Found(places, here, certain);
	}

	/**
	 * Return the index of the one form of forms that takes arguments
	 * of these types, as many as it has parameters; -1 when none or
	 * more than one does, or when a macro of the name takes the call.
	 */
	public static int fitting(List<Knowledge.Symbol> forms,
			List<String> arguments) {
		int found = -1;
		for (int i = 0, n = forms.size(); i < n; ++i) {
			Knowledge.Symbol form = forms.get(i);
			if (form.kind() == Knowledge.Kind.MACRO) {
				return -1;
			} else if (form.kind() == Knowledge.Kind.FUNCTION &&
					form.parts().size() == arguments.size() &&
					GlslCompletion.takes(types(form), arguments)) {
				if (found > -1) {
					return -1;
				}
				found = i;
			}
		}
		return found;
	}

	private static boolean holds(List<Knowledge.Symbol> symbols,
			Knowledge.Symbol symbol) {
		for (Knowledge.Symbol s : symbols) {
			if (s == symbol) {
				return true;
			}
		}
		return false;
	}

	// Whether what the text declares around offset is read as the
	// compiler reads it: not in the body of a macro, but for one of its
	// parameters, nor behind a directive or a statement a macro starts
	// in a function.
	private static boolean readable(Knowledge known, Knowledge.Scope scope,
			boolean whole, Text text, int offset, Knowledge.Symbol local) {
		Knowledge.Symbol function = null;
		for (Knowledge.Symbol symbol : known.symbols()) {
			if (symbol.from() > offset) {
				break;
			} else if (offset >= symbol.to()) {
				continue;
			} else if (symbol.kind() == Knowledge.Kind.MACRO) {
				if (offset > symbol.end() && (local == null ||
						local.kind() != Knowledge.Kind.PARAMETER)) {
					return false;
				}
			} else if (symbol.kind() == Knowledge.Kind.FUNCTION) {
				function = symbol;
			}
		}
		if (function == null) {
			return true;
		}
		List<Knowledge.Symbol> outside = scope.macros();
		Fault fault = last;
		if (fault == null || fault.known != known ||
				fault.function != function || fault.outside != outside ||
				fault.whole != whole) {
			fault = new Fault(known, function, outside, whole,
					fault(known, scope, whole, text, function));
			last = fault;
		}
		return fault.at >= offset;
	}

	// Where in a function the reader stops reading it as the compiler
	// does, for the last function asked about: a function is asked
	// about on every caret move in it, and scanned once.
	private record Fault(Knowledge known, Knowledge.Symbol function,
			List<Knowledge.Symbol> outside, boolean whole, int at) {
	}

	private static volatile Fault last;

	// Returns where in function the first directive line or statement a
	// macro starts stands, or the end of function. Where the files
	// around are not wholly known, any name that starts a statement
	// and is known neither here nor there may be a macro.
	private static int fault(Knowledge known, Knowledge.Scope scope,
			boolean whole, Text text, Knowledge.Symbol function) {
		Set<String> macros = new HashSet<>();
		Set<String> named = whole ? null : new HashSet<>(scope.names());
		for (List<Knowledge.Symbol> list : List.of(known.symbols(),
				scope.macros())) {
			for (Knowledge.Symbol symbol : list) {
				if (symbol.kind() == Knowledge.Kind.MACRO) {
					macros.add(symbol.name());
				}
			}
		}
		int end = Math.min(function.to(), text.length());
		for (int n = text.lineOf(function.from()), lines = text.lineCount();
				n < lines && text.lineStart(n) < end; ++n) {
			String line = text.line(n);
			int lineStart = text.lineStart(n);
			int from = Math.max(0, function.from() - lineStart);
			if (from == 0 && line.trim().startsWith("#")) {
				return lineStart;
			}
			int at = macros.isEmpty() && named == null ? -1 : statement(
					line, from, Math.min(line.length(), end - lineStart),
					macros, named, known, lineStart + from);
			if (at > -1) {
				return lineStart + at;
			}
		}
		return end;
	}

	// Returns where one of macros, or a name that may be one, stands
	// where a statement starts in line between from and to: first on
	// the line, or behind ";", "{" or "}"; -1 if none does. With named,
	// the names the files around are known to declare, any name that is
	// neither among them nor known at offset may be a macro.
	private static int statement(String line, int from, int to,
			Set<String> macros, Set<String> named, Knowledge known,
			int offset) {
		char before = ';';
		for (int i = from; i < to; ) {
			char c = line.charAt(i);
			if (!Character.isJavaIdentifierStart(c)) {
				if (!Character.isWhitespace(c)) {
					before = c;
				}
				++i;
				continue;
			}
			int start = i;
			while (i < to && Character.isJavaIdentifierPart(line.charAt(i))) {
				++i;
			}
			String name = line.substring(start, i);
			if ((before == ';' || before == '{' || before == '}') &&
					(macros.contains(name) || (named != null &&
							!named.contains(name) && GlslWords.style(name, 0,
							name.length()) == Style.IDENTIFIER &&
							known.at(offset, name).isEmpty()))) {
				return start;
			}
			before = 'a';
		}
		return -1;
	}

	// Drops each prototype of this file that has a body of the same
	// parameter types in this file: GLSL allows one body per form.
	private static void dropPrototypes(List<Knowledge.Symbol> places,
			Text text) {
		for (int i = places.size(); i-- > 0; ) {
			Knowledge.Symbol prototype = places.get(i);
			if (!isBody(prototype, text, ';')) {
				continue;
			}
			for (Knowledge.Symbol body : places) {
				if (isBody(body, text, '}') &&
						types(body).equals(types(prototype))) {
					places.remove(i);
					break;
				}
			}
		}
	}

	// Whether symbol is a function of this file whose declaration ends
	// with end: a brace for a body, a semicolon for a prototype.
	private static boolean isBody(Knowledge.Symbol symbol, Text text,
			char end) {
		int to = symbol.to();
		return symbol.kind() == Knowledge.Kind.FUNCTION &&
				symbol.file() == null && to > 0 && to <= text.length() &&
				text.substring(to - 1, to).charAt(0) == end;
	}

	private static List<String> types(Knowledge.Symbol function) {
		List<String> types = new ArrayList<>();
		for (Knowledge.Symbol part : function.parts()) {
			types.add(part.type());
		}
		return types;
	}

	// Whether a macro of the text glues names.
	private static boolean glues(Knowledge known) {
		for (Knowledge.Symbol symbol : known.symbols()) {
			if (gluing(symbol)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Return whether symbol is a macro that glues names together with
	 * ##. A macro's detail is its line, which holds its body.
	 */
	public static boolean gluing(Knowledge.Symbol symbol) {
		return symbol.kind() == Knowledge.Kind.MACRO &&
				symbol.detail().contains("##");
	}
}
