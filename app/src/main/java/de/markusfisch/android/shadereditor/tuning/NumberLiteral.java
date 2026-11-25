package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.opengl.Tuning;

/**
 * One number literal of GLSL code, by the tokens the editor shows: a
 * float with a point, an exponent or an f, an int in decimal, octal or
 * hex, and a uint with its u. A minus in front is an operator, not part
 * of it.
 */
public record NumberLiteral(@NonNull Tuning.Type type, double value) {
	// The largest number a uint, or an int's bits, holds.
	private static final long MAX_BITS = 0xffffffffL;

	/** Return the number literal stands for, or null when it is none. */
	@Nullable
	static NumberLiteral parse(@NonNull String literal) {
		int length = literal.length();
		char last = literal.charAt(length - 1);
		boolean hex = length > 1 && literal.charAt(0) == '0' &&
				(literal.charAt(1) == 'x' || literal.charAt(1) == 'X');
		try {
			if (!hex && (literal.indexOf('.') > -1 ||
					literal.indexOf('e') > -1 || literal.indexOf('E') > -1)) {
				double value = Double.parseDouble(last == 'f' || last == 'F'
						? literal.substring(0, length - 1)
						: literal);
				return Double.isInfinite(value)
						? null
						: new NumberLiteral(Tuning.Type.FLOAT, value);
			}
			boolean unsigned = last == 'u' || last == 'U';
			String digits = unsigned ? literal.substring(0, length - 1) : literal;
			long value;
			if (hex) {
				value = Long.parseLong(digits.substring(2), 16);
			} else if (digits.length() > 1 && digits.charAt(0) == '0') {
				value = Long.parseLong(digits.substring(1), 8);
			} else {
				value = Long.parseLong(digits);
			}
			if (value > MAX_BITS) {
				return null;
			}
			// An int is its bit pattern read as signed, as GLSL ES 3.00
			// (4.1.3) says: 0xffffffff is -1. A uint keeps it unsigned.
			return unsigned
					? new NumberLiteral(Tuning.Type.UINT, value)
					: new NumberLiteral(Tuning.Type.INT, (int) value);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
