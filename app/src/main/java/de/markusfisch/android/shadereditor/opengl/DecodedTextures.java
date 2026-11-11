package de.markusfisch.android.shadereditor.opengl;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

// The bitmaps a shader's samplers and backbuffer start with, decoded
// before the GL thread uploads them.
final class DecodedTextures implements TextureHandOver.Recyclable {
	interface BitmapSource {
		@Nullable
		Bitmap get(@NonNull String name);
	}

	// Per sampler: null, one bitmap for a 2D texture, or six cubemap faces.
	@NonNull
	private final Bitmap[][] samplerBitmaps;
	@Nullable
	private final Bitmap backBuffer;

	private DecodedTextures(
			@NonNull Bitmap[][] samplerBitmaps,
			@Nullable Bitmap backBuffer) {
		this.samplerBitmaps = samplerBitmaps;
		this.backBuffer = backBuffer;
	}

	static boolean isNeeded(
			@NonNull List<DiscoveredSampler> samplers,
			@NonNull BackBufferParameters backBuffer) {
		if (backBuffer.hasPreset()) {
			return true;
		}
		for (var sampler : samplers) {
			if (isDecoded(sampler)) {
				return true;
			}
		}
		return false;
	}

	@NonNull
	static DecodedTextures decode(
			@NonNull List<DiscoveredSampler> samplers,
			@NonNull BackBufferParameters backBuffer,
			int width,
			int height,
			@NonNull BitmapSource source) {
		var samplerBitmaps = new Bitmap[samplers.size()][];
		for (int i = 0; i < samplerBitmaps.length; ++i) {
			var sampler = samplers.get(i);
			if (!isDecoded(sampler)) {
				continue;
			}
			Bitmap bitmap = source.get(sampler.name());
			if (bitmap == null) {
				continue;
			}
			if (sampler.target() == GLES20.GL_TEXTURE_2D) {
				samplerBitmaps[i] = new Bitmap[]{bitmap};
				continue;
			}
			try {
				samplerBitmaps[i] = cubemapFaces(bitmap);
			} finally {
				bitmap.recycle();
			}
		}
		return new DecodedTextures(
				samplerBitmaps,
				backBuffer.getPresetBitmap(source, width, height));
	}

	// Bitmaps of the sampler at this index of the decoded list, or null.
	@Nullable
	Bitmap[] get(int index) {
		return samplerBitmaps[index];
	}

	@Nullable
	Bitmap backBuffer() {
		return backBuffer;
	}

	@Override
	public void recycle() {
		for (var bitmaps : samplerBitmaps) {
			if (bitmaps != null) {
				for (var bitmap : bitmaps) {
					bitmap.recycle();
				}
			}
		}
		if (backBuffer != null) {
			backBuffer.recycle();
		}
	}

	private static boolean isDecoded(@NonNull DiscoveredSampler sampler) {
		return sampler.target() == GLES20.GL_TEXTURE_2D ||
				sampler.target() == GLES20.GL_TEXTURE_CUBE_MAP;
	}

	// Cuts the faces out of a 2x3 atlas, in GlDevice.CUBE_MAP_TARGETS order.
	@NonNull
	private static Bitmap[] cubemapFaces(@NonNull Bitmap bitmap) {
		int bitmapWidth = bitmap.getWidth();
		int bitmapHeight = bitmap.getHeight();
		int sideWidth = (int) Math.ceil(bitmapWidth / 2f);
		int sideHeight = Math.round(bitmapHeight / 3f);
		int sideLength = Math.min(sideWidth, sideHeight);
		int x = 0;
		int y = 0;
		var faces = new Bitmap[6];

		for (int i = 0; i < faces.length; ++i) {
			int[] sidePixels = new int[sideLength * sideLength];
			bitmap.getPixels(sidePixels, 0, sideLength, x, y, sideLength, sideLength);

			Bitmap side = Bitmap.createBitmap(
					sideLength,
					sideLength,
					Bitmap.Config.ARGB_8888);
			side.setPremultiplied(false);
			side.setPixels(sidePixels, 0, sideLength, 0, 0, sideLength, sideLength);
			faces[i] = side;

			if ((x += sideWidth) >= bitmapWidth) {
				x = 0;
				y += sideHeight;
			}
		}

		return faces;
	}
}
