package de.markusfisch.android.shadereditor.widget;

import java.util.Arrays;

// In the order the platform's EditText shows them. Actions are ids of
// android.R.id, constants, so this runs on the JVM.
final class TextActions {
	private TextActions() {
	}

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
