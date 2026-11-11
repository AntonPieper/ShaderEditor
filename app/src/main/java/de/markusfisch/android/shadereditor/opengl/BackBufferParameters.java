package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.graphics.BitmapEditor;

// The library texture a backbuffer starts with, tiled to its size.
final class BackBufferParameters {
	static final BackBufferParameters NONE = new BackBufferParameters(null);

	@Nullable
	private final String preset;

	BackBufferParameters(@Nullable String preset) {
		this.preset = preset;
	}

	@Nullable
	Bitmap getPresetBitmap(@NonNull Context context, int width, int height) {
		if (preset == null) {
			return null;
		}

		// Use the modern singleton to get the DataSource and fetch the bitmap.
		Bitmap tile = Database
				.getInstance(context)
				.getDataSource()
				.texture
				.getTextureBitmap(preset);

		if (tile == null) {
			return null;
		}

		int tw = tile.getWidth();
		int th = tile.getHeight();
		int scaledHeight = Math.round((float) width / tw * th);

		try {
			Bitmap scaledTile = BitmapEditor.createScaledBitmap(
					tile,
					width,
					scaledHeight);
			if (scaledTile != tile) {
				tile.recycle();
			}
			tile = scaledTile;
		} catch (IllegalArgumentException e) {
			tile.recycle();
			return null;
		}

		Bitmap background = Bitmap.createBitmap(
				width,
				height,
				Bitmap.Config.ARGB_8888);
		background.setPremultiplied(false);

		int[] tilePixels = new int[width * scaledHeight];
		tile.getPixels(tilePixels, 0, width, 0, 0, width, scaledHeight);
		int[] backgroundPixels = new int[width * height];
		for (int y = 0; y < height; ++y) {
			int mirroredY = y % (scaledHeight * 2);
			if (mirroredY >= scaledHeight) {
				mirroredY = (scaledHeight * 2) - mirroredY - 1;
			}
			System.arraycopy(
					tilePixels,
					mirroredY * width,
					backgroundPixels,
					y * width,
					width);
		}
		background.setPixels(backgroundPixels, 0, width, 0, 0, width, height);
		tile.recycle();

		return background;
	}
}
