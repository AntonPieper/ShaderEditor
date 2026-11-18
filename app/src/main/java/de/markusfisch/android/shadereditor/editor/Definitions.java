package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslCompletion;
import de.markusfisch.android.shadereditor.syntax.Knowledge;

/**
 * Where a word is declared, as the knowledge of the file lists it, seen
 * from a place: no more, no less. It never chooses: a name declared
 * twice, in two branches of an "#if", as a macro and a function, here
 * and in a file this one includes, in two files around, is declared
 * twice. Only a prototype
 * goes into the body it announces, when both are in this file, where
 * the text tells a body by its brace. Lookups only, cheap enough for
 * every caret move.
 */
public final class Definitions {
	/**
	 * What was found.
	 *
	 * @param places the declarations, those of this file first, in its
	 *               order, then those of the files around, each with
	 *               its file
	 * @param here   whether the place asked from is the name of one of
	 *               them in this file
	 * @param sure   whether no places means there are none: the
	 *               knowledge is whole, and no macro glues names
	 *               together with ##, which makes names that are
	 *               declared nowhere
	 */
	public record Found(List<Knowledge.Symbol> places, boolean here,
			boolean sure) {
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
		// A local or a parameter hides what is declared outside.
		List<Knowledge.Symbol> at = known.at(offset, word);
		if (at.size() == 1 && !holds(places, at.get(0))) {
			places = at;
		} else {
			places.addAll(scope.all(word));
			dropPrototypes(places, text);
		}
		boolean here = false;
		for (Knowledge.Symbol place : places) {
			here |= place.file() == null && offset >= place.start() &&
					offset <= place.end();
		}
		return new Found(places, here, !places.isEmpty() ||
				(known.complete() && !glues(known, scope)));
	}

	/**
	 * Return the index of the one form of forms that takes arguments
	 * of these types, as many as it has parameters; -1 when none or
	 * more than one does.
	 */
	public static int fitting(List<Knowledge.Symbol> forms,
			List<String> arguments) {
		int found = -1;
		for (int i = 0, n = forms.size(); i < n; ++i) {
			Knowledge.Symbol form = forms.get(i);
			if (form.kind() == Knowledge.Kind.FUNCTION &&
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

	// Whether a macro of the text or of the files around glues names.
	// A macro's detail is its line, which holds its body.
	private static boolean glues(Knowledge known, Knowledge.Scope scope) {
		for (Knowledge.Symbol symbol : known.symbols()) {
			if (gluing(symbol)) {
				return true;
			}
		}
		for (String name : scope.names()) {
			for (Knowledge.Symbol symbol : scope.find(name)) {
				if (gluing(symbol)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean gluing(Knowledge.Symbol symbol) {
		return symbol.kind() == Knowledge.Kind.MACRO &&
				symbol.detail().contains("##");
	}
}
