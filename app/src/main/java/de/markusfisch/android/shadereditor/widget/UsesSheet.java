package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;

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
 */
public final class UsesSheet {
	/** Goes to a place the user chose; the sheet has closed. */
	public interface Go {
		void go(@NonNull WordPlaces.Place place);
	}

	/**
	 * What the sheet lists.
	 *
	 * @param shown  the file in the editor, whose places name no file
	 * @param unread how many files could not be read
	 * @param glued  whether a file glues names together with ##
	 */
	public record Content(@NonNull String word, @NonNull String shown,
			@NonNull List<Group> groups, int unread, boolean glued) {
	}

	/** Where the line is cut so that the name shows, see {@link #cut}. */
	record Cut(@NonNull String text, int at) {
	}

	private static final float MAX_HEIGHT = .9f;
	private static final char ELLIPSIS = '…';

	private final BottomSheetDialog dialog;
	private final View view;
	private final Adapter adapter;

	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public UsesSheet(@NonNull Context context, @NonNull Typeface font,
			@NonNull Go go) {
		view = LayoutInflater.from(context).inflate(R.layout.sheet_uses,
				null);
		dialog = new BottomSheetDialog(context);
		TextView word = view.findViewById(R.id.uses_word);
		word.setTypeface(font);
		ViewCompat.setAccessibilityHeading(word, true);
		RecyclerView list = view.findViewById(R.id.uses_list);
		list.setLayoutManager(new LinearLayoutManager(context));
		adapter = new Adapter(font, MaterialColors.getColor(view,
				androidx.appcompat.R.attr.colorPrimary), place -> {
			dialog.dismiss();
			go.go(place);
		});
		list.setAdapter(adapter);
		// The code is cut to the width there is.
		list.addOnLayoutChangeListener((v, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != oldRight - oldLeft) {
				list.post(() -> adapter.setWidth(right - left));
			}
		});
		dialog.setContentView(view);
		BottomSheetBehavior<?> behavior = dialog.getBehavior();
		behavior.setMaxHeight(Math.round(MAX_HEIGHT *
				context.getResources().getDisplayMetrics().heightPixels));
		behavior.setSkipCollapsed(true);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
	}

	/** Run when the sheet goes, however it goes. */
	public void setOnDismiss(@NonNull Runnable gone) {
		dialog.setOnDismissListener(d -> gone.run());
	}

	/** List content, and show the sheet if it is not shown. */
	public void show(@NonNull Content content) {
		Context context = view.getContext();
		Resources res = context.getResources();
		((TextView) view.findViewById(R.id.uses_word)).setText(
				content.word());
		dialog.setTitle(content.word());
		int places = 0;
		Set<String> files = new HashSet<>();
		for (Group group : content.groups()) {
			if (group.kind() != Group.Kind.COMMENTS) {
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
		adapter.show(content);
		if (!dialog.isShowing()) {
			dialog.show();
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

	private static final class Adapter
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

		private final Set<Group.Kind> unfolded = EnumSet.noneOf(
				Group.Kind.class);
		private final Typeface font;
		private final int accent;
		private final Go go;
		@NonNull
		private List<Row> rows = Collections.emptyList();
		@Nullable
		private Content content;
		private int width;
		// Of the widest line number listed.
		private int digits = 1;

		private Adapter(@NonNull Typeface font, int accent, @NonNull Go go) {
			this.font = font;
			this.accent = accent;
			this.go = go;
		}

		private void show(@NonNull Content content) {
			this.content = content;
			int last = 0;
			for (Group group : content.groups()) {
				for (WordPlaces.Place place : group.places()) {
					last = Math.max(last, place.line() + 1);
				}
			}
			digits = String.valueOf(last).length();
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
				boolean folds = kind == Group.Kind.OTHER ||
						kind == Group.Kind.COMMENTS;
				list.add(new Row(folds ? FOLD : HEADING, group, null, null));
				if (folds && !unfolded.contains(kind)) {
					continue;
				}
				boolean byFile = folds || kind == Group.Kind.ELSEWHERE;
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
						default -> group.name();
					});
					ViewCompat.setAccessibilityHeading(view, true);
				}
				case FILE -> ((TextView) view).setText(row.file());
				case FOLD -> bindFold((TextView) view, group.kind(),
						group.places().size());
				default -> bindPlace(view, row.place());
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

		private void bindPlace(@NonNull View view,
				@NonNull WordPlaces.Place place) {
			Context context = view.getContext();
			TextView number = view.findViewById(R.id.use_line);
			TextView code = view.findViewById(R.id.use_code);
			number.setText(String.valueOf(place.line() + 1));
			number.setMinWidth(Math.round(number.getPaint().measureText(
					"0") * digits));
			String word = content.word();
			Cut cut = cut(place.code(), place.column(), word.length(),
					columns(view, number));
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
			view.setOnClickListener(v -> go.go(place));
		}

		// How many characters of code fit in row beside number.
		private int columns(@NonNull View row, @NonNull TextView number) {
			if (width <= 0) {
				return Integer.MAX_VALUE;
			}
			float room = width - row.getPaddingStart() -
					row.getPaddingEnd() - number.getMinWidth() -
					TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
							row.getResources().getDisplayMetrics());
			TextView code = row.findViewById(R.id.use_code);
			// One less, so that rounding never cuts the line once more.
			return Math.max(1, (int) (room / code.getPaint()
					.measureText("m")) - 1);
		}
	}
}
