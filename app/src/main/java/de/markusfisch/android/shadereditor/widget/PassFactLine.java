package de.markusfisch.android.shadereditor.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import de.markusfisch.android.shadereditor.opengl.PassFacts;
import de.markusfisch.android.shadereditor.project.HostDirective;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

/**
 * What the passes sheet says of a pass, as facts, never as verdicts: a
 * second line only when it says something, and for the pass shown a
 * line of what is worth knowing about it. A pass that is black on
 * purpose reads like any other.
 */
final class PassFactLine {
	/** The words, in the language of the screen. */
	interface Words {
		String error(int line);

		String warning(int line);

		String everyPixel(String values);

		String values(String low, String high);

		String nonFinite();

		String size(int width, int height);

		String unchanged(long seconds);
	}

	// A pass that stays the same this long is said to be unchanged.
	static final long UNCHANGED_NANOS = 3_000_000_000L;
	private static final String MINUS = "−";

	private PassFactLine() {
	}

	/**
	 * The second line of a row, null when nothing is worth saying: the
	 * build's error, else its warning, else NaN, else values outside
	 * 0..1, else one value everywhere, else a size not the screen's.
	 */
	@Nullable
	static String second(@NonNull PassFacts facts, int screenWidth,
			int screenHeight, @NonNull Words words) {
		var problem = problem(facts);
		if (problem != null) {
			return problem.severity() == ProjectDiagnostic.Severity.ERROR
					? words.error(problem.line())
					: words.warning(problem.line());
		} else if (facts.nonFinite()) {
			return words.nonFinite();
		} else if (outside(facts)) {
			return words.values(number(low(facts)), number(high(facts)));
		} else if (facts.same() != null) {
			var same = facts.same();
			var values = new StringBuilder();
			for (int i = 0; i < same.length; ++i) {
				values.append(i > 0 ? ", " : "").append(number(same[i]));
			}
			return words.everyPixel(values.toString());
		} else if (facts.width() > 0 && (facts.width() != screenWidth ||
				facts.height() != screenHeight)) {
			return words.size(facts.width(), facts.height());
		}
		return null;
	}

	/**
	 * The facts line of the pass shown: its format when it is not the
	 * usual one, and how long it has not changed while the picture
	 * runs; null when there is none.
	 */
	@Nullable
	static String shown(@NonNull PassFacts facts, boolean paused,
			@NonNull Words words) {
		// rgba8 is the normal case and says nothing.
		String format = facts.format() != null &&
				facts.format() != HostDirective.Format.RGBA8
				? facts.format().name().toLowerCase(Locale.ROOT)
				: null;
		String unchanged = !paused && facts.unchanged() >= UNCHANGED_NANOS
				? words.unchanged(facts.unchanged() / 1_000_000_000L)
				: null;
		if (format == null) {
			return unchanged;
		}
		return unchanged != null ? format + " · " + unchanged : format;
	}

	/** The build's first error in the pass's file, else its first warning. */
	@Nullable
	static ProjectDiagnostic problem(@NonNull PassFacts facts) {
		ProjectDiagnostic warning = null;
		for (var problem : facts.problems()) {
			if (problem.severity() == ProjectDiagnostic.Severity.ERROR) {
				return problem;
			} else if (warning == null) {
				warning = problem;
			}
		}
		return warning;
	}

	/** Whether red, green or blue leave 0..1, which the screen cuts. */
	static boolean outside(@NonNull PassFacts facts) {
		return facts.low() != null && facts.high() != null &&
				(low(facts) < 0 || high(facts) > 1);
	}

	/** The lowest value of red, green and blue. */
	static float low(@NonNull PassFacts facts) {
		var low = facts.low();
		return Math.min(low[0], Math.min(low[1], low[2]));
	}

	/** The highest value of red, green and blue. */
	static float high(@NonNull PassFacts facts) {
		var high = facts.high();
		return Math.max(high[0], Math.max(high[1], high[2]));
	}

	/**
	 * A value as it is read: whole numbers whole, else two decimals
	 * under 10, one under 100 and none above; a real minus sign.
	 */
	@NonNull
	static String number(float value) {
		String text;
		float size = Math.abs(value);
		if (value == Math.rint(value) && size < 1e6f) {
			text = String.valueOf((long) size);
		} else if (size < 10) {
			text = String.format(Locale.ROOT, "%.2f", size);
		} else if (size < 100) {
			text = String.format(Locale.ROOT, "%.1f", size);
		} else {
			text = String.format(Locale.ROOT, "%.0f", size);
		}
		return value < 0 && !text.matches("[0.]*") ? MINUS + text : text;
	}
}
