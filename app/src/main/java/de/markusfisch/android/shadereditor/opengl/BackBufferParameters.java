package de.markusfisch.android.shadereditor.opengl;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.graphics.BitmapEditor;
import de.markusfisch.android.shadereditor.project.legacy.LegacySamplerSettings;

// The library texture a backbuffer starts with, tiled to its size.
final class BackBufferParameters {
	static final BackBufferParameters NONE = new BackBufferParameters(null);

	@Nullable
	private final String preset;

	BackBufferParameters(@Nullable String preset) {
		this.preset = preset;
	}

	boolean hasPreset() {
		return preset != null;
	}

	@Nullable
	Bitmap getPresetBitmap(
			@NonNull DecodedTextures.BitmapSource source,
			int width,
			int height) {
		if (preset == null) {
			return null;
		}

		Bitmap tile = source.get(preset);
		long id = LegacySamplerSettings.presetId(preset);
		if (tile == null && id > 0) {
			tile = source.get(id);
		}

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
