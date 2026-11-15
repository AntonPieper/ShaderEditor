package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * A number of the code that can be tuned, as the tokens show it: the
 * literal from start to end, and a minus directly in front that belongs
 * to the value (after "(", ",", "=", "?", ":" and the like, or at the
 * start) from signStart. Inside a vector constructor of number literals
 * only, the place knows the other parts; inside radians() it is an
 * angle in degrees.
 *
 * {@link #text} is what is written from signStart to end for a value:
 * after a binary + or −, a negative value gets parentheses, so that
 * {@code a - 5.0} tuned by −10 is {@code a - (-5.0)}, never
 * {@code a --5.0}.
 */
public final class TunedPlace {
	/** The parts of the vector constructor a number is in. */
	public record Vector(@NonNull String type, @NonNull List<TunedPlace> parts,
			int index) {
	}

	// Tokens after which a minus is the sign of what follows.
	private static final Set<String> BEFORE_SIGN = Set.of(
			"(", ",", "=", "?", ":", "[", "{", ";", "return");
	private static final Set<String> VECTORS = Set.of(
			"vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
			"uvec2", "uvec3", "uvec4");

	public final int signStart;
	public final int start;
	public final int end;
	@NonNull
	public final String literal;
	@NonNull
	public final Tuning.Type type;
	/** The value, with the minus that belongs to it. */
	@NonNull
	public final BigDecimal value;
	/** Whether it is the angle in degrees of radians(). */
	public final boolean degrees;
	// Whether a negative value needs parentheses where it is.
	private final boolean afterBinary;
	@Nullable
	private Vector vector;

	private TunedPlace(int signStart, int start, int end,
			@NonNull String literal, @NonNull Tuning.Type type,
			@NonNull BigDecimal value, boolean degrees,
			boolean afterBinary) {
		this.signStart = signStart;
		this.start = start;
		this.end = end;
		this.literal = literal;
		this.type = type;
		this.value = value;
		this.degrees = degrees;
		this.afterBinary = afterBinary;
	}

	/**
	 * Return the number of the code at offset (in it, or right after
	 * it), or null when there is none there.
	 */
	@Nullable
	public static TunedPlace at(@NonNull CharSequence text, int offset) {
		Tokens tokens = GlslLexer.lex(text);
		int index = numberAt(tokens, offset);
		if (index < 0) {
			return null;
		}
		TunedPlace place = place(text, tokens, index);
		if (place == null) {
			return null;
		}
		place.vector = vector(text, tokens, index);
		return place;
	}

	/**
	 * Return whether offset of a line is in a number or right after
	 * one, by the line's own tokens: what the strip asks at the caret,
	 * which costs no more than the line.
	 */
	public static boolean isNumberAt(@NonNull Tokens tokens, int offset) {
		return numberAt(tokens, offset) > -1;
	}

	/** The vector constructor it is a part of, or null. */
	@Nullable
	public Vector vector() {
		return vector;
	}

	/**
	 * Return what to write from signStart to end for value, with
	 * step's decimals at least.
	 */
	@NonNull
	public String text(@NonNull BigDecimal value, @NonNull BigDecimal step) {
		String written = LiteralText.write(literal, value, step);
		return afterBinary && value.signum() < 0
				? "(" + written + ")"
				: written;
	}

	/**
	 * Return what to put from start to end, the literal alone, for
	 * value: the minus in front of it stays, so the literal holds the
	 * value with the opposite sign then.
	 */
	@NonNull
	public String literalText(@NonNull BigDecimal value,
			@NonNull BigDecimal step) {
		if (signStart == start) {
			return text(value, step);
		}
		BigDecimal own = value.negate();
		String written = LiteralText.write(literal, own, step);
		return own.signum() < 0 ? "(" + written + ")" : written;
	}

	/**
	 * Return the number the literal from start to end stands for when
	 * the place has value: its uniform's value while it is tuned.
	 */
	public double literalValue(@NonNull BigDecimal value) {
		return (signStart == start ? value : value.negate()).doubleValue();
	}

	private static int numberAt(@NonNull Tokens tokens, int offset) {
		for (int at : new int[]{offset, offset - 1}) {
			int index = at < 0 ? -1 : tokens.indexAt(at);
			if (index > -1 && tokens.style(index) == Style.NUMBER &&
					!tokens.inDirective(index)) {
				return index;
			}
		}
		return -1;
	}

	@Nullable
	private static TunedPlace place(@NonNull CharSequence text,
			@NonNull Tokens tokens, int index) {
		int start = tokens.start(index);
		int end = tokens.end(index);
		String literal = text.subSequence(start, end).toString();
		BigDecimal value = LiteralText.value(literal);
		Tuning.Type type = LiteralText.type(literal);
		if (value == null || type == null) {
			return null;
		}
		int signStart = start;
		boolean afterBinary = false;
		int before = index - 1;
		if (before > -1 && "-".equals(token(text, tokens, before)) &&
				tokens.end(before) == start && isUnary(text, tokens, before)) {
			signStart = tokens.start(before);
			value = value.negate();
			afterBinary = isBinarySign(text, tokens, before - 1);
		} else if (before > -1) {
			afterBinary = isBinarySign(text, tokens, before);
		}
		return new TunedPlace(signStart, start, end, literal, type, value,
				isRadians(text, tokens, index, signStart), afterBinary);
	}

	// Whether the minus at index is the sign of what follows it.
	private static boolean isUnary(@NonNull CharSequence text,
			@NonNull Tokens tokens, int index) {
		if (index == 0) {
			return true;
		}
		Style style = tokens.style(index - 1);
		String token = token(text, tokens, index - 1);
		return BEFORE_SIGN.contains(token) ||
				(style == Style.OPERATOR && !")".equals(token) &&
						!"]".equals(token) && !"++".equals(token) &&
						!"--".equals(token)) ||
				style == Style.CONTROL_FLOW || style == Style.KEYWORD;
	}

	// Whether the token at index is a + or − between two operands,
	// after which a minus would make "--" or "+-".
	private static boolean isBinarySign(@NonNull CharSequence text,
			@NonNull Tokens tokens, int index) {
		if (index < 0) {
			return false;
		}
		String token = token(text, tokens, index);
		return ("-".equals(token) || "+".equals(token)) &&
				!isUnary(text, tokens, index);
	}

	private static boolean isRadians(@NonNull CharSequence text,
			@NonNull Tokens tokens, int index, int signStart) {
		int open = signStart == tokens.start(index) ? index - 1 : index - 2;
		return open > 0 && index + 1 < tokens.size() &&
				"(".equals(token(text, tokens, open)) &&
				")".equals(token(text, tokens, index + 1)) &&
				"radians".equals(token(text, tokens, open - 1));
	}

	// The vector constructor whose arguments are all numbers, one of
	// them the one at index, or null.
	@Nullable
	private static TunedPlace.Vector vector(@NonNull CharSequence text,
			@NonNull Tokens tokens, int index) {
		// The open parenthesis of the call it is an argument of.
		int depth = 0;
		int open = -1;
		for (int i = index - 1; i >= 0; --i) {
			String token = token(text, tokens, i);
			if (")".equals(token)) {
				++depth;
			} else if ("(".equals(token)) {
				if (depth == 0) {
					open = i;
					break;
				}
				--depth;
			} else if (";".equals(token) || "{".equals(token) ||
					"}".equals(token)) {
				return null;
			}
		}
		if (open < 1 || !VECTORS.contains(token(text, tokens, open - 1))) {
			return null;
		}
		List<TunedPlace> parts = new ArrayList<>();
		int found = -1;
		int i = open + 1;
		while (i < tokens.size()) {
			int number = i;
			if ("-".equals(token(text, tokens, i)) && i + 1 < tokens.size()) {
				number = i + 1;
			}
			if (tokens.style(number) != Style.NUMBER) {
				return null;
			}
			TunedPlace part = place(text, tokens, number);
			if (part == null || (number != i && part.signStart != tokens.start(i))) {
				return null;
			}
			if (number == index) {
				found = parts.size();
			}
			parts.add(part);
			i = number + 1;
			String next = i < tokens.size() ? token(text, tokens, i) : "";
			if (")".equals(next)) {
				break;
			} else if (!",".equals(next)) {
				return null;
			}
			++i;
		}
		return found > -1 && parts.size() > 1
				? new Vector(token(text, tokens, open - 1), parts, found)
				: null;
	}

	@NonNull
	private static String token(@NonNull CharSequence text,
			@NonNull Tokens tokens, int index) {
		return text.subSequence(tokens.start(index), tokens.end(index))
				.toString();
	}
}
