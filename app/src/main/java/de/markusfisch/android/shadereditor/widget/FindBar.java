package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Search;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;

/**
 * Find in the file shown, in the toolbar's place: a query, the matches
 * of it in the code, and the one the selection is on. What it shows is
 * one {@link State}, changed only through set(), which renders it. The
 * matches are counted off the main thread for the whole text, and a
 * newer query or edit cancels the count; the lines on screen mark their
 * own matches as they are drawn. Closed, it listens to nothing, so
 * typing in the code costs it nothing.
 */
public class FindBar extends LinearLayout {
	/**
	 * What Find is: whether it is open, whether the row that replaces
	 * shows, the query with its options, the matches counted in the text
	 * shown, or null while they are counted, and where the current match
	 * starts: -1 for none, and {@link Search#UNKNOWN} while it is looked
	 * for with the count.
	 */
	record State(boolean open, boolean replacing,
			@NonNull Search.Query query, @Nullable Search.Matches matches,
			int current) {
		static final State CLOSED = new State(false, false,
				Search.Query.NONE, null, -1);

		State with(@Nullable Search.Matches matches, int current) {
			return new State(open, replacing, query, matches, current);
		}
	}

	private static final String QUERY = "find_query";
	private static final String MATCH_CASE = "find_match_case";
	private static final String WHOLE_WORD = "find_whole_word";
	private static final String REPLACING = "find_replacing";
	private static final String REPLACEMENT = "find_replacement";
	// The lines the main thread reads per keystroke for the match to
	// show at once: about a few screens. Further on, the count finds it.
	private static final int MAIN_LINES = 200;
	// The longest selection Find starts with.
	private static final int MAX_SELECTED = 200;
	private static final long FADE_OUT = 50;
	private static final long FADE_IN = 100;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Executor thread = new ThreadPoolExecutor(0, 1, 1,
			TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task ->
			new Thread(() -> {
				Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
				task.run();
			}, "Find"));
	private final Document.Listener documentListener =
			new Document.Listener() {
				@Override
				public void onUpdate() {
					updated();
				}
			};
	private View row;
	private EditText field;
	private EditText replacement;
	// Shows the menu once the keyboard made room for it, or null.
	@Nullable
	private ViewTreeObserver.OnGlobalLayoutListener menuWaits;
	private View replaceRow;
	// Where the row that replaces starts under the find row: under the
	// field.
	private int replaceIndent;
	private TextView count;
	private View previous;
	private View next;
	@NonNull
	private State state = State.CLOSED;
	@Nullable
	private CodeView code;
	@Nullable
	private Document document;
	@Nullable
	private View toolbar;
	// Where a query looks for its first match from: the caret when Find
	// opened, then the match shown, so that typing narrows from there.
	private int origin;
	private int revision;
	// Counts the counts started; a result of an older one is dropped.
	private int counts;
	private boolean matchCase;
	private boolean wholeWord;
	@Nullable
	private Runnable openChanged;

	public FindBar(Context context) {
		super(context);
	}

	public FindBar(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		row = findViewById(R.id.find_row);
		field = findViewById(R.id.find_field);
		replaceRow = findViewById(R.id.replace_row);
		replaceIndent = replaceRow.getPaddingStart();
		replacement = findViewById(R.id.replace_field);
		findViewById(R.id.replace_one).setOnClickListener(v -> replace());
		findViewById(R.id.replace_all).setOnClickListener(v ->
				replaceAll());
		count = findViewById(R.id.find_count);
		previous = findViewById(R.id.find_previous);
		next = findViewById(R.id.find_next);
		findViewById(R.id.find_close).setOnClickListener(v -> close());
		previous.setOnClickListener(v -> step(false));
		next.setOnClickListener(v -> step(true));
		findViewById(R.id.find_menu).setOnClickListener(this::showMenu);
		field.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start,
					int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				search();
			}
		});
		// The keyboard's action goes to the next match, Shift+Enter on a
		// keyboard to the one before.
		field.setOnEditorActionListener((v, action, event) -> {
			if (action == EditorInfo.IME_ACTION_SEARCH ||
					(event != null &&
							event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
							event.getAction() == KeyEvent.ACTION_DOWN)) {
				step(event == null || !event.isShiftPressed());
				return true;
			}
			return false;
		});
	}

	// Escape closes, from either field.
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE && state.open()) {
			if (event.getAction() == KeyEvent.ACTION_UP) {
				close();
			}
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	/** Return whether Find is open. */
	public boolean isOpen() {
		return state.open();
	}

	/** Return whether the row that replaces shows. */
	public boolean isReplacing() {
		return state.replacing();
	}

	/**
	 * Return the measured height with the rows one under the other,
	 * whether they are or not.
	 */
	public int stackedHeight() {
		return getPaddingTop() + getPaddingBottom() +
				row.getMeasuredHeight() + (replaceRow.getVisibility() == GONE
				? 0
				: replaceRow.getMeasuredHeight());
	}

	/**
	 * Put the row that replaces beside the find row, where it takes
	 * half the width and no indent, or under it, aligned with the field;
	 * return whether that changed.
	 */
	public boolean setOneRow(boolean oneRow) {
		if (oneRow == (getOrientation() == HORIZONTAL)) {
			return false;
		}
		setOrientation(oneRow ? HORIZONTAL : VERTICAL);
		for (View child : new View[]{row, replaceRow}) {
			LayoutParams params = (LayoutParams) child.getLayoutParams();
			params.width = oneRow ? 0 : LayoutParams.MATCH_PARENT;
			params.weight = oneRow ? 1 : 0;
			child.setLayoutParams(params);
		}
		replaceRow.setPaddingRelative(oneRow ? 0 : replaceIndent,
				replaceRow.getPaddingTop(), replaceRow.getPaddingEnd(),
				replaceRow.getPaddingBottom());
		return true;
	}

	/** Run changed whenever Find opens or closes; none for null. */
	public void setOnOpenChanged(@Nullable Runnable changed) {
		openChanged = changed;
	}

	/**
	 * Put what is typed into state while Find is open, so that a screen
	 * made again opens it as it was; see {@link #restore}.
	 */
	public void save(@NonNull Bundle state) {
		if (this.state.open()) {
			state.putString(QUERY, field.getText().toString());
			state.putBoolean(MATCH_CASE, matchCase);
			state.putBoolean(WHOLE_WORD, wholeWord);
			state.putBoolean(REPLACING, this.state.replacing());
			state.putString(REPLACEMENT, replacement.getText().toString());
		}
	}

	/** Return whether state was saved while Find was open. */
	public static boolean wasOpen(@Nullable Bundle state) {
		return state != null && state.containsKey(QUERY);
	}

	/** Open as save left Find, in place of toolbar over code. */
	public void restore(@NonNull Bundle state, @NonNull CodeView code,
			@NonNull View toolbar) {
		matchCase = state.getBoolean(MATCH_CASE);
		wholeWord = state.getBoolean(WHOLE_WORD);
		replacement.setText(state.getString(REPLACEMENT));
		open(code, toolbar, state.getString(QUERY, ""));
		if (state.getBoolean(REPLACING)) {
			showReplace();
		}
	}

	/** Show the row that replaces, and give its field the focus. */
	public void showReplace() {
		if (state.open() && !state.replacing()) {
			set(new State(true, true, state.query(), state.matches(),
					state.current()));
		}
		post(() -> SoftKeyboard.show(getContext(), replacement));
	}

	@Override
	protected void onDetachedFromWindow() {
		// A file's document outlives this screen.
		++counts;
		listen(null);
		super.onDetachedFromWindow();
	}

	/**
	 * Open in the place of toolbar, finding in what code shows: with the
	 * selection when it is short and in one line, a name as a whole
	 * word, else with the query of the last time. Open, take the focus.
	 */
	public void open(@NonNull CodeView code, @NonNull View toolbar) {
		open(code, toolbar, null);
	}

	// Opens with query, or as open says when it is null.
	private void open(@NonNull CodeView code, @NonNull View toolbar,
			@Nullable String query) {
		if (state.open()) {
			field.requestFocus();
			field.selectAll();
			return;
		}
		this.code = code;
		this.toolbar = toolbar;
		code.setOnDocumentShown(this::documentShown);
		listen(code.getDocument());
		Document.Selection selection = document.selection();
		origin = selection.start();
		String selected = selection.isEmpty() ||
				selection.end() - selection.start() > MAX_SELECTED
				? null
				: document.text().substring(selection.start(),
						selection.end());
		state = new State(true, false, Search.Query.NONE, null, -1);
		if (query != null) {
			field.setText(query);
		} else if (selected != null && selected.indexOf('\n') < 0) {
			wholeWord = isName(selected);
			field.setText(selected);
		} else {
			search();
		}
		field.selectAll();
		setPadding(getPaddingLeft(), toolbar.getPaddingTop(),
				getPaddingRight(), getPaddingBottom());
		// Not laid out yet when the screen was made again.
		if (toolbar.getHeight() > 0) {
			row.setMinimumHeight(toolbar.getHeight() -
					toolbar.getPaddingTop());
		}
		fadeThrough(toolbar, this, () -> SoftKeyboard.show(getContext(),
				field));
		if (openChanged != null) {
			openChanged.run();
		}
	}

	/**
	 * Close and give the toolbar its place back; the current match stays
	 * selected in the code, which takes the focus.
	 */
	public void close() {
		if (!state.open()) {
			return;
		}
		++counts;
		boolean typing = field.hasFocus() && SoftKeyboard.isOpen(this);
		set(State.CLOSED);
		if (openChanged != null) {
			openChanged.run();
		}
		CodeView shown = code;
		listen(null);
		code = null;
		if (shown != null) {
			shown.setOnDocumentShown(null);
			shown.setFound(Search.Query.NONE);
			if (typing) {
				SoftKeyboard.show(getContext(), shown);
			} else {
				shown.requestFocus();
			}
		}
		if (toolbar != null) {
			fadeThrough(this, toolbar, null);
		}
	}

	private void set(@NonNull State state) {
		this.state = state;
		render();
	}

	// Shows state: the count, the arrows and the marks in the code.
	private void render() {
		if (code != null) {
			code.setFound(state.open() ? state.query() : Search.Query.NONE);
		}
		Search.Matches matches = state.matches();
		boolean none = state.query().isEmpty() ||
				(matches != null && matches.count() == 0);
		previous.setEnabled(!none);
		next.setEnabled(!none);
		count.setText(countText());
		// TalkBack hears the count with the field.
		ViewCompat.setStateDescription(field, spoken());
		replaceRow.setVisibility(state.replacing() ? VISIBLE : GONE);
	}

	@NonNull
	private String countText() {
		Search.Matches matches = state.matches();
		if (state.query().isEmpty()) {
			return "";
		} else if (matches == null) {
			return getContext().getString(R.string.find_counting);
		}
		String total = matches.more()
				? getContext().getString(R.string.find_many)
				: String.valueOf(matches.count());
		int index = state.current() >= 0
				? matches.indexOf(state.current())
				: -1;
		return matches.count() == 0 || index >= 0
				? getContext().getString(R.string.find_count, index + 1,
				total)
				: total;
	}

	// What TalkBack says of the current match, or of none; null while
	// that is not known.
	@Nullable
	private String spoken() {
		Search.Matches matches = state.matches();
		if (state.query().isEmpty() || matches == null) {
			return null;
		} else if (matches.count() == 0) {
			return getContext().getString(R.string.a11y_find_none);
		}
		int index = state.current() >= 0
				? matches.indexOf(state.current())
				: -1;
		return index < 0 || document == null
				? null
				: getContext().getString(R.string.a11y_find_match, index + 1,
				matches.count(), document.text().lineOf(
						state.current()) + 1);
	}

	// The query changed: the match from origin on is shown at once when
	// it is near, and the count goes on in the background.
	private void search() {
		if (!state.open() || document == null) {
			return;
		}
		Search.Query query = new Search.Query(field.getText().toString(),
				matchCase, wholeWord);
		int at = Search.next(document.text(), query, origin, MAIN_LINES);
		if (at >= 0) {
			select(at, query);
		}
		set(new State(true, state.replacing(), query, null, at));
		count();
	}

	// Counts the matches of the query in the text as it is now, off the
	// main thread; a newer count drops this one.
	private void count() {
		if (document == null || state.query().isEmpty()) {
			return;
		}
		int counted = ++counts;
		Text text = document.text();
		Search.Query query = state.query();
		thread.execute(() -> {
			Search.Matches matches = Search.all(text, query,
					() -> counted != counts);
			if (matches != null) {
				handler.post(() -> counted(counted, matches));
			}
		});
	}

	private void counted(int counted, @NonNull Search.Matches matches) {
		if (counted != counts || document == null ||
				matches.text() != document.text()) {
			return;
		}
		int current = state.current();
		if (current == Search.UNKNOWN) {
			current = following(matches, origin);
			if (current >= 0) {
				select(current, matches.query());
			}
		}
		boolean none = matches.count() == 0 && (state.matches() == null ||
				state.matches().count() > 0);
		set(state.with(matches, current));
		if (none) {
			announceForAccessibility(spoken());
		}
	}

	// Goes to the next match after the selection, or the one before it.
	private void step(boolean forward) {
		if (document == null || state.query().isEmpty()) {
			return;
		}
		Document.Selection selection = document.selection();
		Search.Query query = state.query();
		Search.Matches matches = state.matches();
		Text text = document.text();
		int at;
		if (matches == null || matches.text() != text) {
			at = forward
					? Search.next(text, query, selection.end(),
					Integer.MAX_VALUE)
					: Search.previous(text, query, selection.start());
		} else if (forward) {
			at = following(matches, selection.end());
		} else {
			int i = matches.after(selection.start()) - 1;
			at = i >= 0 && !(i == matches.count() - 1 && matches.more())
					? matches.start(i)
					: matches.more() || matches.count() == 0
					? Search.previous(text, query, selection.start())
					: matches.start(matches.count() - 1);
		}
		if (at < 0) {
			return;
		}
		select(at, query);
		origin = at;
		set(state.with(state.matches(), at));
		String spoken = spoken();
		if (spoken != null) {
			announceForAccessibility(spoken);
		}
	}

	// Replaces the current match, as one step of undo, and goes to the
	// next; without a current match, it goes to the next first.
	private void replace() {
		if (document == null || state.query().isEmpty()) {
			return;
		}
		Document.Selection selection = document.selection();
		int current = state.current();
		if (current >= 0 && selection.start() == current &&
				selection.end() == current + state.query().text().length()) {
			document.replace(selection.start(), selection.end(),
					Text.of(replacement.getText()));
		}
		step(true);
	}

	// Replaces every match as one change, one step of undo: from the
	// first match to the end of the last, the text between them kept.
	private void replaceAll() {
		if (document == null || state.query().isEmpty()) {
			return;
		}
		Search.Replace all = Search.replaceAll(Search.all(document.text(),
				state.query(), Integer.MAX_VALUE, () -> false),
				replacement.getText());
		if (all != null && code != null) {
			document.replace(all.from(), all.to(), all.text());
			code.showFound();
		}
	}

	// Returns the first match at or after offset, round the end.
	private int following(@NonNull Search.Matches matches, int offset) {
		int i = matches.after(offset);
		if (i < matches.count()) {
			return matches.start(i);
		} else if (matches.more()) {
			return Search.next(matches.text(), matches.query(), offset,
					Integer.MAX_VALUE);
		}
		return matches.count() > 0 ? matches.start(0) : -1;
	}

	private void select(int at, @NonNull Search.Query query) {
		if (document == null || code == null) {
			return;
		}
		origin = at;
		document.select(at, at + query.text().length());
		code.showFound();
	}

	// The text or the selection changed. An edit counts again; a caret
	// moved off the current match leaves none current.
	private void updated() {
		if (document == null) {
			return;
		}
		Document.Selection selection = document.selection();
		int current = state.current();
		int length = state.query().text().length();
		if (current >= 0 && (selection.start() != current ||
				selection.end() != current + length)) {
			current = -1;
			origin = selection.start();
		}
		if (document.revision() != revision) {
			revision = document.revision();
			// The count shown stays until the new one replaces it.
			set(state.with(state.matches(), current));
			count();
		} else if (current != state.current()) {
			set(state.with(state.matches(), current));
		}
	}

	// Another document is shown: its matches are counted, and none is
	// current until the arrows go to one.
	private void documentShown() {
		if (code == null) {
			return;
		}
		listen(code.getDocument());
		origin = document.selection().start();
		set(state.with(null, -1));
		count();
	}

	private void listen(@Nullable Document document) {
		if (this.document != null) {
			this.document.removeListener(documentListener);
		}
		this.document = document;
		if (document != null) {
			revision = document.revision();
			document.addListener(documentListener);
		}
	}

	// The entries are options, not typing: where a keyboard open for a
	// field of the bar leaves the menu too little room, as a docked one
	// in landscape does, the keyboard makes room for it first and comes
	// back for that field when the menu closes. Where the menu fits, as
	// in portrait, the keyboard stays.
	private void showMenu(@NonNull View anchor) {
		View typing = field.hasFocus()
				? field
				: replacement.hasFocus() ? replacement : null;
		if (typing == null || !SoftKeyboard.isOpen(this) ||
				menuFits(anchor)) {
			showMenu(anchor, null);
			return;
		}
		SoftKeyboard.hide(getContext(), typing);
		// Shown once the window has the room, as layout tells; a second
		// tap meanwhile waits for the same.
		ViewTreeObserver observer = getViewTreeObserver();
		if (menuWaits != null) {
			observer.removeOnGlobalLayoutListener(menuWaits);
		}
		menuWaits = new ViewTreeObserver.OnGlobalLayoutListener() {
			@Override
			public void onGlobalLayout() {
				if (SoftKeyboard.isOpen(FindBar.this)) {
					return;
				}
				getViewTreeObserver().removeOnGlobalLayoutListener(this);
				menuWaits = null;
				if (state.open() && isAttachedToWindow()) {
					showMenu(anchor, typing);
				}
			}
		};
		observer.addOnGlobalLayoutListener(menuWaits);
	}

	// Return whether the menu fits in the room the popup gets: what the
	// window shows below anchor, which an open keyboard shortens.
	private boolean menuFits(@NonNull View anchor) {
		Rect shown = new Rect();
		anchor.getWindowVisibleDisplayFrame(shown);
		int[] at = new int[2];
		anchor.getLocationOnScreen(at);
		int room = shown.bottom - at[1] - anchor.getHeight();
		float dp = getResources().getDisplayMetrics().density;
		TypedValue value = new TypedValue();
		float entry = getContext().getTheme().resolveAttribute(
				androidx.appcompat.R.attr.dropdownListPreferredItemHeight,
				value, true)
				? value.getDimension(getResources().getDisplayMetrics())
				: 48 * dp;
		// The entries, and the list's padding above and below them.
		int entries = errorsButton() != null ? 4 : 3;
		return entries * entry + 16 * dp <= room;
	}

	// The toolbar's error button while it shows, or null.
	@Nullable
	private View errorsButton() {
		View errors = toolbar != null
				? toolbar.findViewById(R.id.show_errors)
				: null;
		return errors != null && errors.getVisibility() == VISIBLE
				? errors
				: null;
	}

	// Show the menu; typing is the field whose keyboard comes back when
	// it closes, or null.
	private void showMenu(@NonNull View anchor, @Nullable View typing) {
		boolean[] typeOn = {typing != null};
		PopupMenu popup = new PopupMenu(getContext(), anchor);
		popup.getMenu().add(0, R.string.match_case, 0, R.string.match_case)
				.setCheckable(true).setChecked(matchCase);
		popup.getMenu().add(0, R.string.whole_word, 0, R.string.whole_word)
				.setCheckable(true).setChecked(wholeWord);
		popup.getMenu().add(0, R.string.replace, 0, R.string.replace)
				.setCheckable(true).setChecked(state.replacing());
		// The toolbar's error button stands in its place, so the list of
		// errors stays one tap away while Find is open.
		View errors = errorsButton();
		if (errors != null) {
			popup.getMenu().add(1, R.string.show_errors, 1,
					R.string.show_errors);
		}
		popup.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.string.show_errors) {
				// Their list opens in place of typing.
				typeOn[0] = false;
				errors.performClick();
				return true;
			} else if (id == R.string.replace) {
				boolean replacing = !state.replacing();
				set(new State(true, replacing, state.query(),
						state.matches(), state.current()));
				if (replacing) {
					typeOn[0] = false;
					SoftKeyboard.show(getContext(), replacement);
				} else if (replacement.hasFocus()) {
					field.requestFocus();
				}
				return true;
			} else if (id == R.string.match_case) {
				matchCase = !matchCase;
			} else {
				wholeWord = !wholeWord;
			}
			search();
			return true;
		});
		popup.setOnDismissListener(menu -> {
			if (typeOn[0] && state.open()) {
				// Replace turned off leaves the find field.
				SoftKeyboard.show(getContext(), typing.isShown()
						? typing
						: field);
			}
		});
		popup.show();
	}

	// Fades the content of out away, puts in in its place and fades its
	// content in: 150 ms, at once when animations are off.
	private static void fadeThrough(@NonNull View out, @NonNull View in,
			@Nullable Runnable shown) {
		ViewGroup outGroup = (ViewGroup) out;
		for (int i = outGroup.getChildCount(); i-- > 0; ) {
			outGroup.getChildAt(i).animate().alpha(0).setDuration(FADE_OUT);
		}
		out.postOnAnimationDelayed(() -> {
			out.setVisibility(View.GONE);
			for (int i = outGroup.getChildCount(); i-- > 0; ) {
				outGroup.getChildAt(i).animate().cancel();
				outGroup.getChildAt(i).setAlpha(1);
			}
			in.setVisibility(View.VISIBLE);
			ViewGroup inGroup = (ViewGroup) in;
			for (int i = inGroup.getChildCount(); i-- > 0; ) {
				View child = inGroup.getChildAt(i);
				child.setAlpha(0);
				child.animate().alpha(1).setDuration(FADE_IN);
			}
			if (shown != null) {
				shown.run();
			}
		}, Math.round(FADE_OUT * durationScale(out.getContext())));
	}

	private static float durationScale(@NonNull Context context) {
		return android.provider.Settings.Global.getFloat(
				context.getContentResolver(),
				android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1);
	}

	// Whether s is a name of GLSL: a letter or _, then letters, digits
	// and _.
	private static boolean isName(@NonNull String s) {
		for (int i = 0, length = s.length(); i < length; ++i) {
			char c = s.charAt(i);
			if (!(c == '_' || (c < 128 && Character.isLetter(c)) ||
					(i > 0 && c >= '0' && c <= '9'))) {
				return false;
			}
		}
		return !s.isEmpty();
	}
}
