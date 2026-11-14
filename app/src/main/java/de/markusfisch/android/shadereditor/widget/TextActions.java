package de.markusfisch.android.shadereditor.widget;

import java.util.Arrays;

/**
 * The actions a CodeView offers on its floating toolbar and context
 * menu, in the order the platform's EditText shows them: cut and copy
 * for a selection, paste while the clipboard holds a clip, share for a
 * selection, and select all unless there is no text or all of it is
 * selected. Actions are
 * ids of android.R.id, constants, so this runs on the JVM.
 */
final class TextActions {
	private TextActions() {
	}

	/**
	 * Return the actions for the selection from start to end of a text
	 * of length; clip tells whether the clipboard holds a clip.
	 */
	static int[] offered(int start, int end, int length, boolean clip) {
		int[] ids = new int[5];
		int n = 0;
		if (start < end) {
			ids[n++] = android.R.id.cut;
			ids[n++] = android.R.id.copy;
		}
		if (clip) {
			ids[n++] = android.R.id.paste;
		}
		if (start < end) {
			ids[n++] = android.R.id.shareText;
		}
		if (length > 0 && (start > 0 || end < length)) {
			ids[n++] = android.R.id.selectAll;
		}
		return Arrays.copyOf(ids, n);
	}
}
