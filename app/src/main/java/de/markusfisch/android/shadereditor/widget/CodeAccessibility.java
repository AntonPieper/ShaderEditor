package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.AccessibleText;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Motion;
import de.markusfisch.android.shadereditor.editor.Text;

// What a CodeView tells accessibility services, as the platform's EditText
// does: one editable multi-line node whose text is the lines AccessibleText
// reads for the selection, without the line numbers, and whose state is the
// caret's line. The first error of a result is announced once, as the row that
// shows it is not part of the text; focus does not read it again. The
// framework calls it only while a service is enabled, and the view tells it of
// changes only then.
final class CodeAccessibility extends View.AccessibilityDelegate {
	private static final int GRANULARITIES =
			AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH |
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE;
	// At most this much of a selection is said.
	private static final int SAID_CHARS = 200;
	// The actions of the fixes of the caret's line, in their order.
	private static final int[] FIXES = {R.id.row_fix, R.id.row_fix_2};

	private final CodeView view;
	private final Motion motion = new Motion();
	// The text of the last window, while its text is the document's.
	private Text windowOf;
	private AccessibleText.Window window;
	private String windowText;
	// The window of the last selection event, in the text it was of. A
	// service says what a move passed over from the text of the selection
	// event, between the index of the event before and the new one, so the
	// window stays the same while it holds the selection.
	private Text toldOf;
	private AccessibleText.Window toldWindow;
	private String toldText;
	// The selection the last event reported.
	private Document.Selection reported;
	// The first change of an edit told by tell.
	private boolean telling;
	private Document.Change told;
	// The error announced last, while results have errors.
	private String announced;
	// A jump made while a sheet over the view had focus: it lands when the
	// view's window has focus again, after the service put its focus back.
	private boolean landing;
	// What the landing of a jump says, until it is said: the code's focus
	// coming back says it then, not the lines around the caret.
	private String landed;
	// While a jump moves the caret: a service hears where it landed, not
	// every line between where the caret was and where it went.
	private boolean jumping;

	CodeAccessibility(CodeView view) {
		this.view = view;
	}

	@Override
	public void onInitializeAccessibilityNodeInfo(@NonNull View host,
			@NonNull AccessibilityNodeInfo info) {
		super.onInitializeAccessibilityNodeInfo(host, info);
		Document document = view.getDocument();
		// While a tap tunes, the code takes no input: a double-tap, or one
		// held, says the number being tuned, and nothing is selected.
		boolean tunes = view.hearsTaps();
		String said = tunes
				? host.getContext().getString(R.string.a11y_tuning_code)
				: null;
		info.setEditable(!tunes);
		info.setMultiLine(true);
		info.setInputType(view.inputType());
		info.setClickable(true);
		info.addAction(new AccessibilityAction(
				AccessibilityNodeInfo.ACTION_CLICK, said));
		info.setLongClickable(true);
		info.addAction(new AccessibilityAction(
				AccessibilityNodeInfo.ACTION_LONG_CLICK, said));
		addScrollActions(host, info);
		if (document == null) {
			return;
		}
		info.addAction(AccessibilityAction.ACTION_SET_TEXT);
		CharSequence error = view.caretLineError();
		if (error != null) {
			info.setContentInvalid(true);
			List<String> fixes = view.caretLineFixes();
			for (int i = 0; i < fixes.size(); ++i) {
				info.addAction(new AccessibilityAction(FIXES[i],
						fixes.get(i)));
			}
		}
		String word = view.lookUpWord();
		CodeView.Names names = view.names();
		String name = word == null && names != null ? names.name() : null;
		if (word != null || name != null) {
			info.addAction(new AccessibilityAction(R.id.look_up, host
					.getContext().getString(R.string.a11y_look_up,
							word != null ? word : name)));
		}
		// The name at the caret, without a selection: what its strip
		// and the keys offer.
		if (name != null) {
			Context context = host.getContext();
			info.addAction(new AccessibilityAction(R.id.name_definition,
					context.getString(R.string.a11y_definition_of, name)));
			if (names.listsUses()) {
				info.addAction(new AccessibilityAction(R.id.name_uses,
						context.getString(R.string.a11y_uses_of, name)));
			}
			if (names.renames()) {
				info.addAction(new AccessibilityAction(R.id.name_rename,
						context.getString(R.string.a11y_rename_name, name)));
			}
		}
		String back = names != null ? names.wayBack() : null;
		if (back != null) {
			info.addAction(new AccessibilityAction(R.id.way_back, back));
		}
		// The strip offers it only while the caret is in the path, which
		// reading by lines or words seldom leaves it in.
		String link = view.caretLineLink();
		if (link != null) {
			info.addAction(new AccessibilityAction(R.id.open_link, host
					.getContext().getString(R.string.a11y_reference_open,
							link)));
		}
		if (host.isFocused() &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			info.addAction(AccessibilityAction.ACTION_IME_ENTER);
		}
		Document.Selection selection = document.selection();
		Text all = document.text();
		AccessibleText.Window window = reading(all, selection);
		String text = text(all, window);
		info.setText(text.isEmpty() ? null : text);
		AccessibilityNodeInfoCompat.wrap(info).setStateDescription(
				host.getContext().getString(error != null
								? R.string.a11y_code_line_error
								: R.string.a11y_code_line,
						all.lineOf(selection.head()) + 1, all.lineCount()));
		if (!text.isEmpty()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				info.setAvailableExtraData(Collections.singletonList(
						AccessibilityNodeInfo
								.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY));
			}
			info.setTextSelection(window.index(selection.anchor()),
					window.index(selection.head()));
			info.addAction(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
			info.addAction(
					AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
			info.setMovementGranularities(GRANULARITIES);
			if (!tunes) {
				info.addAction(AccessibilityAction.ACTION_SET_SELECTION);
			}
			if (!tunes && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				info.setTextSelectable(true);
			}
		}
		if (host.isFocused()) {
			// In the order of TextView's.
			int[] offered = view.offered();
			if (offers(offered, android.R.id.copy)) {
				info.addAction(AccessibilityAction.ACTION_COPY);
			}
			if (offers(offered, android.R.id.paste)) {
				info.addAction(AccessibilityAction.ACTION_PASTE);
			}
			if (offers(offered, android.R.id.cut)) {
				info.addAction(AccessibilityAction.ACTION_CUT);
			}
		}
	}

	// The framework calls the view's addExtraDataToAccessibilityNodeInfo, not
	// the delegate's.
	void addExtraData(AccessibilityNodeInfo info, String key,
			Bundle arguments) {
		Document document = view.getDocument();
		if (document == null || arguments == null || !AccessibilityNodeInfo
				.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY.equals(key)) {
			return;
		}
		AccessibleText.Window window = reading(document.text(),
				document.selection());
		int start = arguments.getInt(AccessibilityNodeInfo
				.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, -1);
		int length = arguments.getInt(AccessibilityNodeInfo
				.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1);
		if (length <= 0 || start < 0 || start >= window.length()) {
			return;
		}
		RectF[] bounds = new RectF[Math.min(length, window.length() - start)];
		view.characterBounds(window.start() + start, bounds);
		info.getExtras().putParcelableArray(key, bounds);
	}

	@Override
	public void onInitializeAccessibilityEvent(@NonNull View host,
			@NonNull AccessibilityEvent event) {
		super.onInitializeAccessibilityEvent(host, event);
		Document document = view.getDocument();
		if (document == null) {
			return;
		}
		switch (event.getEventType()) {
			case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
				Document.Selection selection = document.selection();
				AccessibleText.Window window = told(document.text(),
						selection.start(), selection.end());
				if (window.length() > 0) {
					event.setFromIndex(window.index(selection.anchor()));
					event.setToIndex(window.index(selection.head()));
					event.setItemCount(window.length());
				}
			}
			case AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
				event.setScrollable(host.canScrollVertically(-1) ||
						host.canScrollVertically(1) ||
						host.canScrollHorizontally(-1) ||
						host.canScrollHorizontally(1));
				event.setScrollX(host.getScrollX());
				event.setScrollY(host.getScrollY());
				event.setMaxScrollX(Math.max(0,
						view.computeHorizontalScrollRange() - host.getWidth()));
				event.setMaxScrollY(Math.max(0,
						view.computeVerticalScrollRange() - host.getHeight()));
			}
			default -> {
			}
		}
	}

	@Override
	public void sendAccessibilityEvent(@NonNull View host, int eventType) {
		if (eventType ==
				AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) {
			view.forgetExplored();
		}
		super.sendAccessibilityEvent(host, eventType);
	}

	@Override
	public void onPopulateAccessibilityEvent(@NonNull View host,
			@NonNull AccessibilityEvent event) {
		super.onPopulateAccessibilityEvent(host, event);
		Document document = view.getDocument();
		if (document == null) {
			return;
		}
		if (landed != null &&
				event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
			event.getText().add(landed);
			landed = null;
			return;
		}
		Document.Selection selection = document.selection();
		Text all = document.text();
		String text = event.getEventType() ==
				AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
				? toldText(all, told(all, selection.start(), selection.end()))
				: text(all, reading(all, selection));
		if (!text.isEmpty()) {
			event.getText().add(text);
		}
	}

	// As TextView does when its text is set.
	void documentChanged() {
		Document document = view.getDocument();
		reported = document != null ? document.selection() : null;
		ViewParent parent = view.getParent();
		if (parent != null && isEnabled()) {
			parent.notifySubtreeAccessibilityStateChanged(view, view,
					AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT);
		}
	}

	// A service drops a text change of a view in a window that is not
	// active, as the code's is while the menu that asked for an undo is
	// open: what done changed is announced instead.
	void tell(int done, Runnable edit) {
		telling = true;
		told = null;
		try {
			edit.run();
		} finally {
			telling = false;
		}
		if (told != null && isEnabled()) {
			view.announceForAccessibility(view.getContext().getString(done,
					describe(told)));
		}
		told = null;
	}

	private String describe(Document.Change change) {
		Context context = view.getContext();
		if (change.inserted().length() == 0) {
			return context.getString(R.string.a11y_deleted,
					say(change.removed()));
		} else if (change.removed().length() == 0) {
			return say(change.inserted());
		}
		return context.getString(R.string.a11y_replaced,
				say(change.inserted()), say(change.removed()));
	}

	private String say(Text text) {
		int lines = text.lineCount();
		return lines > 1
				? view.getContext().getString(R.string.a11y_lines, lines)
				: text.toString();
	}

	// As TextView does while it has focus.
	void changed(Document.Change change, Text before) {
		if (telling && told == null) {
			told = change;
		}
		ViewParent parent = view.getParent();
		Document document = view.getDocument();
		if (parent == null || document == null || !view.isFocused() ||
				!view.isShown() || !isEnabled()) {
			return;
		}
		Text after = document.text();
		AccessibleText.Edit edit = AccessibleText.edit(before, after, change);
		AccessibilityEvent event = AccessibilityEvent.obtain(
				AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
		view.onInitializeAccessibilityEvent(event);
		event.setBeforeText(before.substring(edit.before().start(),
				edit.before().end()));
		String text = text(after, edit.after());
		if (!text.isEmpty()) {
			event.getText().add(text);
		}
		event.setFromIndex(edit.from());
		event.setRemovedCount(edit.removed());
		event.setAddedCount(edit.added());
		parent.requestSendAccessibilityEvent(view, event);
	}

	void updated() {
		Document document = view.getDocument();
		if (document == null || !isEnabled()) {
			return;
		}
		Document.Selection selection = document.selection();
		if (selection.equals(reported)) {
			return;
		}
		reported = selection;
		if (jumping) {
			return;
		}
		// A service reads the selection of a view with neither focus only
		// when it gets to the event, and by then a reload may have given
		// the code the focus: all the text between is read out.
		if (view.isFocused() || view.isAccessibilityFocused()) {
			view.sendAccessibilityEvent(
					AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED);
		}
	}

	@Override
	public boolean performAccessibilityAction(@NonNull View host, int action,
			Bundle arguments) {
		Document document = view.getDocument();
		if (document == null) {
			return super.performAccessibilityAction(host, action, arguments);
		} else if (action == R.id.look_up) {
			CodeView.Names names = view.names();
			if (!document.selection().isEmpty() || names == null ||
					names.name() == null) {
				return view.lookUpSelection();
			}
			names.lookUp();
			return true;
		} else if (action == R.id.name_definition ||
				action == R.id.name_uses || action == R.id.name_rename) {
			CodeView.Names names = view.names();
			if (names == null || names.name() == null) {
				return false;
			} else if (action == R.id.name_definition) {
				names.definition();
			} else {
				names.uses(action == R.id.name_rename);
			}
			return true;
		} else if (action == R.id.way_back) {
			CodeView.Names names = view.names();
			return names != null && names.back();
		} else if (action == R.id.open_link) {
			return view.openCaretLineLink();
		}
		for (int i = 0; i < FIXES.length; ++i) {
			if (action == FIXES[i]) {
				return view.applyCaretLineFix(i);
			}
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
			case AccessibilityNodeInfo.ACTION_COPY:
				return textAction(host, android.R.id.copy);
			case AccessibilityNodeInfo.ACTION_PASTE:
				return textAction(host, android.R.id.paste);
			case AccessibilityNodeInfo.ACTION_CUT:
				return textAction(host, android.R.id.cut);
			case AccessibilityNodeInfo.ACTION_SET_TEXT:
				// A service sets the node's text, which is its window.
				AccessibleText.Window window = reading(document.text(),
						document.selection());
				return view.replaceForAccessibility(window.start(),
						window.end(), arguments != null
								? arguments.getCharSequence(AccessibilityNodeInfo
								.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
								: null);
			case AccessibilityNodeInfo.ACTION_CLICK:
				return view.clickForAccessibility();
			case AccessibilityNodeInfo.ACTION_LONG_CLICK:
				return view.longClickForAccessibility();
			case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
				return view.scrollByPage(0, 1);
			case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
				return view.scrollByPage(0, -1);
			default:
				break;
		}
		if (action == android.R.id.accessibilityActionScrollDown) {
			return view.scrollByPage(0, 1);
		} else if (action == android.R.id.accessibilityActionScrollUp) {
			return view.scrollByPage(0, -1);
		} else if (action == android.R.id.accessibilityActionScrollRight) {
			return view.scrollByPage(1, 0);
		} else if (action == android.R.id.accessibilityActionScrollLeft) {
			return view.scrollByPage(-1, 0);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
				action == android.R.id.accessibilityActionImeEnter) {
			if (host.isFocused()) {
				view.enter();
			}
			return true;
		}
		return super.performAccessibilityAction(host, action, arguments);
	}

	// The window of the last selection event if it holds from to to in text,
	// else the window of a change there.
	private AccessibleText.Window told(Text text, int from, int to) {
		if (text != toldOf || from < toldWindow.start() ||
				to > toldWindow.end()) {
			toldOf = text;
			toldWindow = AccessibleText.window(text, from, to);
			toldText = null;
		}
		return toldWindow;
	}

	private String toldText(Text text, AccessibleText.Window window) {
		if (toldText == null) {
			toldText = text.substring(window.start(), window.end());
		}
		return toldText;
	}

	private static AccessibleText.Window reading(Text text,
			Document.Selection selection) {
		return AccessibleText.reading(text, selection.start(),
				selection.end());
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
		sendTraversed(text, segment, action, granularity);
		return true;
	}

	private void sendTraversed(Text text, AccessibleText.Segment segment,
			int action, int granularity) {
		ViewParent parent = view.getParent();
		if (parent == null || !isEnabled()) {
			return;
		}
		// A service reads it against the node it gets for it.
		AccessibleText.Window window = reading(text,
				view.getDocument().selection());
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
	// end, the selection shown with its toolbar. A service's select all, or a
	// move to the start or end, therefore reaches the window's ends, not the
	// text's: nothing tells those apart from the same range chosen there.
	private boolean setSelection(Document document, Bundle arguments) {
		int start = arguments != null ? arguments.getInt(
				AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
				-1) : -1;
		int end = arguments != null ? arguments.getInt(
				AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
				-1) : -1;
		Document.Selection selection = document.selection();
		AccessibleText.Window window = reading(document.text(), selection);
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

	private boolean textAction(View host, int id) {
		return host.isFocused() && offers(view.offered(), id) &&
				view.toolbarAction(id);
	}

	// first is null for none. It is not announced twice, and services are told
	// that the error of the caret's line may have changed without it being
	// spoken again.
	void errorsChanged(String first) {
		if (first == null) {
			announced = null;
		}
		if (!isEnabled()) {
			return;
		}
		if (first != null && !first.equals(announced) && view.isShown()) {
			announced = first;
			view.announceForAccessibility(first);
		}
		ViewParent parent = view.getParent();
		if (parent != null) {
			parent.notifySubtreeAccessibilityStateChanged(view, view,
					AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
		}
	}

	// After a jump, a service's focus goes to the code, from where it would
	// be after the sheet or menu that asked for the jump.
	void jumped() {
		jumping = false;
		Document document = view.getDocument();
		if (!isEnabled() || document == null) {
			return;
		}
		Text text = document.text();
		int line = text.lineOf(document.selection().head());
		String content = text.line(line);
		landed = content.trim().isEmpty()
				? view.getContext().getString(R.string.a11y_landed_blank,
				line + 1)
				: view.getContext().getString(R.string.a11y_landed, line + 1,
				content);
		if (view.hasWindowFocus()) {
			land();
		} else {
			landing = true;
		}
	}

	// A jump is about to move the caret; jumped() follows, or the next
	// frame ends it.
	void jumping() {
		if (isEnabled() && !jumping) {
			jumping = true;
			view.post(() -> jumping = false);
		}
	}

	void windowFocused() {
		if (landing) {
			landing = false;
			view.post(this::land);
		}
	}

	// Said here, unless the code's focus coming back said it.
	private void land() {
		if (!view.isShown()) {
			return;
		}
		view.performAccessibilityAction(
				AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
		if (landed != null) {
			view.announceForAccessibility(landed);
			landed = null;
		}
	}

	private boolean isEnabled() {
		AccessibilityManager manager = manager();
		return manager != null && manager.isEnabled();
	}

	// A service tells of a selection it made itself, not of one made by its
	// long press.
	void selectedByTouch() {
		Document document = view.getDocument();
		if (document == null) {
			return;
		}
		Document.Selection selection = document.selection();
		int end = AccessibleText.cut(document.text(), Math.min(selection.end(),
				selection.start() + SAID_CHARS));
		view.announceForAccessibility(view.getContext().getString(
				R.string.a11y_selected, document.text().substring(
						selection.start(), end)));
	}

	boolean exploresByTouch() {
		AccessibilityManager manager = manager();
		return manager != null && manager.isTouchExplorationEnabled();
	}

	private AccessibilityManager manager() {
		return (AccessibilityManager) view.getContext().getSystemService(
				Context.ACCESSIBILITY_SERVICE);
	}

	private static void addScrollActions(View host,
			AccessibilityNodeInfo info) {
		boolean up = host.canScrollVertically(-1);
		boolean down = host.canScrollVertically(1);
		boolean left = host.canScrollHorizontally(-1);
		boolean right = host.canScrollHorizontally(1);
		info.setScrollable(up || down || left || right);
		if (up) {
			info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD);
			info.addAction(AccessibilityAction.ACTION_SCROLL_UP);
		}
		if (down) {
			info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD);
			info.addAction(AccessibilityAction.ACTION_SCROLL_DOWN);
		}
		if (left) {
			info.addAction(AccessibilityAction.ACTION_SCROLL_LEFT);
		}
		if (right) {
			info.addAction(AccessibilityAction.ACTION_SCROLL_RIGHT);
		}
	}

	private static boolean offers(int[] offered, int id) {
		for (int o : offered) {
			if (o == id) {
				return true;
			}
		}
		return false;
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
