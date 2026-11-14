package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.TimeZone;

/** How a list says when a shader was last changed. */
final class ChangeTimes {
	private static final long MINUTE = 60_000L;
	private static final long DAY = 86_400_000L;

	private ChangeTimes() {
	}

	/**
	 * Today how long ago, at least a minute; yesterday; or the date,
	 * with the year only when it is not this one; null when not known.
	 */
	@Nullable
	static String describe(@NonNull Context context, long changed, long now) {
		if (changed <= 0) {
			return null;
		}
		TimeZone zone = TimeZone.getDefault();
		long days = daysBetween(changed, now, zone);
		if (days == 0) {
			return DateUtils.getRelativeTimeSpanString(
					Math.min(changed, now - MINUTE), now,
					DateUtils.MINUTE_IN_MILLIS).toString();
		}
		if (days == 1) {
			return DateUtils.getRelativeTimeSpanString(
					changed, now, DateUtils.DAY_IN_MILLIS).toString();
		}
		Calendar then = Calendar.getInstance(zone);
		then.setTimeInMillis(changed);
		Calendar today = Calendar.getInstance(zone);
		today.setTimeInMillis(now);
		return DateUtils.formatDateTime(context, changed,
				DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH |
						(then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
								? DateUtils.FORMAT_NO_YEAR
								: DateUtils.FORMAT_SHOW_YEAR));
	}

	/** Calendar days from then to now in the given zone. */
	static long daysBetween(long then, long now, @NonNull TimeZone zone) {
		return (now + zone.getOffset(now)) / DAY -
				(then + zone.getOffset(then)) / DAY;
	}
}
