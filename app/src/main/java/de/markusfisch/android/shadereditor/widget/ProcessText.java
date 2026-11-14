package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.Menu;
import android.view.MenuItem;

/**
 * What other apps offer for selected text, their activities for
 * Intent.ACTION_PROCESS_TEXT, as the platform's text views list them in
 * the floating toolbar: after its own actions, in its overflow, each
 * item with the intent that starts its activity.
 */
final class ProcessText {
	// As the platform's ACTION_MODE_MENU_ITEM_ORDER_PROCESS_TEXT_...
	private static final int ORDER = 100;

	private ProcessText() {
	}

	/**
	 * Add an item for each activity to menu; readOnly tells the
	 * activities that no text they return will be used.
	 */
	static void addTo(Menu menu, Context context, boolean readOnly) {
		PackageManager pm = context.getPackageManager();
		int order = ORDER;
		for (ResolveInfo info : pm.queryIntentActivities(intent(), 0)) {
			ActivityInfo activity = info.activityInfo;
			if (!context.getPackageName().equals(activity.packageName) &&
					!(activity.exported && (activity.permission == null ||
							context.checkSelfPermission(activity.permission) ==
									PackageManager.PERMISSION_GRANTED))) {
				continue;
			}
			menu.add(Menu.NONE, Menu.NONE, order++, info.loadLabel(pm))
					.setIntent(intent()
							.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,
									readOnly)
							.setClassName(activity.packageName,
									activity.name))
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		}
	}

	private static Intent intent() {
		return new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
	}
}
