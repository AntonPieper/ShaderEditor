package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.editor.AccessibleText;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Motion;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What a CodeView tells accessibility services, as the platform's
 * EditText does: one node, an editable multi-line text field, whose
 * text is the window AccessibleText gives for the selection's head and
 * whose selection is the document's in that window. The line numbers
 * are not part of the text. Services move the caret by the units of
 * AccessibleText and select, as touch selects. The framework calls it
 * only while a service is enabled.
 */
final class CodeAccessibility extends View.AccessibilityDelegate {
	private static final int GRANULARITIES =
			AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE;

	private final CodeView view;
	private final Motion motion = new Motion();
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
		AccessibleText.Window window = window(document.text(),
				selection.head(), selection.head(), selection.head());
		String text = text(document.text(), window);
		info.setText(text.isEmpty() ? null : text);
		if (!text.isEmpty()) {
			info.setTextSelection(window.index(selection.anchor()),
					window.index(selection.head()));
			info.addAction(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
			info.addAction(
					AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
			info.setMovementGranularities(GRANULARITIES);
			info.addAction(AccessibilityAction.ACTION_SET_SELECTION);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				info.setTextSelectable(true);
			}
		}
	}

	@Override
	public boolean performAccessibilityAction(@NonNull View host, int action,
			Bundle arguments) {
		Document document = view.getDocument();
		if (document == null) {
			return super.performAccessibilityAction(host, action, arguments);
		}
		switch (action) {
			case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
			case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY:
				return arguments != null && traverse(document, action,
						arguments.getInt(AccessibilityNodeInfo
								.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT),
						arguments.getBoolean(AccessibilityNodeInfo
								.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN));
			case AccessibilityNodeInfo.ACTION_SET_SELECTION:
				return setSelection(document, arguments);
			default:
				break;
		}
		return super.performAccessibilityAction(host, action, arguments);
	}

	// Returns the window for a focus from from to to, the window of head
	// if that holds it.
	private static AccessibleText.Window window(Text text, int head,
			int from, int to) {
		AccessibleText.Window window = AccessibleText.window(text, head, head);
		return from >= window.start() && to <= window.end()
				? window
				: AccessibleText.window(text, from, to);
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

	// Moves the selection's head over the next or previous segment of
	// granularity, as View.traverseAtGranularity does.
	private boolean traverse(Document document, int action,
			int granularity, boolean extend) {
		AccessibleText.Unit unit = unit(granularity);
		Text text = document.text();
		if (unit == null || text.length() == 0) {
			return false;
		}
		Document.Selection selection = document.selection();
		boolean forward =
				action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY;
		AccessibleText.Segment segment = forward
				? AccessibleText.following(motion, text, unit,
				selection.head(), view.pageLines())
				: AccessibleText.preceding(motion, text, unit,
				selection.head(), view.pageLines());
		if (segment == null) {
			return false;
		}
		int head = forward ? segment.end() : segment.start();
		view.selectForAccessibility(extend ? selection.anchor() : head, head,
				false);
		sendTraversed(text, head, segment, action, granularity);
		return true;
	}

	private void sendTraversed(Text text, int head,
			AccessibleText.Segment segment, int action, int granularity) {
		ViewParent parent = view.getParent();
		if (parent == null || !isEnabled()) {
			return;
		}
		AccessibleText.Window window = window(text, head, segment.start(),
				segment.end());
		AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent
				.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY);
		view.onInitializeAccessibilityEvent(event);
		event.getText().add(text(text, window));
		event.setFromIndex(window.index(segment.start()));
		event.setToIndex(window.index(segment.end()));
		event.setAction(action);
		event.setMovementGranularity(granularity);
		parent.requestSendAccessibilityEvent(view, event);
	}

	// Selects as TextView does: in the window of the node, start before
	// end, the selection shown with its toolbar.
	private boolean setSelection(Document document, Bundle arguments) {
		int start = arguments != null ? arguments.getInt(
				AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
				-1) : -1;
		int end = arguments != null ? arguments.getInt(
				AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
				-1) : -1;
		Document.Selection selection = document.selection();
		AccessibleText.Window window = window(document.text(),
				selection.head(), selection.head(), selection.head());
		if (start == window.index(selection.anchor()) &&
				end == window.index(selection.head())) {
			return false;
		}
		if (start == -1 && end == -1) {
			// TextView removes the selection; an editor keeps its caret.
			return true;
		}
		if (start < 0 || start > end || end > window.length()) {
			return false;
		}
		view.selectForAccessibility(window.start() + start,
				window.start() + end, true);
		return true;
	}

	private boolean isEnabled() {
		AccessibilityManager manager = (AccessibilityManager) view
				.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
		return manager != null && manager.isEnabled();
	}

	private static AccessibleText.Unit unit(int granularity) {
		return switch (granularity) {
			case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER ->
					AccessibleText.Unit.CHARACTER;
			case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD ->
					AccessibleText.Unit.WORD;
			case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE ->
					AccessibleText.Unit.LINE;
			case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH ->
					AccessibleText.Unit.PARAGRAPH;
			case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE ->
					AccessibleText.Unit.PAGE;
			default -> null;
		};
	}
}
