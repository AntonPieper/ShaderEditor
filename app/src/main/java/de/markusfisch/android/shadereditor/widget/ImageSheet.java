package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import de.markusfisch.android.shadereditor.R;

/**
 * An image of a folder in a sheet: its name, where it is and what uses
 * it, the picture fitted on a checkerboard, its size, and what can be
 * done with it. Insert as texture shows the lines it adds before it is
 * tapped, and where it cannot be offered one sentence says why.
 */
public final class ImageSheet {
	/** What the sheet's buttons do. */
	public interface Listener {
		void onInsert();

		void onRename();

		void onDelete();
	}

	private final BottomSheetDialog dialog;
	private final ImageView picture;
	private final TextView info;

	/**
	 * @param where    the folder and, when the running shader reads
	 *                 it, which shader
	 * @param into     the file Insert as texture adds to, or null when
	 *                 it cannot be offered
	 * @param lines    the lines it adds, or why it cannot be offered
	 * @param noChanges why the image cannot be renamed or deleted
	 *                  here, or null when Rename and Delete are offered
	 */
	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public ImageSheet(@NonNull Context context, @NonNull String name,
			@NonNull String where, @Nullable String into,
			@NonNull String lines, @Nullable String noChanges,
			@NonNull Listener listener) {
		View view = LayoutInflater.from(context).inflate(R.layout.sheet_image,
				null);
		((FileNameView) view.findViewById(R.id.image_name)).setName(name);
		((TextView) view.findViewById(R.id.image_where)).setText(where);
		picture = view.findViewById(R.id.image_picture);
		picture.setBackground(new Checks(picture, checks(context)));
		picture.setClipToOutline(true);
		info = view.findViewById(R.id.image_info);
		TextView says = view.findViewById(R.id.image_insert_says);
		TextView code = view.findViewById(R.id.image_insert_lines);
		View insert = view.findViewById(R.id.image_insert);
		if (into != null) {
			says.setText(context.getString(R.string.nav_insert_adds, into));
			code.setText(lines);
		} else {
			says.setText(lines);
			code.setVisibility(View.GONE);
			// No button that does nothing.
			insert.setVisibility(View.GONE);
		}
		View rename = view.findViewById(R.id.image_rename);
		View delete = view.findViewById(R.id.image_delete);
		boolean changes = noChanges == null;
		rename.setVisibility(changes ? View.VISIBLE : View.GONE);
		delete.setVisibility(changes ? View.VISIBLE : View.GONE);
		if (!changes) {
			// No button that is gone without a word.
			TextView why = view.findViewById(R.id.image_no_changes);
			why.setText(noChanges);
			why.setVisibility(View.VISIBLE);
		}
		dialog = new BottomSheetDialog(context);
		insert.setOnClickListener(v -> {
			dialog.dismiss();
			listener.onInsert();
		});
		rename.setOnClickListener(v -> {
			dialog.dismiss();
			listener.onRename();
		});
		delete.setOnClickListener(v -> {
			dialog.dismiss();
			listener.onDelete();
		});
		dialog.setContentView(view);
		BottomSheetBehavior<?> behavior = dialog.getBehavior();
		behavior.setSkipCollapsed(true);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
	}

	public void show() {
		dialog.show();
	}

	public void dismiss() {
		dialog.dismiss();
	}

	/** Run when the sheet goes, however it goes. */
	public void setOnDismiss(@NonNull Runnable gone) {
		dialog.setOnDismissListener(d -> gone.run());
	}

	public boolean isShowing() {
		return dialog.isShowing();
	}

	/**
	 * Shows the image, read, with what is said of it, like
	 * "256 × 256 · PNG · 86 KB". A tiny image keeps crisp pixels.
	 */
	public void setImage(@NonNull Bitmap bitmap, @NonNull String said) {
		BitmapDrawable drawable = new BitmapDrawable(
				picture.getResources(), bitmap);
		float density = picture.getResources().getDisplayMetrics().density;
		drawable.setFilterBitmap(Math.max(bitmap.getWidth(),
				bitmap.getHeight()) > 120 * density);
		picture.setImageDrawable(drawable);
		info.setText(said);
	}

	/** Says that the file cannot be shown as a picture. */
	public void setUnreadable(@NonNull String said) {
		info.setText(said);
	}

	// Transparent pixels show over a checkerboard of 8 dp squares.
	@NonNull
	private static Paint checks(@NonNull Context context) {
		int check = Math.round(8 * context.getResources()
				.getDisplayMetrics().density);
		Bitmap tile = Bitmap.createBitmap(check * 2, check * 2,
				Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(tile);
		Paint paint = new Paint();
		canvas.drawColor(0xff34373e);
		paint.setColor(0xff2a2c32);
		canvas.drawRect(0, 0, check, check, paint);
		canvas.drawRect(check, check, check * 2, check * 2, paint);
		Paint board = new Paint();
		board.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT,
				Shader.TileMode.REPEAT));
		return board;
	}

	// The checkerboard behind the picture only, where it is fitted: no
	// bars beside an image that is not as wide as the place.
	private static final class Checks extends Drawable {
		private final ImageView view;
		private final Paint paint;
		private final RectF fitted = new RectF();

		Checks(@NonNull ImageView view, @NonNull Paint paint) {
			this.view = view;
			this.paint = paint;
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Drawable image = view.getDrawable();
			if (image == null) {
				return;
			}
			fitted.set(0, 0, image.getIntrinsicWidth(),
					image.getIntrinsicHeight());
			view.getImageMatrix().mapRect(fitted);
			fitted.offset(view.getPaddingLeft(), view.getPaddingTop());
			canvas.drawRect(fitted, paint);
		}

		@Override
		public void setAlpha(int alpha) {
			paint.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter filter) {
			paint.setColorFilter(filter);
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}
}
