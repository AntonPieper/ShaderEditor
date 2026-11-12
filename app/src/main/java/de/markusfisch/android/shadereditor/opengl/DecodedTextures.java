package de.markusfisch.android.shadereditor.opengl;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;

// The bitmaps a shader's samplers and backbuffer start with, decoded
// before the GL thread uploads them.
final class DecodedTextures implements TextureHandOver.Recyclable {
	interface BitmapSource {
		@Nullable
		Bitmap get(@NonNull String name);

		@Nullable
		default Bitmap get(long id) {
			return null;
		}
	}

	// Per sampler: null, one bitmap for a 2D texture, or six cubemap faces.
	@NonNull
	private final Bitmap[][] samplerBitmaps;
	@Nullable
	private Bitmap backBuffer;
	@Nullable
	private final String error;

	private DecodedTextures(@NonNull Bitmap[][] samplerBitmaps, @Nullable String error) {
		this.samplerBitmaps = samplerBitmaps;
		this.error = error;
	}

	// What a decode that threw leaves: nothing but the reason.
	@NonNull
	static DecodedTextures failed(@NonNull Throwable e) {
		String message = e.getMessage();
		return new DecodedTextures(new Bitmap[0][],
				"Cannot load the textures: " + (message != null ? message : e));
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

	// Throws when an image cannot be decoded or cut, after recycling what
	// it decoded so far.
	@NonNull
	static DecodedTextures decode(
			@NonNull List<DiscoveredSampler> samplers,
			@NonNull BackBufferParameters backBuffer,
			int width,
			int height,
			@NonNull BitmapSource source) {
		var decoded = new DecodedTextures(new Bitmap[samplers.size()][], null);
		try {
			for (int i = 0; i < samplers.size(); ++i) {
				var sampler = samplers.get(i);
				if (!isDecoded(sampler)) {
					continue;
				}
				Bitmap bitmap = source.get(sampler.name());
				if (bitmap == null) {
					continue;
				}
				if (sampler.target() == GLES20.GL_TEXTURE_2D) {
					decoded.samplerBitmaps[i] = new Bitmap[]{bitmap};
					continue;
				}
				try {
					decoded.samplerBitmaps[i] = new Bitmap[6];
					cutCubemap(sampler.name(), bitmap, decoded.samplerBitmaps[i]);
				} finally {
					bitmap.recycle();
				}
			}
			decoded.backBuffer = backBuffer.getPresetBitmap(source, width, height);
			return decoded;
		} catch (RuntimeException | OutOfMemoryError e) {
			decoded.recycle();
			throw e;
		}
	}

	// Whether the old 2x3 cubemap layout fits an image of this size: every
	// face it cuts is at least one pixel and inside the image.
	static boolean isCubemapAtlas(int width, int height) {
		int sideWidth = (int) Math.ceil(width / 2f);
		int sideHeight = Math.round(height / 3f);
		int sideLength = Math.min(sideWidth, sideHeight);
		return sideLength > 0 &&
				sideWidth + sideLength <= width &&
				sideHeight * 2 + sideLength <= height;
	}

	// The reason these textures cannot be used, or null.
	@Nullable
	String error() {
		return error;
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
					if (bitmap != null) {
						bitmap.recycle();
					}
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
	private static void cutCubemap(
			@NonNull String name,
			@NonNull Bitmap bitmap,
			@NonNull Bitmap[] faces) {
		int bitmapWidth = bitmap.getWidth();
		int bitmapHeight = bitmap.getHeight();
		if (!isCubemapAtlas(bitmapWidth, bitmapHeight)) {
			throw new IllegalArgumentException(String.format(Locale.US,
					"%s (%dx%d pixels) cannot be cut into the 2x3 squares of a cubemap",
					name, bitmapWidth, bitmapHeight));
		}
		int sideWidth = (int) Math.ceil(bitmapWidth / 2f);
		int sideHeight = Math.round(bitmapHeight / 3f);
		int sideLength = Math.min(sideWidth, sideHeight);
		int x = 0;
		int y = 0;

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
	}
}
