package de.markusfisch.android.shadereditor.platform.asset;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.markusfisch.android.shadereditor.engine.asset.AssetLoader;
import de.markusfisch.android.shadereditor.engine.asset.TextureAsset;

/**
 * An Android-specific implementation of {@link AssetLoader} for {@link TextureAsset}.
 * It uses Android's {@link BitmapFactory} to decode an InputStream into a Bitmap,
 * then extracts the raw pixel data into a ByteBuffer to create a platform-agnostic
 * TextureAsset.
 */
public class AndroidTextureLoader implements AssetLoader<TextureAsset> {
	@NonNull
	@Override
	public TextureAsset load(@NonNull InputStream dataStream) throws IOException {
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false; // No pre-scaling.
		final Bitmap bitmap = BitmapFactory.decodeStream(dataStream, null, options);

		if (bitmap == null) {
			throw new IOException("Failed to decode bitmap from stream");
		}

		final int width = bitmap.getWidth();
		final int height = bitmap.getHeight();

		final ByteBuffer buffer = ByteBuffer.allocateDirect(bitmap.getByteCount())
				.order(ByteOrder.nativeOrder());
		bitmap.copyPixelsToBuffer(buffer);
		buffer.rewind();

		// The most common and compatible format is GL_RGBA.
		final int format = GLES20.GL_RGBA;

		bitmap.recycle();

		return new TextureAsset(width, height, format, buffer);
	}
}