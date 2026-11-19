package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

import de.markusfisch.android.shadereditor.opengl.Tuning;

/**
 * The text of a number literal of GLSL, read and written in decimal
 * arithmetic: a tuned value is written the way the literal was, never
 * as a float prints it (0.3 + 0.01 × 4 is 0.34, never 0.34000003).
 *
 * A float keeps its point or exponent ({@code 1.}, {@code .5},
 * {@code 1e-3}, {@code 2.0f}); an int never gets one and keeps its base
 * ({@code 0x1F}, {@code 017}); a uint keeps its {@code u}. A minus is
 * not part of the literal: {@link #write} puts one in front of a
 * negative value.
 */
public final class LiteralText {
	private LiteralText() {
	}

	/** Return the type of literal, or null when it is none. */
	@Nullable
	public static Tuning.Type type(@NonNull String literal) {
		NumberLiteral number = literal.isEmpty()
				? null
				: NumberLiteral.parse(literal);
		return number != null ? number.type() : null;
	}

	/**
	 * Return the exact value of literal, or null when it is none: an int
	 * is its bit pattern read as signed, as GLSL ES 3.00 (4.1.3) says, so
	 * 0xffffffff is -1, as the shader has it.
	 */
	@Nullable
	public static BigDecimal value(@NonNull String literal) {
		NumberLiteral number = literal.isEmpty()
				? null
				: NumberLiteral.parse(literal);
		if (number == null) {
			return null;
		}
		return number.type() == Tuning.Type.FLOAT
				? new BigDecimal(mantissaAndExponent(literal))
				: BigDecimal.valueOf((long) number.value());
	}

	/**
	 * Return value written in the style of literal: as many decimals as
	 * step or the literal has, whichever has more; a float keeps its
	 * point, exponent and suffix, an int its base and suffix. A
	 * negative value starts with a minus.
	 */
	@NonNull
	public static String write(@NonNull String literal,
			@NonNull BigDecimal value, @NonNull BigDecimal step) {
		Tuning.Type type = type(literal);
		if (type == null) {
			throw new IllegalArgumentException("not a number: " + literal);
		}
		return type == Tuning.Type.FLOAT
				? writeFloat(literal, value, step)
				: writeInt(literal, value);
	}

	/**
	 * Return what was typed as a literal of the type of literal, with
	 * as many decimals as typed, or null when it is no number of that
	 * type. A float gets a point when it has none; an int keeps the
	 * literal's suffix. A minus in front is the value's sign.
	 */
	@Nullable
	public static String typed(@NonNull String literal,
			@NonNull String typed) {
		Tuning.Type type = type(literal);
		String text = typed.trim();
		boolean negative = text.startsWith("-");
		if (negative) {
			text = text.substring(1);
		}
		if (type == null || text.isEmpty() || text.startsWith("-") ||
				text.startsWith("+")) {
			return null;
		}
		BigDecimal value;
		try {
			value = new BigDecimal(text);
		} catch (NumberFormatException e) {
			return null;
		}
		String sign = negative && value.signum() != 0 ? "-" : "";
		if (type != Tuning.Type.FLOAT) {
			if (text.indexOf('.') > -1 || text.indexOf('e') > -1 ||
					text.indexOf('E') > -1 ||
					TuningStep.clamp(sign.isEmpty() ? value : value.negate(),
							type).compareTo(sign.isEmpty()
							? value
							: value.negate()) != 0) {
				return null;
			}
			return sign + value.toBigInteger() + suffix(literal);
		}
		// Beyond what a float holds, as 1e99, is no float of the shader.
		if (value.abs().compareTo(new BigDecimal(Float.MAX_VALUE)) > 0) {
			return null;
		}
		boolean point = text.indexOf('.') > -1 ||
				text.indexOf('e') > -1 || text.indexOf('E') > -1;
		return sign + text + (point ? "" : ".0") + suffix(literal);
	}

	/** Return the decimals literal shows, in its own notation. */
	static int decimals(@NonNull String literal) {
		String mantissa = mantissa(withoutSuffix(literal));
		int point = mantissa.indexOf('.');
		return point < 0 ? 0 : mantissa.length() - point - 1;
	}

	/** Return the exponent literal is written with, or 0. */
	static int exponent(@NonNull String literal) {
		String digits = withoutSuffix(literal);
		int e = exponentAt(digits);
		return e < 0 ? 0 : Integer.parseInt(stripPlus(digits.substring(e + 1)));
	}

	/** Return whether literal is a float in exponent form. */
	static boolean hasExponent(@NonNull String literal) {
		return type(literal) == Tuning.Type.FLOAT &&
				exponentAt(withoutSuffix(literal)) > -1;
	}

	@NonNull
	private static String writeFloat(@NonNull String literal,
			@NonNull BigDecimal value, @NonNull BigDecimal step) {
		String digits = withoutSuffix(literal);
		String suffix = suffix(literal);
		// Never fewer than the value has: no digit of it is rounded away.
		int stepScale = Math.max(Math.max(0, step.stripTrailingZeros()
				.scale()), value.stripTrailingZeros().scale());
		if (exponentAt(digits) > -1) {
			int exponent = exponent(literal);
			BigDecimal mantissa = value.movePointLeft(exponent);
			int decimals = Math.max(decimals(literal),
					Math.max(0, stepScale + exponent));
			String e = digits.substring(exponentAt(digits), exponentAt(digits) + 1);
			return plain(mantissa, decimals, mantissa(digits)) + e + exponent +
					suffix;
		}
		int decimals = Math.max(decimals(literal), stepScale);
		return plain(value, decimals, digits) + suffix;
	}

	// Writes value with decimals in the style of the mantissa written
	// as like: "1." keeps its bare point, ".5" its missing zero.
	@NonNull
	private static String plain(@NonNull BigDecimal value, int decimals,
			@NonNull String like) {
		String text = value.setScale(decimals, RoundingMode.HALF_EVEN)
				.toPlainString();
		boolean negative = text.startsWith("-");
		String abs = negative ? text.substring(1) : text;
		if (decimals == 0) {
			// A float with no decimals: a bare point when it had one;
			// in exponent form, the exponent makes it a float.
			abs += like.indexOf('.') > -1 ? "." : "";
		} else if (like.startsWith(".") && abs.startsWith("0.")) {
			abs = abs.substring(1);
		}
		if (abs.matches("0*\\.?0*")) {
			// No minus in front of a zero.
			negative = false;
		}
		return (negative ? "-" : "") + abs;
	}

	@NonNull
	private static String writeInt(@NonNull String literal,
			@NonNull BigDecimal value) {
		BigInteger integer = value.setScale(0, RoundingMode.HALF_EVEN)
				.toBigIntegerExact();
		String digits = withoutSuffix(literal);
		String suffix = suffix(literal);
		String sign = integer.signum() < 0 ? "-" : "";
		BigInteger abs = integer.abs();
		if (isHex(digits)) {
			String hex = abs.toString(16);
			boolean upper = !digits.substring(2).equals(
					digits.substring(2).toLowerCase(Locale.ROOT));
			return sign + digits.substring(0, 2) +
					(upper ? hex.toUpperCase(Locale.ROOT) : hex) + suffix;
		} else if (digits.length() > 1 && digits.charAt(0) == '0') {
			return sign + "0" + abs.toString(8) + suffix;
		}
		return sign + abs + suffix;
	}

	@NonNull
	private static String mantissaAndExponent(@NonNull String literal) {
		String digits = withoutSuffix(literal);
		return stripPlus(digits.endsWith(".")
				? digits + "0"
				: digits.startsWith(".") ? "0" + digits : digits)
				.replace(".e", ".0e").replace(".E", ".0E");
	}

	@NonNull
	private static String mantissa(@NonNull String digits) {
		int e = exponentAt(digits);
		return e < 0 ? digits : digits.substring(0, e);
	}

	private static int exponentAt(@NonNull String digits) {
		if (isHex(digits)) {
			return -1;
		}
		int e = digits.indexOf('e');
		return e > -1 ? e : digits.indexOf('E');
	}

	@NonNull
	private static String stripPlus(@NonNull String text) {
		return text.replace("+", "");
	}

	private static boolean isHex(@NonNull String digits) {
		return digits.length() > 1 && digits.charAt(0) == '0' &&
				(digits.charAt(1) == 'x' || digits.charAt(1) == 'X');
	}

	@NonNull
	private static String withoutSuffix(@NonNull String literal) {
		String suffix = suffix(literal);
		return literal.substring(0, literal.length() - suffix.length());
	}

	// The f of a float or the u of a uint, as written; a hex digit F
	// is no suffix.
	@NonNull
	private static String suffix(@NonNull String literal) {
		char last = literal.charAt(literal.length() - 1);
		if (last == 'u' || last == 'U') {
			return String.valueOf(last);
		}
		if ((last == 'f' || last == 'F') && !isHex(literal)) {
			return String.valueOf(last);
		}
		return "";
	}
}
