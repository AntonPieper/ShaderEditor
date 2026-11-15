package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Face;
import de.markusfisch.android.shadereditor.project.FileKind;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The images of folders read small for the rows on screen, with their
 * format and size in pixels, and drawn as a row shows an image: whole,
 * in its own proportions, on a mat inside the row's square, over a
 * checkerboard where it is transparent, placed as {@link Face#place}
 * says. The provider's own thumbnail is read first, else the image at
 * about {@link #SIZE} pixels; a tiny image is read as it is, for its
 * crisp pixels. Each is read once, on the worker of its
 * project, one at a time, and only while its row is still wanted; a
 * row that scrolled away before its turn is not read. What was read
 * is kept in memory only, up to about {@link #KEPT} bytes. Call it on
 * the main thread.
 */
final class ImageFaces {
	/** An image's face, its format and its size in pixels. */
	record Small(@NonNull Bitmap bitmap, @NonNull String format, int width,
			int height) {
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

	private record Wanted(@NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Runnable ready) {
	}

	private final LruCache<String, Small> kept = new LruCache<>(KEPT) {
		@Override
		protected int sizeOf(String id, Small small) {
			return small.bitmap().getByteCount();
		}
	};
	// By id, the one asked for last after the others.
	private final Map<String, Wanted> wanted = new LinkedHashMap<>();
	// What could not be read, so that it is not asked again.
	private final Set<String> unreadable = new HashSet<>();
	private final Frame frame;
	private int generation;
	private boolean reading;

	ImageFaces(@NonNull Context context) {
		float dp = context.getResources().getDisplayMetrics().density;
		frame = new Frame(Math.round(40 * dp), Math.round(4 * dp),
				Math.max(1, Math.round(dp)), Math.round(4 * dp),
				ContextCompat.getColor(context, R.color.se_picture_mat),
				0xff5c5f66, 0xff45484f);
	}

	/** The image id read small, or null while it is not. */
	@Nullable
	Small get(@NonNull String id) {
		return kept.get(id);
	}

	/**
	 * Read the image id, the file at path of project, small; ready is
	 * called when it is, unless it was no longer wanted by then.
	 */
	void want(@NonNull String id, @NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Runnable ready) {
		if (kept.get(id) != null || unreadable.contains(id)) {
			return;
		}
		wanted.put(id, new Wanted(project, path, ready));
		next();
	}

	/** The row of image id is not shown anymore. */
	void unwant(@NonNull String id) {
		wanted.remove(id);
	}

	/**
	 * Nothing asked for is wanted anymore, as the folder's project is
	 * let go: what it would still answer is dropped.
	 */
	void stop() {
		wanted.clear();
		++generation;
		reading = false;
	}

	private void next() {
		if (reading || wanted.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<String, Wanted>> first =
				wanted.entrySet().iterator();
		Map.Entry<String, Wanted> entry = first.next();
		first.remove();
		String id = entry.getKey();
		Wanted asked = entry.getValue();
		reading = true;
		int asking = generation;
		Frame drawn = frame;
		asked.project().look(files -> read(files, asked.path(), drawn),
				small -> {
			if (asking != generation) {
				return;
			}
			reading = false;
			if (small != null) {
				kept.put(id, small);
				asked.ready().run();
			} else {
				unreadable.add(id);
			}
			next();
		});
	}

	// On the project's worker.
	@Nullable
	private static Small read(@NonNull ProjectFiles files,
			@NonNull ProjectPath path, @NonNull Frame frame) {
		try {
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			byte[] head = files.head(path, HEAD);
			String format = head != null ? FileKind.picture(head) : null;
			if (head == null || format == null) {
				return null;
			}
			BitmapFactory.decodeByteArray(head, 0, head.length, bounds);
			// A tiny image is read as it is: a provider's thumbnail of
			// it would be blurred.
			boolean tiny = bounds.outWidth > 0 &&
					Math.max(bounds.outWidth, bounds.outHeight) <= SIZE;
			Bitmap bitmap = tiny ? null : files.thumbnail(path, SIZE);
			if (bitmap == null || bounds.outWidth <= 0) {
				ProjectFiles.Read read = files.read(path, LARGEST);
				if (!(read instanceof ProjectFiles.Content content)) {
					return null;
				}
				byte[] bytes = content.bytes();
				if (bounds.outWidth <= 0) {
					BitmapFactory.decodeByteArray(bytes, 0, bytes.length,
							bounds);
				}
				if (bitmap == null) {
					BitmapFactory.Options options = new BitmapFactory.Options();
					options.inSampleSize = sample(bounds.outWidth,
							bounds.outHeight);
					bitmap = BitmapFactory.decodeByteArray(bytes, 0,
							bytes.length, options);
				}
			}
			return bitmap != null && bounds.outWidth > 0
					? new Small(face(bitmap, frame), format, bounds.outWidth,
							bounds.outHeight)
					: null;
		} catch (OutOfMemoryError | RuntimeException e) {
			return null;
		}
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

	// The largest power of two that keeps the shorter side at SIZE or
	// more.
	static int sample(int width, int height) {
		int shorter = Math.min(width, height);
		int sample = 1;
		while (shorter / (sample * 2) >= SIZE) {
			sample *= 2;
		}
		return sample;
	}
}
