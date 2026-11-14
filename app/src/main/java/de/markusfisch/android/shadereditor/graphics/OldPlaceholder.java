package de.markusfisch.android.shadereditor.graphics;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.R;

/**
 * The grey square the database gave a shader that had not run yet. It
 * was kept as the shader's picture, and the move into the library kept
 * it too; a file with it has no picture, and gets one when it runs.
 */
public final class OldPlaceholder {
	// The placeholder is a few hundred bytes; a picture of more is
	// never it, and is not decoded to tell.
	private static final int MAX_BYTES = 4096;

	@Nullable
	private static Bitmap placeholder;

	private OldPlaceholder() {
	}

	/**
	 * Return png, or null when there is none or it shows the old
	 * placeholder. Call it off the main thread.
	 */
	@Nullable
	public static byte[] real(@NonNull Context context, @Nullable byte[] png) {
		if (png == null || png.length == 0) {
			return null;
		} else if (png.length > MAX_BYTES) {
			return png;
		}
		Bitmap old = placeholder(context);
		Bitmap picture = BitmapFactory.decodeByteArray(png, 0, png.length);
		if (picture == null || old == null) {
			return png;
		}
		boolean same = picture.sameAs(old);
		picture.recycle();
		return same ? null : png;
	}

	@Nullable
	private static synchronized Bitmap placeholder(@NonNull Context context) {
		if (placeholder == null) {
			placeholder = BitmapFactory.decodeResource(context.getResources(),
					R.drawable.thumbnail_new_shader);
		}
		return placeholder;
	}
}
