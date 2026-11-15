package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Face;
import de.markusfisch.android.shadereditor.navigation.ImageDecode;
import de.markusfisch.android.shadereditor.project.FileKind;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The images of folders read for the drawer: small for the rows on
 * screen, with their format and size in pixels, drawn as a row shows
 * an image (whole, in its own proportions, on a mat inside the row's
 * square, over a checkerboard where it is transparent, placed as
 * {@link Face#place} says), and whole for an image's sheet. Only what
 * shows is decoded, as {@link ImageDecode} plans it from the header:
 * the middle of a strip for a row, the screen's size for the sheet.
 * The provider's own thumbnail is asked first where it can serve. All
 * of it is read by an {@link ImageReader}, never on the queue projects
 * save on, and only while it is wanted. Faces are kept in memory only,
 * up to about {@link #KEPT} bytes. Call it on the main thread.
 */
final class ImageFaces {
	/** An image's face, its format and its size in pixels. */
	record Small(@NonNull Bitmap bitmap, @NonNull String format, int width,
			int height) {
	}

	/** An image as its sheet shows it, with what is said of it. */
	record Large(@NonNull Bitmap bitmap, @NonNull String format, int width,
			int height, long bytes) {
	}

	// What a face is drawn with, in pixels and colours.
	private record Frame(int square, int mat, int hairline, int check,
			int matColor, int light, int dark) {
	}

	/** The shorter side of a picture read, in pixels: a 40 dp row's. */
	static final int SIZE = 128;
	/** What the pictures kept may take, in bytes: about a hundred. */
	static final int KEPT = 4 << 20;
	// Enough of the start of an image to know its size.
	private static final int HEAD = 64 * 1024;
	// An image larger than this is not read to make a small picture.
	private static final long LARGEST = 16L << 20;
	// The key of the sheet's read: one at a time.
	private static final Object SHEET = new Object();

	private final LruCache<String, Small> kept = new LruCache<>(KEPT) {
		@Override
		protected int sizeOf(String id, Small small) {
			return small.bitmap().getByteCount();
		}
	};
	// What is no picture, so that it is not asked again; a read that
	// failed for now is asked again when its row is shown again.
	private final Set<String> unreadable = new HashSet<>();
	private final ImageReader reader = new ImageReader();
	private final Frame frame;
	// The sheet's limits: the screen's longer side and its pixels.
	private final int longest;
	private final long pixels;

	ImageFaces(@NonNull Context context) {
		DisplayMetrics metrics = context.getResources().getDisplayMetrics();
		float dp = metrics.density;
		frame = new Frame(Math.round(40 * dp), Math.round(4 * dp),
				Math.max(1, Math.round(dp)), Math.round(4 * dp),
				ContextCompat.getColor(context, R.color.se_picture_mat),
				0xff5c5f66, 0xff45484f);
		longest = Math.max(metrics.widthPixels, metrics.heightPixels);
		pixels = (long) metrics.widthPixels * metrics.heightPixels;
	}

	/** The image id read small, or null while it is not. */
	@Nullable
	Small get(@NonNull String id) {
		return kept.get(id);
	}

	/**
	 * Read the image id, the file at path of files, small; ready is
	 * called when it is, unless it was no longer wanted by then.
	 */
	void want(@NonNull String id, @NonNull ProjectFiles files,
			@NonNull ProjectPath path, @NonNull Runnable ready) {
		if (kept.get(id) != null || unreadable.contains(id) ||
				reader.asked(id)) {
			return;
		}
		Frame drawn = frame;
		reader.read(id, cancel -> read(files, path, drawn, cancel),
				(Small small, boolean again) -> {
					if (small != null) {
						kept.put(id, small);
						ready.run();
					} else if (!again) {
						unreadable.add(id);
					}
				});
	}

	/** The row of image id is not shown anymore. */
	void unwant(@NonNull String id) {
		reader.cancel(id);
	}

	/**
	 * Read the image at path of files for its sheet, in place of the
	 * one read for a sheet before; done hears it, or null and whether
	 * that may be tried again. The read stops when cancelled.
	 */
	@NonNull
	ImageReader.Cancel large(@NonNull ProjectFiles files,
			@NonNull ProjectPath path,
			@NonNull ImageReader.Done<Large> done) {
		int side = longest;
		long most = pixels;
		return reader.read(SHEET, cancel -> large(files, path, side, most,
				cancel), done);
	}

	/**
	 * Nothing asked for is wanted anymore: the level was left or the
	 * drawer closed. What is under way is let go.
	 */
	void stop() {
		reader.cancelAll();
	}

	void destroy() {
		reader.destroy();
	}

	// Off the main thread.
	@Nullable
	private static Small read(@NonNull ProjectFiles files,
			@NonNull ProjectPath path, @NonNull Frame frame,
			@NonNull ImageReader.Cancel cancel) throws IOException {
		byte[] head = files.head(path, HEAD);
		String format = head != null ? FileKind.picture(head) : null;
		if (head == null || format == null) {
			return null;
		}
		BitmapFactory.Options bounds = bounds(head);
		Bitmap bitmap = null;
		ImageDecode.Plan plan = bounds.outWidth > 0
				? ImageDecode.face(bounds.outWidth, bounds.outHeight, SIZE)
				: null;
		// The provider's thumbnail serves an image shown whole that is
		// not tiny: a tiny one would be blurred, a strip cut wrong.
		if (plan != null && plan.sample() > 1 &&
				plan.whole(bounds.outWidth, bounds.outHeight)) {
			bitmap = small(files.thumbnail(path, SIZE, cancel.signal));
		}
		if (cancel.cancelled()) {
			return null;
		}
		if (bitmap == null) {
			byte[] bytes = bytes(files, path);
			if (plan == null) {
				bounds = bounds(bytes);
				if (bounds.outWidth <= 0) {
					return null;
				}
				plan = ImageDecode.face(bounds.outWidth, bounds.outHeight,
						SIZE);
			}
			if (cancel.cancelled()) {
				return null;
			}
			bitmap = decode(bytes, plan, bounds);
		}
		return bitmap != null
				? new Small(face(bitmap, frame), format, bounds.outWidth,
						bounds.outHeight)
				: null;
	}

	// Off the main thread: the whole image at no more than the screen.
	@Nullable
	private static Large large(@NonNull ProjectFiles files,
			@NonNull ProjectPath path, int longest, long pixels,
			@NonNull ImageReader.Cancel cancel) throws IOException {
		byte[] bytes = bytes(files, path);
		String format = FileKind.picture(bytes);
		BitmapFactory.Options bounds = bounds(bytes);
		if (format == null || bounds.outWidth <= 0 || cancel.cancelled()) {
			return null;
		}
		Bitmap bitmap = decode(bytes, ImageDecode.whole(bounds.outWidth,
				bounds.outHeight, longest, pixels), bounds);
		return bitmap != null
				? new Large(bitmap, format, bounds.outWidth,
						bounds.outHeight, bytes.length)
				: null;
	}

	@NonNull
	private static byte[] bytes(@NonNull ProjectFiles files,
			@NonNull ProjectPath path) throws IOException {
		ProjectFiles.Read read = files.read(path, LARGEST);
		if (read instanceof ProjectFiles.Content content) {
			return content.bytes();
		}
		// Not there, or not readable now: may be tried again.
		throw new IOException(read instanceof ProjectFiles.Failed failed
				? failed.reason()
				: "it is gone");
	}

	@NonNull
	private static BitmapFactory.Options bounds(@NonNull byte[] bytes) {
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
		return bounds;
	}

	// The region of the plan, at its step: the whole image or, for a
	// strip, only its middle.
	@Nullable
	private static Bitmap decode(@NonNull byte[] bytes,
			@NonNull ImageDecode.Plan plan,
			@NonNull BitmapFactory.Options bounds) throws IOException {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = plan.sample();
		if (plan.whole(bounds.outWidth, bounds.outHeight)) {
			return BitmapFactory.decodeByteArray(bytes, 0, bytes.length,
					options);
		}
		@SuppressWarnings("deprecation")
		BitmapRegionDecoder region = BitmapRegionDecoder.newInstance(bytes,
				0, bytes.length, false);
		try {
			return region.decodeRegion(new Rect(plan.left(), plan.top(),
					plan.right(), plan.bottom()), options);
		} finally {
			region.recycle();
		}
	}

	// A provider's thumbnail no larger than a face needs: one that is
	// larger is scaled down, so none costs more than a decode would.
	@Nullable
	private static Bitmap small(@Nullable Bitmap thumbnail) {
		if (thumbnail == null) {
			return null;
		}
		int w = thumbnail.getWidth();
		int h = thumbnail.getHeight();
		int shorter = Math.min(w, h);
		if (shorter <= 2 * SIZE && Math.max(w, h) <= 6 * SIZE) {
			return thumbnail;
		}
		float scale = Math.min((float) SIZE / shorter,
				(float) (6 * SIZE) / Math.max(w, h));
		Bitmap scaled = Bitmap.createScaledBitmap(thumbnail,
				Math.max(1, Math.round(w * scale)),
				Math.max(1, Math.round(h * scale)), true);
		if (scaled != thumbnail) {
			thumbnail.recycle();
		}
		return scaled;
	}

	// The image on its mat, square, as a row shows it.
	@NonNull
	private static Bitmap face(@NonNull Bitmap image, @NonNull Frame frame) {
		int square = frame.square();
		Bitmap face = Bitmap.createBitmap(square, square,
				Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(face);
		canvas.drawColor(frame.matColor());
		Face.Placed placed = Face.place(image.getWidth(), image.getHeight(),
				square - 2 * frame.mat());
		int left = (square - placed.width()) / 2;
		int top = (square - placed.height()) / 2;
		Rect to = new Rect(left, top, left + placed.width(),
				top + placed.height());
		Paint paint = new Paint();
		if (image.hasAlpha()) {
			// Transparent pixels show over a checkerboard.
			canvas.save();
			canvas.clipRect(to);
			int check = frame.check();
			for (int y = to.top; y < to.bottom; y += check) {
				for (int x = to.left; x < to.right; x += check) {
					paint.setColor(((x - to.left) / check +
							(y - to.top) / check) % 2 == 0
							? frame.light()
							: frame.dark());
					canvas.drawRect(x, y, x + check, y + check, paint);
				}
			}
			canvas.restore();
		}
		paint.setFilterBitmap(!placed.crisp());
		canvas.drawBitmap(image, new Rect(placed.left(), placed.top(),
				placed.right(), placed.bottom()), to, paint);
		// A hairline keeps its edge where it is as dark as the mat.
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(frame.hairline());
		paint.setColor(0x73000000);
		float half = frame.hairline() / 2f;
		canvas.drawRect(to.left - half, to.top - half, to.right + half,
				to.bottom + half, paint);
		if (face != image) {
			image.recycle();
		}
		return face;
	}

}
