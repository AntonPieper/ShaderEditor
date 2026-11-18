package de.markusfisch.android.shadereditor.syntax;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a ';' may be missing: right before the token a compiler stopped
 * at, as in JavaScript's automatic semicolon insertion. A compile of the
 * text with the ';' put in decides.
 */
public final class MissingSemicolon {
	private static final Pattern SYNTAX_ERROR = Pattern.compile(
			"^'(.+)' : (?:Syntax error:\\s+)?syntax error$");
	private static final Set<Style> ENDS = EnumSet.of(Style.IDENTIFIER,
			Style.FIELD_SELECTION, Style.NUMBER, Style.BOOL);
	// Tokens that start a statement other than an expression.
	private static final Set<Style> STARTS = EnumSet.of(Style.KEYWORD,
			Style.CONTROL_FLOW, Style.PRECISION, Style.TYPE_QUALIFIER,
			Style.PRIMITIVE, Style.TYPE_VECTOR, Style.TYPE_MATRIX,
			Style.TYPE_SAMPLER, Style.TYPE_IMAGE, Style.ATOMIC_COUNTER,
			Style.TYPE);

	private MissingSemicolon() {
	}

	/**
	 * The offset for a ';' when error, on the 0-based line, is a bare
	 * syntax error at a token: after the code token before an occurrence
	 * of it, if that ends an expression and the token starts a declaration,
	 * a keyword's statement, "}" or an assignment. A missing operator
	 * leaves none of these. -1 for no such place or more than one.
	 */
	public static int place(CharSequence text, int line, String error) {
		Matcher matcher = SYNTAX_ERROR.matcher(error);
		if (!matcher.matches()) {
			return -1;
		}
		String token = matcher.group(1);
		Tokens tokens = GlslLexer.lex(text);
		int place = -1;
		int previous = -1;
		for (int i = 0; i < tokens.size(); ++i) {
			if (tokens.style(i) == Style.COMMENT) {
				continue;
			}
			int at = tokens.lineOf(tokens.start(i));
			if (at > line) {
				break;
			}
			if (at == line && previous >= 0 &&
					token.equals(text(tokens, text, i)) &&
					ends(tokens, text, previous) &&
					starts(tokens, text, i)) {
				if (place >= 0) {
					return -1;
				}
				place = tokens.end(previous);
			}
			previous = i;
		}
		return place;
	}

	/** The code token that ends at offset at. */
	public static String before(CharSequence text, int at) {
		Tokens tokens = GlslLexer.lex(text);
		return text(tokens, text, tokens.indexAt(at - 1));
	}

	private static boolean ends(Tokens tokens, CharSequence text, int i) {
		String t = text(tokens, text, i);
		return !tokens.inDirective(i) && (ENDS.contains(tokens.style(i)) ||
				t.equals(")") || t.equals("]"));
	}

	// A type that is not called, another statement's keyword, "}", or a
	// name followed by "=" or an operator and "=".
	private static boolean starts(Tokens tokens, CharSequence text, int i) {
		String next = following(tokens, text, i);
		if (text(tokens, text, i).equals("}") ||
				(STARTS.contains(tokens.style(i)) && !next.equals("("))) {
			return true;
		}
		String after = following(tokens, text, i + 1);
		return next.equals("=") ? !after.equals("=") :
				"+-*/".contains(next) && next.length() == 1 &&
						after.equals("=");
	}

	private static String following(Tokens tokens, CharSequence text, int i) {
		for (int n = i + 1; n < tokens.size(); ++n) {
			if (tokens.style(n) != Style.COMMENT) {
				return text(tokens, text, n);
			}
		}
		return "";
	}

	private static String text(Tokens tokens, CharSequence text, int i) {
		return text.subSequence(tokens.start(i), tokens.end(i)).toString();
	}
}
