package de.markusfisch.android.shadereditor.view;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

public class SoftKeyboard {
	public static void hide(Context context, View view) {
		if (context != null && view != null) {
			InputMethodManager imm = ((InputMethodManager) context.getSystemService(
					Context.INPUT_METHOD_SERVICE));
			if (imm != null) {
				imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
			}
		}
	}

	/** Show the keyboard for view, which takes the focus. */
	public static void show(Context context, View view) {
		if (context != null && view != null && view.requestFocus()) {
			InputMethodManager imm = ((InputMethodManager) context.getSystemService(
					Context.INPUT_METHOD_SERVICE));
			if (imm != null) {
				imm.showSoftInput(view, 0);
			}
		}
	}

	/** Return whether an on-screen keyboard is open over view's window. */
	public static boolean isOpen(View view) {
		androidx.core.view.WindowInsetsCompat insets =
				androidx.core.view.ViewCompat.getRootWindowInsets(view);
		return insets != null && insets.isVisible(
				androidx.core.view.WindowInsetsCompat.Type.ime());
	}
}
