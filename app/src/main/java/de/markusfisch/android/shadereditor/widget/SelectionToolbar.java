package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Build;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.RequiresApi;

/**
 * The platform's floating toolbar over the selection or the caret of a
 * view, with the actions its host offers under the platform's names.
 * It hides while the host moves what it points at and comes back a
 * moment after; moving the content rectangle hides it for as long as
 * that moves. Back closes it, as the user's dismissal.
 */
final class SelectionToolbar extends ActionMode.Callback2 {
	interface Host {
		/**
		 * Put the part of the selection on screen into out, in the
		 * view's coordinates; a rectangle outside the view hides the
		 * toolbar.
		 */
		void contentRect(Rect out);

		/** Return the actions to offer now, ids of android.R.id. */
		int[] actions();

		/** Do action id; return whether the toolbar is done. */
		boolean act(int id);

		/** The toolbar closed; dismissed if the user closed it (Back). */
		void closed(boolean dismissed);
	}

	private final View view;
	private final Host host;
	private final Runnable reveal = this::reveal;
	private ActionMode mode;
	private boolean closing;
	// From Android 13 on, the dispatcher Back goes through and the
	// callback that closes the toolbar, while it shows.
	private Object backDispatcher;
	private Object backCallback;

	SelectionToolbar(View view, Host host) {
		this.view = view;
		this.host = host;
	}

	/**
	 * Put the platform's titles of actions into menu, each calling
	 * listener.
	 */
	// As the platform's text views, which offer few enough actions.
	@SuppressLint("AlwaysShowAction")
	static void fill(Menu menu, int[] actions,
			MenuItem.OnMenuItemClickListener listener) {
		menu.clear();
		for (int i = 0; i < actions.length; ++i) {
			int id = actions[i];
			MenuItem item;
			if (id == android.R.id.cut) {
				item = menu.add(Menu.NONE, id, i, android.R.string.cut)
						.setAlphabeticShortcut('x');
			} else if (id == android.R.id.copy) {
				item = menu.add(Menu.NONE, id, i, android.R.string.copy)
						.setAlphabeticShortcut('c');
			} else if (id == android.R.id.paste) {
				item = menu.add(Menu.NONE, id, i, android.R.string.paste)
						.setAlphabeticShortcut('v');
			} else {
				item = menu.add(Menu.NONE, id, i, android.R.string.selectAll)
						.setAlphabeticShortcut('a');
			}
			item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
			if (listener != null) {
				item.setOnMenuItemClickListener(listener);
			}
		}
	}

	boolean isShown() {
		return mode != null;
	}

	/**
	 * Show the toolbar with the actions offered now, a moment after the
	 * last touch if it was hidden.
	 */
	void show() {
		if (mode == null) {
			mode = view.startActionMode(this, ActionMode.TYPE_FLOATING);
			if (mode != null &&
					Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				takeBack();
			}
			return;
		}
		mode.invalidate();
		mode.invalidateContentRect();
		view.removeCallbacks(reveal);
		view.postDelayed(reveal, ViewConfiguration.getDoubleTapTimeout());
	}

	/** Hide the toolbar for a while, for as long as a drag goes on. */
	void hide() {
		if (mode != null) {
			view.removeCallbacks(reveal);
			mode.hide(ActionMode.DEFAULT_HIDE_DURATION);
		}
	}

	/** What the toolbar points at moved. */
	void moved() {
		if (mode != null) {
			mode.invalidateContentRect();
		}
	}

	/**
	 * Close the toolbar as the user does with Back; return whether it
	 * showed.
	 */
	boolean dismiss() {
		if (mode == null) {
			return false;
		}
		mode.finish();
		return true;
	}

	void close() {
		if (mode != null) {
			closing = true;
			try {
				mode.finish();
			} finally {
				closing = false;
			}
		}
	}

	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	private void takeBack() {
		OnBackInvokedDispatcher dispatcher =
				view.findOnBackInvokedDispatcher();
		if (dispatcher != null) {
			OnBackInvokedCallback callback = this::dismiss;
			dispatcher.registerOnBackInvokedCallback(
					OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
			backDispatcher = dispatcher;
			backCallback = callback;
		}
	}

	private void releaseBack() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				backCallback != null) {
			((OnBackInvokedDispatcher) backDispatcher)
					.unregisterOnBackInvokedCallback(
							(OnBackInvokedCallback) backCallback);
			backDispatcher = backCallback = null;
		}
	}

	private void reveal() {
		if (mode != null) {
			mode.hide(0);
		}
	}

	@Override
	public boolean onCreateActionMode(ActionMode mode, Menu menu) {
		mode.setTitle(null);
		mode.setSubtitle(null);
		mode.setTitleOptionalHint(true);
		fill(menu, host.actions(), null);
		return true;
	}

	@Override
	public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
		fill(menu, host.actions(), null);
		return true;
	}

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
		if (host.act(item.getItemId())) {
			close();
		}
		return true;
	}

	@Override
	public void onDestroyActionMode(ActionMode mode) {
		this.mode = null;
		view.removeCallbacks(reveal);
		releaseBack();
		host.closed(!closing);
	}

	@Override
	public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
		host.contentRect(outRect);
	}
}
