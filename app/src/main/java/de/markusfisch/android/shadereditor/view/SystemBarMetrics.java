package de.markusfisch.android.shadereditor.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import de.markusfisch.android.shadereditor.R;

public class SystemBarMetrics {
	public static int getToolBarHeight(@NonNull Context context) {
		TypedValue tv = new TypedValue();
		return context.getTheme().resolveAttribute(
				android.R.attr.actionBarSize,
				tv,
				true) ? TypedValue.complexToDimensionPixelSize(
				tv.data,
				context.getResources().getDisplayMetrics()) : 0;
	}

	public static void initMainLayout(
			@NonNull AppCompatActivity activity,
			@Nullable Rect insets) {
		// The system bars stay transparent with light icons; the toolbar
		// and the navbar view draw their backgrounds.
		EdgeToEdge.enable(activity,
				SystemBarStyle.dark(Color.TRANSPARENT),
				SystemBarStyle.dark(Color.TRANSPARENT));

		View mainLayout = activity.findViewById(R.id.main_layout);
		if (mainLayout != null) {
			setWindowInsets(mainLayout,
					activity.findViewById(R.id.toolbar),
					activity.findViewById(R.id.navbar),
					insets);
		}
	}

	private static void setWindowInsets(
			final View mainLayout,
			@Nullable final View toolbar,
			@Nullable final View navbar,
			@Nullable final Rect windowInsets) {
		ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (v, insets) -> {
			Insets bars = insets.getInsets(
					WindowInsetsCompat.Type.systemBars() |
							WindowInsetsCompat.Type.ime());

			int bottom = bottomInset(v, insets);
			mainLayout.setPadding(bars.left, 0, bars.right, bottom);
			if (windowInsets != null) {
				windowInsets.set(bars.left, bars.top, bars.right, bottom);
			}

			// The toolbar lies behind the status bar. It measures its
			// buttons and text below the padding, so it grows with them.
			if (toolbar != null) {
				toolbar.setPadding(toolbar.getPaddingLeft(), bars.top,
						toolbar.getPaddingRight(), toolbar.getPaddingBottom());
			}

			if (navbar != null) {
				navbar.getLayoutParams().height = bottom;
				navbar.requestLayout();
			}

			return insets;
		});
	}

	/**
	 * The inset the navigation bar and the keyboard take at the bottom
	 * of view's window. Before Android 11, in a window resized for the
	 * keyboard, it is the system's own inset, which holds the keyboard
	 * then: the keyboard read from the window's visible area lags behind
	 * when it closes as the focus moves, as when the tuning bar opens,
	 * and no later dispatch tells the views it is gone.
	 */
	@SuppressWarnings("deprecation")
	public static int bottomInset(@NonNull View view,
			@NonNull WindowInsetsCompat insets) {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.R && resizes(view)
				? insets.getSystemWindowInsetBottom()
				: insets.getInsets(WindowInsetsCompat.Type.systemBars() |
						WindowInsetsCompat.Type.ime()).bottom;
	}

	private static boolean resizes(@NonNull View view) {
		ViewGroup.LayoutParams params = view.getRootView().getLayoutParams();
		return params instanceof WindowManager.LayoutParams window &&
				(window.softInputMode &
						WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) ==
						WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
	}
}
