package de.markusfisch.android.shadereditor.widget;

import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.editor.AccessibleText;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What a CodeView tells accessibility services, as the platform's
 * EditText does: one node, an editable multi-line text field, whose
 * text is the window AccessibleText gives for the selection's head and
 * whose selection is the document's in that window. The line numbers
 * are not part of the text. The framework calls it only while a service
 * is enabled.
 */
final class CodeAccessibility extends View.AccessibilityDelegate {
	private final CodeView view;
	// The text of the last window, while its text is the document's.
	private Text windowOf;
	private AccessibleText.Window window;
	private String windowText;

	CodeAccessibility(CodeView view) {
		this.view = view;
	}

	@Override
	public void onInitializeAccessibilityNodeInfo(@NonNull View host,
			@NonNull AccessibilityNodeInfo info) {
		super.onInitializeAccessibilityNodeInfo(host, info);
		Document document = view.getDocument();
		info.setEditable(true);
		info.setMultiLine(true);
		info.setInputType(view.inputType());
		if (document == null) {
			return;
		}
		Document.Selection selection = document.selection();
		int head = selection.head();
		AccessibleText.Window window = AccessibleText.window(document.text(),
				head, head);
		String text = text(document.text(), window);
		info.setText(text.isEmpty() ? null : text);
		if (text.isEmpty()) {
			return;
		}
		info.setTextSelection(window.index(selection.anchor()),
				window.index(selection.head()));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			info.setTextSelectable(true);
		}
	}

	// Returns the text of window of text, kept while they stay the same.
	private String text(Text text, AccessibleText.Window window) {
		if (text != windowOf || !window.equals(this.window)) {
			windowOf = text;
			this.window = window;
			windowText = text.substring(window.start(), window.end());
		}
		return windowText;
	}
}
