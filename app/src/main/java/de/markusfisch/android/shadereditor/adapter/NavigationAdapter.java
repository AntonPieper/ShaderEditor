package de.markusfisch.android.shadereditor.adapter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.List;
import java.util.WeakHashMap;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Item;
import de.markusfisch.android.shadereditor.navigation.Row;

public class NavigationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	public interface Listener {
		void onTap(@NonNull Item item);

		void onLongPress(@NonNull Item item, @NonNull View view);
	}

	private static final int HEADING = 0;
	private static final int ROW = 1;

	private final Listener listener;
	// Decoded thumbnails, for as long as the listing holds their bytes.
	private final WeakHashMap<byte[], Bitmap> bitmaps = new WeakHashMap<>();
	private List<Item> items = List.of();

	public NavigationAdapter(@NonNull Listener listener) {
		this.listener = listener;
	}

	public void setItems(@NonNull List<Item> items) {
		this.items = items;
		notifyDataSetChanged();
	}

	/** The position of the selected row, or -1. */
	public int getSelectedPosition() {
		for (int i = 0, size = items.size(); i < size; ++i) {
			if (items.get(i).selected()) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	@Override
	public int getItemViewType(int position) {
		return items.get(position).row() == null ? HEADING : ROW;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
			int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == HEADING) {
			return new RecyclerView.ViewHolder(inflater.inflate(
					R.layout.row_navigation_heading, parent, false)) {
			};
		}
		return new RowHolder(inflater.inflate(
				R.layout.row_navigation, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
			int position) {
		Item item = items.get(position);
		if (holder instanceof RowHolder rowHolder) {
			rowHolder.bind(item);
		} else {
			((TextView) holder.itemView).setText(item.heading());
		}
	}

	private final class RowHolder extends RecyclerView.ViewHolder {
		private final ImageView picture;
		private final TextView name;
		private final TextView detail;
		private final View wallpaper;
		private final View running;
		private Item item;

		private RowHolder(@NonNull View view) {
			super(view);
			picture = view.findViewById(R.id.navigation_picture);
			picture.setClipToOutline(true);
			name = view.findViewById(R.id.navigation_name);
			detail = view.findViewById(R.id.navigation_detail);
			wallpaper = view.findViewById(R.id.navigation_wallpaper);
			running = view.findViewById(R.id.navigation_running);
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
							? R.dimen.navigation_row_height
							: R.dimen.navigation_row_height_two_lines));
			bindPicture(row.picture());
			name.setText(row.name());
			// The scale's medium weight for the selected row.
			TextViewCompat.setTextAppearance(name, item.selected()
					? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
					: com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
			name.setTextColor(MaterialColors.getColor(name,
					com.google.android.material.R.attr.colorOnSurface));
			detail.setText(row.detail());
			detail.setVisibility(row.detail() == null ? View.GONE : View.VISIBLE);
			wallpaper.setVisibility(visibleIf(item, Row.Mark.WALLPAPER));
			running.setVisibility(visibleIf(item, Row.Mark.RUNNING));
		}

		private void bindPicture(@NonNull Row.Picture picture) {
			this.picture.setBackgroundResource(R.drawable.navigation_thumbnail);
			this.picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
			byte[] png = picture.png();
			Bitmap bitmap = png != null && png.length > 0 ? decode(png) : null;
			if (bitmap != null) {
				this.picture.setImageBitmap(bitmap);
			} else if (picture.resource() != 0) {
				this.picture.setImageResource(picture.resource());
			} else {
				this.picture.setImageResource(R.drawable.thumbnail_new_shader);
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
