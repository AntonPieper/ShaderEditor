package de.markusfisch.android.shadereditor.adapter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.WeakHashMap;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Item;
import de.markusfisch.android.shadereditor.navigation.Notice;
import de.markusfisch.android.shadereditor.navigation.Row;

public class NavigationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	public interface Listener {
		void onTap(@NonNull Item item);

		void onLongPress(@NonNull Item item, @NonNull View view);

		void onNewShader();

		void onShowExamples();

		void onNotice(@NonNull Notice.Action action);
	}

	private static final int HEADING = 0;
	private static final int ROW = 1;
	private static final int EMPTY = 2;
	private static final int NOTICE = 3;

	private final Listener listener;
	// Decoded thumbnails, for as long as the listing holds their bytes.
	private final WeakHashMap<byte[], Bitmap> bitmaps = new WeakHashMap<>();
	private List<Item> items = List.of();
	private boolean empty;
	private Notice notice;

	public NavigationAdapter(@NonNull Listener listener) {
		this.listener = listener;
	}

	/**
	 * @param empty  whether to say that there is nothing, when there is
	 *               nothing to show and no notice
	 * @param notice what stands under the items, or null
	 */
	public void setItems(@NonNull List<Item> items, boolean empty,
			Notice notice) {
		this.items = items;
		this.notice = notice;
		this.empty = empty && items.isEmpty() && notice == null;
		notifyDataSetChanged();
	}

	/** The position of the selected row, or -1. */
	public int getSelectedPosition() {
		for (int i = 0, size = empty ? 0 : items.size(); i < size; ++i) {
			if (items.get(i).selected()) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public int getItemCount() {
		return empty ? 1 : items.size() + (notice != null ? 1 : 0);
	}

	@Override
	public int getItemViewType(int position) {
		if (empty) {
			return EMPTY;
		} else if (position == items.size()) {
			return NOTICE;
		}
		return items.get(position).row() == null ? HEADING : ROW;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
			int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == EMPTY) {
			View view = inflater.inflate(R.layout.row_navigation_empty,
					parent, false);
			view.findViewById(R.id.navigation_empty_new).setOnClickListener(
					v -> listener.onNewShader());
			view.findViewById(R.id.navigation_empty_examples)
					.setOnClickListener(v -> listener.onShowExamples());
			return new RecyclerView.ViewHolder(view) {
			};
		}
		if (viewType == NOTICE) {
			return new NoticeHolder(inflater.inflate(
					R.layout.row_navigation_notice, parent, false));
		}
		if (viewType == HEADING) {
			return new HeadingHolder(inflater.inflate(
					R.layout.row_navigation_heading, parent, false));
		}
		return new RowHolder(inflater.inflate(
				R.layout.row_navigation, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
			int position) {
		if (holder instanceof NoticeHolder noticeHolder) {
			noticeHolder.bind(notice);
		} else if (holder instanceof RowHolder rowHolder) {
			rowHolder.bind(items.get(position));
		} else if (holder instanceof HeadingHolder headingHolder) {
			headingHolder.line.setVisibility(position > 0
					? View.VISIBLE
					: View.GONE);
			headingHolder.heading.setText(items.get(position).heading());
		}
	}

	private static final class HeadingHolder extends RecyclerView.ViewHolder {
		private final View line;
		private final TextView heading;

		private HeadingHolder(@NonNull View view) {
			super(view);
			line = view.findViewById(R.id.navigation_heading_line);
			heading = view.findViewById(R.id.navigation_heading);
		}
	}

	private final class NoticeHolder extends RecyclerView.ViewHolder {
		private final View progress;
		private final TextView title;
		private final TextView reason;
		private final TextView main;
		private final TextView action;

		private NoticeHolder(@NonNull View view) {
			super(view);
			progress = view.findViewById(R.id.navigation_notice_progress);
			title = view.findViewById(R.id.navigation_notice_title);
			reason = view.findViewById(R.id.navigation_notice_reason);
			main = view.findViewById(R.id.navigation_notice_main);
			action = view.findViewById(R.id.navigation_notice_action);
		}

		private void bind(@NonNull Notice notice) {
			Notice.Kind kind = notice.kind();
			progress.setVisibility(kind == Notice.Kind.READING ||
					kind == Notice.Kind.SLOW ? View.VISIBLE : View.GONE);
			int text = switch (kind) {
				case SLOW -> R.string.nav_still_reading;
				case STOPPED -> R.string.nav_stopped;
				case EMPTY -> R.string.nav_empty_folder;
				case FAILED -> R.string.nav_cannot_read;
				case LOST_FOLDER -> R.string.nav_lost_folder;
				case LOST_FILE -> R.string.nav_lost_file;
				default -> 0;
			};
			title.setVisibility(text != 0 ? View.VISIBLE : View.GONE);
			if (text != 0) {
				title.setText(text);
			}
			reason.setVisibility(notice.reason() != null
					? View.VISIBLE
					: View.GONE);
			reason.setText(notice.reason());
			// Choosing again is the one main way out; the other is beside.
			List<Notice.Action> actions = notice.actions();
			main.setVisibility(View.GONE);
			action.setVisibility(View.GONE);
			for (Notice.Action way : actions) {
				TextView button = way == Notice.Action.CHOOSE_AGAIN
						? main
						: action;
				button.setVisibility(View.VISIBLE);
				button.setText(switch (way) {
					case STOP -> R.string.nav_stop;
					case TRY_AGAIN -> R.string.nav_try_again;
					case CHOOSE_AGAIN -> kind == Notice.Kind.LOST_FILE
							? R.string.nav_choose_file_again
							: R.string.nav_choose_folder_again;
					case REMOVE -> R.string.nav_remove_from_list;
				});
				button.setOnClickListener(v -> listener.onNotice(way));
			}
		}
	}

	private final class RowHolder extends RecyclerView.ViewHolder {
		private final ImageView picture;
		private final TextView name;
		private final TextView detail;
		private final View unsaved;
		private final View wallpaper;
		private final View readOnly;
		private final View running;
		private final View enters;
		private Item item;

		private RowHolder(@NonNull View view) {
			super(view);
			picture = view.findViewById(R.id.navigation_picture);
			picture.setClipToOutline(true);
			name = view.findViewById(R.id.navigation_name);
			detail = view.findViewById(R.id.navigation_detail);
			unsaved = view.findViewById(R.id.navigation_unsaved);
			wallpaper = view.findViewById(R.id.navigation_wallpaper);
			readOnly = view.findViewById(R.id.navigation_read_only_mark);
			running = view.findViewById(R.id.navigation_running);
			enters = view.findViewById(R.id.navigation_enters);
			view.setOnClickListener(v -> listener.onTap(item));
			view.setOnLongClickListener(v -> {
				listener.onLongPress(item, v);
				return true;
			});
		}

		private void bind(@NonNull Item item) {
			this.item = item;
			Row row = item.row();
			itemView.setSelected(item.selected());
			itemView.setMinimumHeight(itemView.getResources()
					.getDimensionPixelSize(row.detail() == null
							? R.dimen.se_row_height
							: R.dimen.se_row_height_two_line));
			bindPicture(row.picture());
			// Its place stays, so that the text lines up with the names.
			picture.setVisibility(row.kind() == Row.Kind.MORE
					? View.INVISIBLE
					: View.VISIBLE);
			// Beside the name when there is a second line, else centred.
			((LinearLayout.LayoutParams) picture.getLayoutParams()).gravity =
					row.detail() == null ? Gravity.CENTER_VERTICAL : Gravity.TOP;
			name.setText(row.name());
			// The scale's medium weight for the selected row.
			TextViewCompat.setTextAppearance(name, item.selected()
					? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
					: com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
			name.setTextColor(AppCompatResources.getColorStateList(
					name.getContext(), R.color.navigation_row_text));
			detail.setText(row.detail());
			detail.setVisibility(row.detail() == null ? View.GONE : View.VISIBLE);
			unsaved.setVisibility(visibleIf(item, Row.Mark.UNSAVED));
			wallpaper.setVisibility(visibleIf(item, Row.Mark.WALLPAPER));
			readOnly.setVisibility(visibleIf(item, Row.Mark.READ_ONLY));
			running.setVisibility(visibleIf(item, Row.Mark.RUNNING));
			enters.setVisibility(row.kind() == Row.Kind.FOLDER
					? View.VISIBLE
					: View.GONE);
		}

		private void bindPicture(@NonNull Row.Picture picture) {
			byte[] png = picture.png();
			Bitmap bitmap = png != null && png.length > 0 ? decode(png) : null;
			int icon = switch (picture.icon()) {
				case LIBRARY -> R.drawable.ic_grid_view;
				case EXAMPLES -> R.drawable.ic_book_2;
				case FOLDER -> R.drawable.ic_folder;
				case IMAGE -> R.drawable.ic_image;
				case SHADER -> R.drawable.ic_draft;
			};
			boolean isIcon = bitmap == null && picture.resource() == 0;
			this.picture.setBackgroundResource(isIcon
					? 0
					: R.drawable.picture_placeholder);
			this.picture.setScaleType(isIcon
					? ImageView.ScaleType.CENTER
					: ImageView.ScaleType.CENTER_CROP);
			ImageViewCompat.setImageTintList(this.picture, isIcon
					? AppCompatResources.getColorStateList(
					itemView.getContext(), R.color.navigation_row_mark)
					: null);
			if (bitmap != null) {
				this.picture.setImageBitmap(bitmap);
			} else if (picture.resource() != 0) {
				this.picture.setImageResource(picture.resource());
			} else {
				this.picture.setImageResource(icon);
			}
		}

		private Bitmap decode(@NonNull byte[] png) {
			Bitmap bitmap = bitmaps.get(png);
			if (bitmap == null) {
				bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
				if (bitmap != null) {
					bitmaps.put(png, bitmap);
				}
			}
			return bitmap;
		}
	}

	private static int visibleIf(@NonNull Item item, @NonNull Row.Mark mark) {
		return item.marks().contains(mark) ? View.VISIBLE : View.GONE;
	}
}
