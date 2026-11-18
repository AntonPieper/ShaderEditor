package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.opengl.Tuning;

/**
 * How far one step of the tuning bar moves a number: a tenth of the
 * number's size at the start, 10^(⌊log10 |v|⌋ − 1), so that 0.3 starts
 * at 0.01, 960.0 at 10 and 1e-3 at 1e-4; an int below 20 at 1; a zero
 * at the place of its last written digit; degrees at 15. After that the
 * step is absolute: each step adds exactly it, in decimal arithmetic.
 */
public final class TuningStep {
	private static final BigDecimal TWENTY = BigDecimal.valueOf(20);
	private static final BigDecimal FIFTEEN = BigDecimal.valueOf(15);
	private static final int[] DEGREES = {1, 5, 15, 45, 90};

	private TuningStep() {
	}

	/**
	 * Return the first step for value, written as literal; degrees
	 * when it is the angle of radians().
	 */
	@NonNull
	public static BigDecimal first(@NonNull String literal,
			@NonNull BigDecimal value, boolean degrees) {
		if (degrees) {
			return FIFTEEN;
		}
		boolean integer = LiteralText.type(literal) != Tuning.Type.FLOAT;
		BigDecimal abs = value.abs();
		if (integer && abs.compareTo(TWENTY) < 0) {
			return BigDecimal.ONE;
		}
		if (abs.signum() == 0) {
			// The place of the last written digit: 0.0 → 0.1, 0. → 1.
			int places = LiteralText.decimals(literal) -
					LiteralText.exponent(literal);
			return BigDecimal.ONE.movePointLeft(places);
		}
		BigDecimal stripped = abs.stripTrailingZeros();
		int magnitude = stripped.precision() - stripped.scale() - 1;
		BigDecimal step = BigDecimal.ONE.movePointRight(magnitude - 1);
		return integer && step.compareTo(BigDecimal.ONE) < 0
				? BigDecimal.ONE
				: normal(step);
	}

	/**
	 * Return the steps the step chip offers around first, smallest
	 * first: a tenth of it, it, 5, 10, 50 and 100 times it; for an int
	 * none below 1; for degrees 1, 5, 15, 45 and 90.
	 */
	@NonNull
	public static List<BigDecimal> menu(@NonNull BigDecimal first,
			boolean integer, boolean degrees) {
		List<BigDecimal> steps = new ArrayList<>();
		if (degrees) {
			for (int degree : DEGREES) {
				steps.add(BigDecimal.valueOf(degree));
			}
			return steps;
		}
		BigDecimal[] factors = {
				new BigDecimal("0.1"), BigDecimal.ONE, BigDecimal.valueOf(5),
				BigDecimal.TEN, BigDecimal.valueOf(50),
				BigDecimal.valueOf(100)};
		for (BigDecimal factor : factors) {
			BigDecimal step = normal(first.multiply(factor));
			if (integer && step.stripTrailingZeros().scale() > 0) {
				continue;
			}
			steps.add(step);
		}
		return steps;
	}

	/**
	 * Return value moved by count steps; an int or uint stops at the
	 * ends of what it holds.
	 */
	@NonNull
	public static BigDecimal move(@NonNull BigDecimal value,
			@NonNull BigDecimal step, long count, @NonNull Tuning.Type type) {
		return clamp(value.add(step.multiply(BigDecimal.valueOf(count))),
				type);
	}

	/** Return value within what a literal of type holds. */
	@NonNull
	static BigDecimal clamp(@NonNull BigDecimal value,
			@NonNull Tuning.Type type) {
		if (type == Tuning.Type.FLOAT) {
			return value;
		}
		BigDecimal min = BigDecimal.valueOf(type == Tuning.Type.INT
				? Integer.MIN_VALUE
				: 0);
		BigDecimal max = BigDecimal.valueOf(type == Tuning.Type.INT
				? Integer.MAX_VALUE
				: 0xffffffffL);
		return value.max(min).min(max);
	}

	/**
	 * Return a step as the step chip shows it: 0.01, 10, 1e-4 written
	 * out as 0.0001.
	 */
	@NonNull
	public static String label(@NonNull BigDecimal step) {
		return normal(step).toPlainString();
	}

	/**
	 * Return text read as a step for a number of type, or null when it
	 * is none: a positive number, whole for an int.
	 */
	public static BigDecimal parse(@NonNull String text,
			@NonNull Tuning.Type type) {
		try {
			BigDecimal step = new BigDecimal(text.trim());
			if (step.signum() <= 0 || (type != Tuning.Type.FLOAT &&
					step.stripTrailingZeros().scale() > 0)) {
				return null;
			}
			return normal(step);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@NonNull
	private static BigDecimal normal(@NonNull BigDecimal step) {
		BigDecimal stripped = step.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}
}
