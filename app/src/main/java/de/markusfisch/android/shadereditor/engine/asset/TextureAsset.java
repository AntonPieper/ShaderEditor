package de.markusfisch.android.shadereditor.engine.asset;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

/**
 * A platform-agnostic representation of a texture asset's pixel data and properties.
 * It holds raw pixel data in a ByteBuffer, making it independent of any specific
 * platform's image classes (like android.graphics.Bitmap).
 */
public record TextureAsset(
		int width,
		int height,
		int format, // e.g., GLES32.GL_RGBA
		@NonNull ByteBuffer pixels
) implements Asset {
}