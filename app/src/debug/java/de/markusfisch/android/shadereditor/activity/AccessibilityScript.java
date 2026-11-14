package de.markusfisch.android.shadereditor.activity;

import android.content.Context;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.FrameLayout;

import java.util.Arrays;
import java.util.List;

/**
 * Development harness, debug builds only: performs accessibility
 * actions on a view as a service would, without one, and logs with the
 * tag "CodeViewA11y" the view's node after each, and every accessibility
 * event the view sends while a service is enabled. A script is steps
 * separated by commas, one every 700 ms:
 * <ul>
 * <li>"dump": the node;</li>
 * <li>"next:UNIT", "prev:UNIT", with ":extend" to extend the selection;
 * UNIT is char, word, line, para or page;</li>
 * <li>"select:START:END", indices into the node's text;</li>
 * <li>"copy", "cut", "paste", "enter", "click", "longclick", "focus",
 * "settext:TEXT";</li>
 * <li>"scrollf", "scrollb", "scrollu", "scrolld", "scrolll",
 * "scrollr";</li>
 * <li>"bounds:START:LENGTH": the character bounds the node gives.</li>
 * </ul>
 */
final class AccessibilityScript {
	static final String TAG = "CodeViewA11y";

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final View view;

	private AccessibilityScript(View view) {
		this.view = view;
	}

	/** Return a frame around view that logs the events it sends. */
	static FrameLayout logEvents(Context context, View view) {
		FrameLayout frame = new FrameLayout(context) {
			@Override
			public boolean onRequestSendAccessibilityEvent(View child,
					AccessibilityEvent event) {
				Log.i(TAG, "event " + AccessibilityEvent.eventTypeToString(
						event.getEventType()) + " from=" +
						event.getFromIndex() + " to=" + event.getToIndex() +
						" added=" + event.getAddedCount() + " removed=" +
						event.getRemovedCount() + " items=" +
						event.getItemCount() + " granularity=" +
						event.getMovementGranularity() + " action=" +
						event.getAction() + " scroll=" + event.getScrollY() +
						"/" + event.getMaxScrollY() + " before=" +
						length(event.getBeforeText()) + " text=" +
						summary(event.getText()));
				return super.onRequestSendAccessibilityEvent(child, event);
			}
		};
		frame.addView(view);
		return frame;
	}

	/** Run script on view. */
	static void run(View view, String script) {
		new AccessibilityScript(view).step(Arrays.asList(script.split(",")),
				0);
	}

	private void step(List<String> steps, int i) {
		if (i >= steps.size()) {
			Log.i(TAG, "done");
			return;
		}
		String step = steps.get(i);
		String[] parts = step.split(":", -1);
		Bundle args = new Bundle();
		boolean result;
		switch (parts[0]) {
			case "dump":
				dump();
				result = true;
				break;
			case "next":
			case "prev":
				args.putInt(AccessibilityNodeInfo
								.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
						granularity(parts[1]));
				args.putBoolean(AccessibilityNodeInfo
								.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
						parts.length > 2 && "extend".equals(parts[2]));
				result = view.performAccessibilityAction("next".equals(
						parts[0])
						? AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
						: AccessibilityNodeInfo
						.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args);
				break;
			case "select":
				args.putInt(AccessibilityNodeInfo
						.ACTION_ARGUMENT_SELECTION_START_INT,
						Integer.parseInt(parts[1]));
				args.putInt(AccessibilityNodeInfo
								.ACTION_ARGUMENT_SELECTION_END_INT,
						Integer.parseInt(parts[2]));
				result = view.performAccessibilityAction(
						AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
				break;
			case "settext":
				args.putCharSequence(AccessibilityNodeInfo
						.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, parts[1]);
				result = view.performAccessibilityAction(
						AccessibilityNodeInfo.ACTION_SET_TEXT, args);
				break;
			case "bounds":
				bounds(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
				result = true;
				break;
			case "focus":
				result = view.requestFocus();
				break;
			default:
				result = view.performAccessibilityAction(action(parts[0]),
						null);
				break;
		}
		Log.i(TAG, "step " + step + " -> " + result + " " + state());
		handler.postDelayed(() -> step(steps, i + 1), 700);
	}

	private String state() {
		AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
		CharSequence text = info.getText();
		int start = info.getTextSelectionStart();
		int end = info.getTextSelectionEnd();
		String around = "";
		if (text != null && start >= 0) {
			int from = Math.max(0, Math.min(start, end) - 12);
			int to = Math.min(text.length(), Math.max(start, end) + 12);
			around = text.subSequence(from, Math.min(start, end)) + "[" +
					text.subSequence(Math.min(start, end), Math.max(start,
							end)) + "]" + text.subSequence(Math.max(start, end),
					to);
		}
		return "length=" + length(text) + " selection=" + start + "," + end +
				" around=" + quote(around) + " scroll=" + view.getScrollX() +
				"," + view.getScrollY();
	}

	private void dump() {
		AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
		StringBuilder actions = new StringBuilder();
		for (AccessibilityAction action : info.getActionList()) {
			actions.append(' ').append(name(action.getId()));
		}
		Log.i(TAG, "node class=" + info.getClassName() + " editable=" +
				info.isEditable() + " multiLine=" + info.isMultiLine() +
				" focusable=" + info.isFocusable() + " focused=" +
				info.isFocused() + " clickable=" + info.isClickable() +
				" longClickable=" + info.isLongClickable() + " scrollable=" +
				info.isScrollable() + " inputType=0x" +
				Integer.toHexString(info.getInputType()) + " maxLength=" +
				info.getMaxTextLength() + " granularities=" +
				info.getMovementGranularities() + " hint=" +
				(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
						? info.getHintText() + " extras=" +
						info.getAvailableExtraData()
						: "") + " importantForA11y=" +
				view.getImportantForAccessibility() + " " + state());
		Log.i(TAG, "node actions" + actions);
	}

	private void bounds(int start, int length) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
		Bundle args = new Bundle();
		args.putInt(AccessibilityNodeInfo
				.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start);
		args.putInt(AccessibilityNodeInfo
				.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length);
		String key = AccessibilityNodeInfo
				.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
		view.addExtraDataToAccessibilityNodeInfo(info, key, args);
		Parcelable[] rects = info.getExtras().getParcelableArray(key);
		StringBuilder sb = new StringBuilder();
		if (rects != null) {
			for (Parcelable rect : rects) {
				sb.append(' ').append(rect == null
						? "null"
						: ((RectF) rect).toShortString());
			}
		}
		Log.i(TAG, "bounds " + start + "+" + length + ":" +
				(rects == null ? " none" : sb));
	}

	private static int granularity(String unit) {
		return switch (unit) {
			case "char" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER;
			case "word" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD;
			case "line" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE;
			case "para" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH;
			default -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE;
		};
	}

	private static int action(String name) {
		return switch (name) {
			case "copy" -> AccessibilityNodeInfo.ACTION_COPY;
			case "cut" -> AccessibilityNodeInfo.ACTION_CUT;
			case "paste" -> AccessibilityNodeInfo.ACTION_PASTE;
			case "click" -> AccessibilityNodeInfo.ACTION_CLICK;
			case "longclick" -> AccessibilityNodeInfo.ACTION_LONG_CLICK;
			case "scrollf" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
			case "scrollb" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
			case "scrollu" -> android.R.id.accessibilityActionScrollUp;
			case "scrolld" -> android.R.id.accessibilityActionScrollDown;
			case "scrolll" -> android.R.id.accessibilityActionScrollLeft;
			case "scrollr" -> android.R.id.accessibilityActionScrollRight;
			case "enter" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
					? android.R.id.accessibilityActionImeEnter
					: 0;
			default -> 0;
		};
	}

	private static String name(int id) {
		switch (id) {
			case AccessibilityNodeInfo.ACTION_FOCUS:
				return "focus";
			case AccessibilityNodeInfo.ACTION_CLEAR_FOCUS:
				return "clearFocus";
			case AccessibilityNodeInfo.ACTION_SELECT:
				return "select";
			case AccessibilityNodeInfo.ACTION_CLEAR_SELECTION:
				return "clearSelection";
			case AccessibilityNodeInfo.ACTION_CLICK:
				return "click";
			case AccessibilityNodeInfo.ACTION_LONG_CLICK:
				return "longClick";
			case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
				return "a11yFocus";
			case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
				return "clearA11yFocus";
			case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
				return "nextAtGranularity";
			case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY:
				return "previousAtGranularity";
			case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
				return "scrollForward";
			case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
				return "scrollBackward";
			case AccessibilityNodeInfo.ACTION_COPY:
				return "copy";
			case AccessibilityNodeInfo.ACTION_PASTE:
				return "paste";
			case AccessibilityNodeInfo.ACTION_CUT:
				return "cut";
			case AccessibilityNodeInfo.ACTION_SET_SELECTION:
				return "setSelection";
			case AccessibilityNodeInfo.ACTION_SET_TEXT:
				return "setText";
			default:
				break;
		}
		if (id == android.R.id.accessibilityActionScrollUp) {
			return "scrollUp";
		} else if (id == android.R.id.accessibilityActionScrollDown) {
			return "scrollDown";
		} else if (id == android.R.id.accessibilityActionScrollLeft) {
			return "scrollLeft";
		} else if (id == android.R.id.accessibilityActionScrollRight) {
			return "scrollRight";
		} else if (id == android.R.id.accessibilityActionShowOnScreen) {
			return "showOnScreen";
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
				id == android.R.id.accessibilityActionImeEnter) {
			return "imeEnter";
		}
		return "0x" + Integer.toHexString(id);
	}

	private static int length(CharSequence text) {
		return text == null ? -1 : text.length();
	}

	private static String summary(List<CharSequence> texts) {
		StringBuilder sb = new StringBuilder();
		for (CharSequence text : texts) {
			sb.append(' ').append(length(text));
		}
		return texts.size() + " [" + sb + " ]";
	}

	private static String quote(String s) {
		return "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\"";
	}
}
