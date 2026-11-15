package de.markusfisch.android.shadereditor.navigation;

import java.util.Locale;

/**
 * How a size in bytes is said, the same on every Android version and
 * in every place that says one: in units of 1000, as "88 kB", "1.5 MB"
 * or "17 MB", with one decimal below ten and none from ten on.
 */
public final class Sizes {
	/** The unit a size is said in. */
	public enum Unit {
		BYTES, KILOBYTES, MEGABYTES, GIGABYTES
	}

	/** A size: its number, formatted, and its unit. */
	public record Said(String number, Unit unit) {
	}

	private Sizes() {
	}

	/** bytes as said in the language of locale. */
	public static Said of(long bytes, Locale locale) {
		if (bytes < 1000) {
			return new Said(String.format(locale, "%d", Math.max(0, bytes)),
					Unit.BYTES);
		}
		double value = bytes;
		Unit unit = Unit.BYTES;
		for (Unit next : new Unit[]{Unit.KILOBYTES, Unit.MEGABYTES,
				Unit.GIGABYTES}) {
			// 999,950 bytes are said as 1.0 MB, not as 1000 kB.
			if (unit != Unit.BYTES && Math.round(value) < 1000) {
				break;
			}
			value /= 1000;
			unit = next;
		}
		String number = value < 9.95
				? String.format(locale, "%.1f", value)
				: String.format(locale, "%d", Math.round(value));
		return new Said(number, unit);
	}
}
