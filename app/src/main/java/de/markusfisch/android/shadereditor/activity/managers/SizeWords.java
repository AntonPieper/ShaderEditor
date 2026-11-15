package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Locale;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Sizes;

/** A size as the drawer and its sheets say it, by {@link Sizes}. */
final class SizeWords {
	private SizeWords() {
	}

	@NonNull
	static String of(@NonNull Context context, long bytes) {
		Sizes.Said said = Sizes.of(bytes, Locale.getDefault());
		return context.getString(switch (said.unit()) {
			case BYTES -> R.string.nav_size_bytes;
			case KILOBYTES -> R.string.nav_size_kilobytes;
			case MEGABYTES -> R.string.nav_size_megabytes;
			case GIGABYTES -> R.string.nav_size_gigabytes;
		}, said.number());
	}
}
