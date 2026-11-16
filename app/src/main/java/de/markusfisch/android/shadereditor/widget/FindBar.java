package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;

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
	private final OnBackPressedCallback back =
			new OnBackPressedCallback(false) {
				@Override
				public void handleOnBackPressed() {
					close();
				}
			};
	private View row;
	private EditText field;
	private EditText replacement;
	private View replaceRow;
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
		// The keyboard's action goes to the next match.
		field.setOnEditorActionListener((v, action, event) -> {
			if (action == EditorInfo.IME_ACTION_SEARCH ||
					(event != null &&
							event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
							event.getAction() == KeyEvent.ACTION_DOWN)) {
				step(true);
				return true;
			}
			return false;
		});
		ComponentActivity activity = activityOf(getContext());
		if (activity != null) {
			activity.getOnBackPressedDispatcher().addCallback(activity, back);
		}
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
		if (selected != null && selected.indexOf('\n') < 0) {
			wholeWord = isName(selected);
			field.setText(selected);
		} else {
			search();
		}
		field.selectAll();
		setPadding(getPaddingLeft(), toolbar.getPaddingTop(),
				getPaddingRight(), getPaddingBottom());
		row.setMinimumHeight(toolbar.getHeight() - toolbar.getPaddingTop());
		fadeThrough(toolbar, this, () -> SoftKeyboard.show(getContext(),
				field));
		back.setEnabled(true);
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
		back.setEnabled(false);
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
		set(state.with(matches, current));
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

	private void showMenu(@NonNull View anchor) {
		PopupMenu popup = new PopupMenu(getContext(), anchor);
		popup.getMenu().add(0, R.string.match_case, 0, R.string.match_case)
				.setCheckable(true).setChecked(matchCase);
		popup.getMenu().add(0, R.string.whole_word, 0, R.string.whole_word)
				.setCheckable(true).setChecked(wholeWord);
		popup.getMenu().add(0, R.string.replace, 0, R.string.replace)
				.setCheckable(true).setChecked(state.replacing());
		popup.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.string.replace) {
				boolean replacing = !state.replacing();
				set(new State(true, replacing, state.query(),
						state.matches(), state.current()));
				if (replacing) {
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

	@Nullable
	private static ComponentActivity activityOf(@NonNull Context context) {
		while (context instanceof ContextWrapper) {
			if (context instanceof ComponentActivity activity) {
				return activity;
			}
			context = ((ContextWrapper) context).getBaseContext();
		}
		return null;
	}
}
