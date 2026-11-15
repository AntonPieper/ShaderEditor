package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * Where GLSL needs a constant, as far as the tokens of one file tell
 * without compiling: a number there cannot become a uniform. Whatever
 * is not recognised here is the compiler's to refuse, such as a #define
 * that is used where a constant is needed.
 */
public final class ConstantContext {
	public enum Kind {
		/** On a directive line other than #define: #if, #line, #pragma. */
		DIRECTIVE,
		/**
		 * In a macro the app reads from the text itself, such as
		 * FTIME_PERIOD: a uniform there would not change what it reads.
		 */
		HOST,
		/** Inside layout(...). */
		LAYOUT,
		/**
		 * The size of an array; in GLSL ES 1.00 also an index, which
		 * Appendix A asks to be constant in a fragment shader.
		 */
		ARRAY,
		/** The header of a for loop in GLSL ES 1.00 (Appendix A). */
		LOOP,
		/** A case label. */
		CASE,
		/** The initializer of a const variable. */
		CONST,
		/** The initializer of a global variable. */
		GLOBAL
	}

	private static final Set<Style> TYPES = EnumSet.of(Style.PRIMITIVE,
			Style.TYPE_VECTOR, Style.TYPE_MATRIX, Style.TYPE_SAMPLER,
			Style.TYPE_IMAGE, Style.ATOMIC_COUNTER, Style.TYPE);

	// A bracket that is open, and the constant it asks for, if any.
	private record Group(char bracket, @Nullable Kind kind) {
	}

	private ConstantContext() {
	}

	/**
	 * Return why the token at offset of text, GLSL code, must be a
	 * constant, or null when nothing says so. es100 is whether a
	 * program in GLSL ES 1.00 reads the text.
	 */
	@Nullable
	public static Kind of(@NonNull String text, int offset, boolean es100) {
		Tokens tokens = GlslLexer.lex(text);
		int at = tokens.indexAt(offset);
		if (at < 0) {
			return null;
		}
		if (tokens.inDirective(at)) {
			String macro = defined(text, tokens, at);
			if (macro == null) {
				return Kind.DIRECTIVE;
			}
			return Tuning.HOST_MACROS.contains(macro) ? Kind.HOST : null;
		}
		Deque<Group> open = new ArrayDeque<>();
		// The tokens of the statement so far, from after the last ";",
		// "{" or "}".
		List<Integer> statement = new ArrayList<>();
		int previous = -1;
		int beforePrevious = -1;
		for (int t = 0; t < at; ++t) {
			Style style = tokens.style(t);
			if (style == Style.COMMENT || tokens.inDirective(t)) {
				continue;
			}
			char c = style == Style.OPERATOR ? text.charAt(tokens.start(t)) : 0;
			switch (c) {
				case '(' -> open.push(new Group(c, is(text, tokens, previous,
						"for") ? Kind.LOOP : is(text, tokens, previous, "layout")
						? Kind.LAYOUT
						: null));
				case '[' -> open.push(new Group(c, es100 ||
						isSize(tokens, previous, beforePrevious)
						? Kind.ARRAY
						: null));
				case ')', ']' -> {
					if (!open.isEmpty() && open.peek().bracket != '{') {
						open.pop();
					}
				}
				case '{' -> open.push(new Group(c, null));
				case '}' -> {
					while (!open.isEmpty() && open.pop().bracket != '{') {
						// Brackets left open inside the block close with it.
					}
				}
				default -> {
				}
			}
			if (c == ';' || c == '{' || c == '}') {
				statement.clear();
			} else {
				statement.add(t);
			}
			beforePrevious = previous;
			previous = t;
		}
		boolean global = true;
		for (Group group : open) {
			if (group.kind == Kind.LOOP ? es100 : group.kind != null) {
				return group.kind;
			}
			global &= group.bracket != '{';
		}
		int lastCase = -1;
		boolean isConst = false;
		boolean assigns = false;
		for (int i = 0; i < statement.size(); ++i) {
			int t = statement.get(i);
			if (is(text, tokens, t, "case")) {
				lastCase = i;
			} else if (is(text, tokens, t, ":")) {
				lastCase = -1;
			}
			isConst |= is(text, tokens, t, "const");
			assigns |= is(text, tokens, t, "=");
		}
		if (lastCase > -1) {
			return Kind.CASE;
		} else if (isConst) {
			return Kind.CONST;
		}
		return global && assigns ? Kind.GLOBAL : null;
	}

	// "[" after a type, as in float[3], or after a type and a name, as
	// in float a[3], is the size of an array, not an index.
	private static boolean isSize(@NonNull Tokens tokens, int previous,
			int beforePrevious) {
		if (previous < 0) {
			return false;
		}
		Style style = tokens.style(previous);
		return TYPES.contains(style) || (style == Style.IDENTIFIER &&
				beforePrevious > -1 &&
				TYPES.contains(tokens.style(beforePrevious)));
	}

	// The name of the macro when the directive the token at index is on
	// is a #define, else null.
	@Nullable
	private static String defined(@NonNull String text,
			@NonNull Tokens tokens, int index) {
		int hash = index;
		while (hash > 0 && tokens.inDirective(hash - 1) &&
				!is(text, tokens, hash, "#")) {
			--hash;
		}
		if (hash + 2 >= tokens.size() ||
				!is(text, tokens, hash + 1, "define")) {
			return null;
		}
		int name = hash + 2;
		return text.substring(tokens.start(name), tokens.end(name));
	}

	private static boolean is(@NonNull String text, @NonNull Tokens tokens,
			int index, @NonNull String word) {
		if (index < 0) {
			return false;
		}
		int start = tokens.start(index);
		return tokens.end(index) - start == word.length() &&
				text.startsWith(word, start);
	}
}
