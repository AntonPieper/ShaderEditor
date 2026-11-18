package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.WordPlaces;
import de.markusfisch.android.shadereditor.editor.WordPlaces.Group;

/**
 * Every place a name is written in the folder, in the groups
 * {@link WordPlaces#group} makes: each place its line and the line's
 * code, cut around the name, which is in the accent colour. The other
 * files of the folder and the comments are folded. A tap on a place
 * goes there. Nothing in it says that a place means what the name means
 * elsewhere. Shown again, it keeps where it was scrolled to.
 * <p>
 * Rename turns the sheet into the rename: the name becomes a field,
 * each place a box, ticked where its group says so, and the button says
 * how many places change.
 */
public final class UsesSheet {
	/** What the sheet asks of whoever shows it. */
	public interface Actions {
		/** Go to place; the sheet has closed. */
		void go(@NonNull WordPlaces.Place place);

		/**
		 * Return why name cannot replace the word, "" when it is no
		 * other name, or null when it can.
		 */
		@Nullable
		String problem(@NonNull String name);

		/** Tell warn where name is written already, null for nowhere. */
		void used(@NonNull String name, @NonNull Warn warn);

		/** Write name in places of the word. */
		void rename(@NonNull String name,
				@NonNull Set<WordPlaces.Place> places);

		/** Write the word of the content in place, which was left. */
		void renameLeft(@NonNull WordPlaces.Place place);
	}

	public interface Warn {
		void warn(@Nullable String where);
	}

	/**
	 * What the sheet lists.
	 *
	 * @param shown     the file in the editor, whose places name no file
	 * @param unread    how many files could not be read
	 * @param glued     whether a file glues names together with ##
	 * @param renamable whether Rename is offered
	 */
	public record Content(@NonNull String word, @NonNull String shown,
			@NonNull List<Group> groups, int unread, boolean glued,
			boolean renamable) {
	}

	/** Where the line is cut so that the name shows, see {@link #cut}. */
	record Cut(@NonNull String text, int at) {
	}

	private static final float MAX_HEIGHT = .9f;
	private static final char ELLIPSIS = '…';

	private final BottomSheetDialog dialog;
	private final View view;
	private final Adapter adapter;
	private final Actions actions;
	private final TextView word;
	private final TextInputLayout nameBox;
	private final EditText name;
	private final Button rename;
	private final OnBackPressedCallback leave = new OnBackPressedCallback(
			false) {
		@Override
		public void handleOnBackPressed() {
			setRenaming(false);
		}
	};
	@Nullable
	private Content content;

	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public UsesSheet(@NonNull Context context, @NonNull Typeface font,
			@NonNull Actions actions) {
		this.actions = actions;
		view = LayoutInflater.from(context).inflate(R.layout.sheet_uses,
				null);
		dialog = new BottomSheetDialog(context);
		word = view.findViewById(R.id.uses_word);
		word.setTypeface(font);
		ViewCompat.setAccessibilityHeading(word, true);
		nameBox = view.findViewById(R.id.uses_name_box);
		name = view.findViewById(R.id.uses_name);
		name.setTypeface(font);
		rename = view.findViewById(R.id.uses_rename);
		RecyclerView list = view.findViewById(R.id.uses_list);
		list.setLayoutManager(new LinearLayoutManager(context));
		adapter = new Adapter(font, MaterialColors.getColor(view,
				androidx.appcompat.R.attr.colorPrimary), this::countTicked);
		list.setAdapter(adapter);
		// The code is cut to the width there is.
		list.addOnLayoutChangeListener((v, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != oldRight - oldLeft) {
				list.post(() -> adapter.setWidth(right - left));
			}
		});
		view.findViewById(R.id.uses_start).setOnClickListener(v ->
				setRenaming(true));
		view.findViewById(R.id.uses_cancel).setOnClickListener(v ->
				setRenaming(false));
		rename.setOnClickListener(v -> {
			String typed = name.getText().toString();
			if (actions.problem(typed) == null) {
				actions.rename(typed, new HashSet<>(adapter.ticked));
			}
		});
		name.addTextChangedListener(new TextWatcher() {
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
				checkName();
			}
		});
		name.setOnEditorActionListener((v, action, event) -> {
			if (action == EditorInfo.IME_ACTION_DONE && rename.isEnabled()) {
				rename.performClick();
			}
			return action == EditorInfo.IME_ACTION_DONE;
		});
		dialog.getOnBackPressedDispatcher().addCallback(leave);
		dialog.setContentView(view);
		fitAboveKeyboard(context);
	}

	public void dismiss() {
		dialog.dismiss();
	}

	/** Run when the sheet goes, however it goes. */
	public void setOnDismiss(@NonNull Runnable gone) {
		dialog.setOnDismissListener(d -> gone.run());
	}

	/** Return a view inside the sheet, to show a message over it. */
	@NonNull
	public View getView() {
		return view;
	}

	public boolean isShowing() {
		return dialog.isShowing();
	}

	/**
	 * List content, and show the sheet if it is not shown. The same word
	 * again keeps renaming with the name typed; another word, as after a
	 * rename, is listed, and TalkBack goes on from it.
	 */
	public void show(@NonNull Content content) {
		boolean again = this.content != null &&
				this.content.word().equals(content.word());
		this.content = content;
		Context context = view.getContext();
		Resources res = context.getResources();
		word.setText(content.word());
		dialog.setTitle(content.word());
		int places = 0;
		Set<String> files = new HashSet<>();
		for (Group group : content.groups()) {
			if (group.kind() != Group.Kind.COMMENTS &&
					group.kind() != Group.Kind.LEFT) {
				places += group.places().size();
				for (WordPlaces.Place place : group.places()) {
					files.add(place.file());
				}
			}
		}
		((TextView) view.findViewById(R.id.uses_count)).setText(
				context.getString(R.string.uses_count,
						res.getQuantityString(R.plurals.uses_places, places,
								places),
						res.getQuantityString(R.plurals.uses_files,
								files.size(), files.size())));
		List<String> notes = new ArrayList<>();
		if (content.glued()) {
			notes.add(context.getString(R.string.uses_glued));
		}
		if (content.unread() > 0) {
			notes.add(res.getQuantityString(R.plurals.uses_unread,
					content.unread(), content.unread()));
		}
		TextView note = view.findViewById(R.id.uses_note);
		note.setText(String.join(" ", notes));
		note.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
		view.findViewById(R.id.uses_bar).setVisibility(content.renamable()
				? View.VISIBLE
				: View.GONE);
		adapter.show(content);
		if (!dialog.isShowing()) {
			dialog.show();
		} else if (adapter.renaming && again) {
			checkName();
		} else {
			setRenaming(false);
			word.post(() -> word.performAccessibilityAction(
					AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
		}
	}

	private void setRenaming(boolean renaming) {
		leave.setEnabled(renaming);
		word.setVisibility(renaming ? View.GONE : View.VISIBLE);
		view.findViewById(R.id.uses_count).setVisibility(renaming
				? View.GONE
				: View.VISIBLE);
		nameBox.setVisibility(renaming ? View.VISIBLE : View.GONE);
		view.findViewById(R.id.uses_start).setVisibility(renaming
				? View.GONE
				: View.VISIBLE);
		view.findViewById(R.id.uses_cancel).setVisibility(renaming
				? View.VISIBLE
				: View.GONE);
		rename.setVisibility(renaming ? View.VISIBLE : View.GONE);
		adapter.setRenaming(renaming);
		if (renaming && content != null) {
			name.setText(content.word());
			name.selectAll();
			name.requestFocus();
		}
		Window window = dialog.getWindow();
		if (window != null) {
			WindowInsetsControllerCompat keyboard =
					WindowCompat.getInsetsController(window, name);
			if (renaming) {
				name.post(() -> keyboard.show(WindowInsetsCompat.Type.ime()));
			} else {
				keyboard.hide(WindowInsetsCompat.Type.ime());
			}
		}
	}

	// Says what is wrong with the name typed, or where it is written.
	private void checkName() {
		String typed = name.getText().toString();
		String problem = actions.problem(typed);
		nameBox.setError(problem != null && !problem.isEmpty()
				? problem
				: null);
		nameBox.setHelperText(null);
		if (problem == null) {
			actions.used(typed, where -> {
				if (typed.equals(name.getText().toString())) {
					nameBox.setHelperText(where);
				}
			});
		}
		countTicked();
	}

	private void countTicked() {
		int count = adapter.ticked.size();
		rename.setText(view.getResources().getQuantityString(
				R.plurals.uses_rename_places, count, count));
		rename.setEnabled(count > 0 &&
				actions.problem(name.getText().toString()) == null);
	}

	// From 8 dp under the status bar down to the keyboard, which the
	// window makes room for, so that the list is never under either.
	private void fitAboveKeyboard(@NonNull Context context) {
		BottomSheetBehavior<?> behavior = dialog.getBehavior();
		int most = Math.round(MAX_HEIGHT *
				context.getResources().getDisplayMetrics().heightPixels);
		behavior.setMaxHeight(most);
		behavior.setSkipCollapsed(true);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		int gap = Math.round(8 * context.getResources().getDisplayMetrics()
				.density);
		int[] top = new int[1];
		ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
			top[0] = insets.getInsets(WindowInsetsCompat.Type.statusBars())
					.top;
			v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
					v.getPaddingRight(), insets.isVisible(
							WindowInsetsCompat.Type.ime())
							? 0
							: insets.getInsets(WindowInsetsCompat.Type
							.navigationBars()).bottom);
			return insets;
		});
		((View) view.getParent().getParent()).addOnLayoutChangeListener(
				(p, left, t, right, bottom, oldLeft, oldTop, oldRight,
						oldBottom) -> {
					int height = Math.min(most, bottom - t - gap - top[0]);
					if (behavior.getMaxHeight() != height) {
						behavior.setMaxHeight(height);
					}
				});
		Window window = dialog.getWindow();
		if (window != null) {
			window.setSoftInputMode(WindowManager.LayoutParams
					.SOFT_INPUT_ADJUST_RESIZE);
		}
	}

	/**
	 * Return the part of line to show in columns characters with the
	 * name from at to at + length in it: from its first character that
	 * is no blank, the name in the middle unless it fits with the line's
	 * start, an ellipsis in place of the first or last character where
	 * the line goes on. A name too long for columns is shown from its
	 * start.
	 */
	@NonNull
	static Cut cut(@NonNull String line, int at, int length, int columns) {
		String s = line.replace('\t', ' ');
		int lead = 0;
		while (lead < at && s.charAt(lead) == ' ') {
			++lead;
		}
		s = s.substring(lead);
		at -= lead;
		int size = s.length();
		if (size <= columns) {
			return new Cut(s, at);
		}
		int from = at + length < columns
				? 0
				: length > columns - 2
				? at - 1
				: Math.min(at - (columns - length) / 2, size - columns);
		int to = Math.min(size, Math.max(from + columns, at + length + 1));
		StringBuilder sb = new StringBuilder(s.substring(from, to));
		if (from > 0) {
			sb.setCharAt(0, ELLIPSIS);
		}
		if (to < size) {
			sb.setCharAt(sb.length() - 1, ELLIPSIS);
		}
		return new Cut(sb.toString(), at - from);
	}

	private final class Adapter
			extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private static final int HEADING = 0;
		private static final int FILE = 1;
		private static final int PLACE = 2;
		private static final int FOLD = 3;

		// A row: the heading of a group, a file over places, or a place,
		// each in its group.
		private record Row(int type, @NonNull Group group,
				@Nullable String file, @Nullable WordPlaces.Place place) {
		}

		private final Set<WordPlaces.Place> ticked = new HashSet<>();
		private final Set<Group.Kind> unfolded = EnumSet.noneOf(
				Group.Kind.class);
		private final Typeface font;
		private final int accent;
		private final Runnable tickedChanged;
		@NonNull
		private List<Row> rows = Collections.emptyList();
		private boolean renaming;
		private int width;
		// Of the widest line number listed.
		private int digits = 1;

		private Adapter(@NonNull Typeface font, int accent,
				@NonNull Runnable tickedChanged) {
			this.font = font;
			this.accent = accent;
			this.tickedChanged = tickedChanged;
		}

		private void show(@NonNull Content content) {
			int last = 0;
			ticked.clear();
			for (Group group : content.groups()) {
				for (WordPlaces.Place place : group.places()) {
					last = Math.max(last, place.line() + 1);
				}
				if (group.kind().ticked()) {
					ticked.addAll(group.places());
				}
			}
			digits = String.valueOf(last).length();
			list();
		}

		private void setRenaming(boolean renaming) {
			this.renaming = renaming;
			list();
		}

		@SuppressLint("NotifyDataSetChanged")
		private void setWidth(int width) {
			this.width = width;
			notifyDataSetChanged();
		}

		@SuppressLint("NotifyDataSetChanged")
		private void list() {
			List<Row> list = new ArrayList<>();
			for (Group group : content.groups()) {
				Group.Kind kind = group.kind();
				if (renaming && kind == Group.Kind.LEFT) {
					continue;
				}
				boolean folds = kind == Group.Kind.OTHER ||
						kind == Group.Kind.COMMENTS;
				list.add(new Row(folds ? FOLD : HEADING, group, null, null));
				if (folds && !unfolded.contains(kind)) {
					continue;
				}
				boolean byFile = folds || kind == Group.Kind.ELSEWHERE ||
						kind == Group.Kind.LEFT;
				String file = null;
				for (WordPlaces.Place place : group.places()) {
					if (byFile && !place.file().equals(file)) {
						file = place.file();
						list.add(new Row(FILE, group, file, null));
					}
					list.add(new Row(PLACE, group, null, place));
				}
			}
			rows = list;
			notifyDataSetChanged();
		}

		@Override
		public int getItemViewType(int position) {
			return rows.get(position).type();
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int type) {
			int layout = switch (type) {
				case HEADING -> R.layout.row_go_to_heading;
				case FILE -> R.layout.row_uses_file;
				case FOLD -> R.layout.row_uses_fold;
				default -> R.layout.row_use;
			};
			View view = LayoutInflater.from(parent.getContext()).inflate(
					layout, parent, false);
			if (type == PLACE) {
				((TextView) view.findViewById(R.id.use_code))
						.setTypeface(font);
			}
			return new RecyclerView.ViewHolder(view) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
				int position) {
			Row row = rows.get(position);
			View view = holder.itemView;
			Context context = view.getContext();
			Group group = row.group();
			switch (row.type()) {
				case HEADING -> {
					((TextView) view).setText(switch (group.kind()) {
						case FUNCTION -> context.getString(
								R.string.uses_in_function, group.name());
						case ELSEWHERE -> context.getString(
								R.string.uses_elsewhere);
						case LEFT -> context.getString(R.string.uses_left,
								group.name());
						default -> group.name();
					});
					ViewCompat.setAccessibilityHeading(view, true);
				}
				case FILE -> ((TextView) view).setText(row.file());
				case FOLD -> bindFold((TextView) view, group.kind(),
						group.places().size());
				default -> bindPlace(view, group, row.place());
			}
		}

		private void bindFold(@NonNull TextView view,
				@NonNull Group.Kind kind, int count) {
			Context context = view.getContext();
			boolean open = unfolded.contains(kind);
			String title = context.getString(kind == Group.Kind.OTHER
					? R.string.uses_other
					: R.string.uses_in_comments);
			view.setText(title + " · " + count);
			view.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, open
					? R.drawable.ic_keyboard_arrow_up
					: R.drawable.ic_keyboard_arrow_down, 0);
			view.setContentDescription(title + ", " + count);
			ViewCompat.setStateDescription(view, context.getString(open
					? R.string.a11y_expanded
					: R.string.a11y_collapsed));
			ViewCompat.setAccessibilityHeading(view, true);
			view.setOnClickListener(v -> {
				if (!unfolded.remove(kind)) {
					unfolded.add(kind);
				}
				list();
			});
		}

		private void bindPlace(@NonNull View view, @NonNull Group group,
				@NonNull WordPlaces.Place place) {
			Context context = view.getContext();
			TextView number = view.findViewById(R.id.use_line);
			TextView code = view.findViewById(R.id.use_code);
			CheckBox tick = view.findViewById(R.id.use_tick);
			Button renameOne = view.findViewById(R.id.use_rename);
			boolean left = group.kind() == Group.Kind.LEFT;
			String word = left ? group.name() : content.word();
			number.setText(String.valueOf(place.line() + 1));
			number.setMinWidth(Math.round(number.getPaint().measureText(
					"0") * digits));
			tick.setVisibility(renaming ? View.VISIBLE : View.GONE);
			tick.setChecked(ticked.contains(place));
			renameOne.setVisibility(left ? View.VISIBLE : View.GONE);
			renameOne.setContentDescription(context.getString(
					R.string.a11y_use_rename, place.line() + 1));
			renameOne.setOnClickListener(v -> actions.renameLeft(place));
			// After the views beside it are shown, so its room is known.
			Cut cut = cut(place.code(), place.column(), word.length(),
					columns(view, number, renaming ? tick : renameOne));
			SpannableStringBuilder sb = new SpannableStringBuilder(
					DocsSheet.colour(context, cut.text()));
			sb.setSpan(new ForegroundColorSpan(accent), cut.at(),
					Math.min(cut.text().length(), cut.at() + word.length()),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			code.setText(sb);
			String line = place.code().trim();
			view.setContentDescription(place.file().equals(content.shown())
					? context.getString(R.string.a11y_use,
					place.line() + 1, line)
					: context.getString(R.string.a11y_use_in, place.file(),
					place.line() + 1, line));
			// Renaming, the row is its box.
			boolean checks = renaming;
			ViewCompat.setAccessibilityDelegate(view,
					new AccessibilityDelegateCompat() {
						@Override
						public void onInitializeAccessibilityNodeInfo(
								@NonNull View host,
								@NonNull AccessibilityNodeInfoCompat info) {
							super.onInitializeAccessibilityNodeInfo(host,
									info);
							if (checks) {
								info.setClassName(CheckBox.class.getName());
								info.setCheckable(true);
								info.setChecked(ticked.contains(place));
							}
						}
					});
			view.setOnClickListener(v -> {
				if (!checks) {
					dialog.dismiss();
					actions.go(place);
					return;
				}
				if (!ticked.remove(place)) {
					ticked.add(place);
				}
				tick.setChecked(ticked.contains(place));
				tickedChanged.run();
			});
		}

		// How many characters of code fit in row beside number and
		// beside.
		private int columns(@NonNull View row, @NonNull TextView number,
				@NonNull View beside) {
			if (width <= 0) {
				return Integer.MAX_VALUE;
			}
			float room = width - row.getPaddingStart() -
					row.getPaddingEnd() - number.getMinWidth() -
					TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
							row.getResources().getDisplayMetrics());
			if (beside.getVisibility() == View.VISIBLE) {
				beside.measure(View.MeasureSpec.UNSPECIFIED,
						View.MeasureSpec.UNSPECIFIED);
				room -= beside.getMeasuredWidth() +
						((ViewGroup.MarginLayoutParams) beside
								.getLayoutParams()).getMarginStart();
			}
			TextView code = row.findViewById(R.id.use_code);
			// One less, so that rounding never cuts the line once more.
			return Math.max(1, (int) (room / code.getPaint()
					.measureText("m")) - 1);
		}
	}
}
