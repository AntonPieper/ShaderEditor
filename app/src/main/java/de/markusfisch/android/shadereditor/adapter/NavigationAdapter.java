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
import androidx.annotation.Nullable;
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

	/**
	 * The images rows stand for, read small while the rows are shown.
	 */
	public interface Images {
		/** The image of row read small, or null while it is not. */
		@Nullable
		Bitmap image(@NonNull Row row);

		/** The format and size of the image of row, as its line, or null. */
		@Nullable
		String size(@NonNull Row row);

		/** What the row is, with the size of its image, or null. */
		@Nullable
		String spoken(@NonNull Row row);

		/** Read the image of row small; ready is called when it is. */
		void want(@NonNull Row row, @NonNull Runnable ready);

		/** The row is not shown anymore. */
		void unwant(@NonNull Row row);
	}

	private static final int HEADING = 0;
	private static final int ROW = 1;
	private static final int EMPTY = 2;
	private static final int NOTICE = 3;

	private final Listener listener;
	@Nullable
	private Images images;
	// Decoded thumbnails, for as long as the listing holds their bytes.
	private final WeakHashMap<byte[], Bitmap> bitmaps = new WeakHashMap<>();
	private List<Item> items = List.of();
	private boolean empty;
	private Notice notice;

	public NavigationAdapter(@NonNull Listener listener) {
		this.listener = listener;
	}

	/** Takes what reads the images rows stand for. */
	public void setImages(@Nullable Images images) {
		this.images = images;
	}

	@Override
	public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
		if (holder instanceof RowHolder row && row.item != null &&
				row.item.row().picture().own() && images != null) {
			images.unwant(row.item.row());
		}
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
				case NO_SHADERS -> R.string.nav_library_empty;
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
				TextView button = way == Notice.Action.CHOOSE_AGAIN ||
						way == Notice.Action.NEW_SHADER
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
					case NEW_SHADER -> R.string.add_shader;
					case EXAMPLES -> R.string.load_sample;
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
			// Two lines or a 40 dp tile: a picture or an empty frame.
			Row.Picture face = row.picture();
			boolean tile = face.png() != null || face.resource() != 0 ||
					face.own() || face.frame();
			itemView.setMinimumHeight(itemView.getResources()
					.getDimensionPixelSize(row.detail() == null && !tile
							? R.dimen.se_row_height
							: R.dimen.se_row_height_two_line));
			bindPicture(item, row.picture());
			// Its place stays, so that the text lines up with the names.
			picture.setVisibility(row.kind() == Row.Kind.MORE
					? View.INVISIBLE
					: View.VISIBLE);
			// Beside the name when there is a second line, else centred.
			LinearLayout.LayoutParams place =
					(LinearLayout.LayoutParams) picture.getLayoutParams();
			place.gravity = row.detail() == null
					? Gravity.CENTER_VERTICAL
					: Gravity.TOP;
			place.topMargin = row.detail() == null
					? 0
					: itemView.getResources().getDimensionPixelSize(
							R.dimen.se_row_padding_vertical);
			name.setText(row.name());
			// The scale's medium weight for the selected row.
			TextViewCompat.setTextAppearance(name, item.selected()
					? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
					: com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
			name.setTextColor(AppCompatResources.getColorStateList(
					name.getContext(), R.color.navigation_row_text));
			// An image without a line of its own, such as its role in
			// the running shader, says its format and size once read.
			String size = row.picture().own() && images != null &&
					row.detail() == null
					? images.size(row)
					: null;
			String line = size != null ? size : row.detail();
			detail.setText(line);
			detail.setVisibility(line == null ? View.GONE : View.VISIBLE);
			// A file that cannot be opened is dimmed, its picture too.
			float alpha = row.picture().dim() ? 0.38f : 1f;
			name.setAlpha(alpha);
			detail.setAlpha(alpha);
			picture.setAlpha(alpha);
			unsaved.setVisibility(visibleIf(item, Row.Mark.UNSAVED));
			wallpaper.setVisibility(visibleIf(item, Row.Mark.WALLPAPER));
			readOnly.setVisibility(visibleIf(item, Row.Mark.READ_ONLY));
			running.setVisibility(visibleIf(item, Row.Mark.RUNNING));
			enters.setVisibility(row.kind() == Row.Kind.FOLDER
					? View.VISIBLE
					: View.GONE);
		}

		private void bindPicture(@NonNull Item item,
				@NonNull Row.Picture picture) {
			byte[] png = picture.png();
			Bitmap bitmap = png != null && png.length > 0 ? decode(png) : null;
			Row row = item.row();
			String spoken = spoken(picture.spoken());
			if (picture.own() && images != null) {
				bitmap = images.image(row);
				if (bitmap == null) {
					images.want(row, () -> {
						if (this.item == item) {
							bind(item);
						}
					});
				} else {
					String said = images.spoken(row);
					spoken = said != null ? said : spoken;
				}
			}
			// What the row is, said with its name and line.
			this.picture.setImportantForAccessibility(spoken != null
					? View.IMPORTANT_FOR_ACCESSIBILITY_YES
					: View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			this.picture.setContentDescription(spoken);
			if (picture.frame()) {
				// The empty place of a shader's picture: nothing in it.
				this.picture.setBackgroundResource(
						R.drawable.picture_empty_frame);
				ImageViewCompat.setImageTintList(this.picture, null);
				this.picture.setImageDrawable(null);
				return;
			}
			int icon = switch (picture.icon()) {
				case LIBRARY -> R.drawable.ic_grid_view;
				case EXAMPLES -> R.drawable.ic_book_2;
				case FOLDER -> R.drawable.ic_folder;
				case IMAGE -> R.drawable.ic_image;
				case CODE -> R.drawable.ic_code;
				case SETTINGS -> R.drawable.ic_tune;
				case DATA -> R.drawable.ic_data_object;
				case NOTES -> R.drawable.ic_description;
				case FILE -> R.drawable.ic_draft;
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

		@Nullable
		private String spoken(@NonNull Row.Picture.Spoken spoken) {
			int id = switch (spoken) {
				case NONE -> 0;
				case FOLDER -> R.string.nav_kind_folder;
				case SHADER -> R.string.nav_kind_shader;
				case SHADER_NOT_RUN -> R.string.nav_kind_shader_not_run;
				case IMAGE -> R.string.nav_kind_image;
				case INCLUDE -> R.string.nav_kind_include;
				case SETTINGS -> R.string.nav_kind_settings;
				case JSON -> R.string.nav_kind_json;
				case MARKDOWN -> R.string.nav_kind_markdown;
				case OTHER_LANGUAGE -> R.string.nav_kind_other_language;
				case FILE -> R.string.nav_kind_file;
				case CANNOT_OPEN -> R.string.nav_kind_cannot_open;
			};
			return id != 0 ? itemView.getContext().getString(id) : null;
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
