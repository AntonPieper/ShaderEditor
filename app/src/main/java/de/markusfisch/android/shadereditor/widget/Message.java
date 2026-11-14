package de.markusfisch.android.shadereditor.widget;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.snackbar.Snackbar;

import de.markusfisch.android.shadereditor.R;

/**
 * Reports the result of what the user did in a screen of the app: a
 * snackbar at the bottom, above the extra keys or the navigation bar.
 */
public final class Message {
	private Message() {
	}

	public static void show(@NonNull Activity activity, @StringRes int text) {
		show(activity, activity.getText(text));
	}

	public static void show(@NonNull Activity activity,
			@NonNull CharSequence text) {
		make(activity, text, Snackbar.LENGTH_LONG).show();
	}

	@NonNull
	public static Snackbar make(@NonNull Activity activity,
			@NonNull CharSequence text, int duration) {
		Snackbar snackbar = Snackbar.make(root(activity), text, duration);
		View anchor = anchor(activity);
		if (anchor != null) {
			snackbar.setAnchorView(anchor);
		}
		return snackbar;
	}

	@NonNull
	private static View root(@NonNull Activity activity) {
		View root = activity.findViewById(R.id.main_coordinator);
		if (root == null) {
			root = activity.findViewById(R.id.content_frame);
		}
		return root != null
				? root
				: activity.findViewById(android.R.id.content);
	}

	// The strip behind the navigation bar grows with the keyboard, so
	// a message is above both; the extra keys are above that strip.
	@Nullable
	private static View anchor(@NonNull Activity activity) {
		View keys = activity.findViewById(R.id.extra_keys);
		if (keys != null && keys.getVisibility() == View.VISIBLE) {
			return keys;
		}
		View navbar = activity.findViewById(R.id.navbar);
		return navbar != null && navbar.getVisibility() == View.VISIBLE
				? navbar
				: null;
	}
}
