// engine/scene/Image2D.java
package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.GpuObject;
import de.markusfisch.android.shadereditor.engine.asset.TextureAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;
import de.markusfisch.android.shadereditor.engine.graphics.TextureInternalFormat;

/**
 * A 2D image you can bind to a sampler; some variants can also be attached for rendering.
 */
public sealed interface Image2D extends GpuObject {

	/**
	 * Immutable uploaded image (sample-only; not attachable).
	 */
	record FromAsset(
			@NonNull TextureAsset asset,
			@NonNull TextureInternalFormat internalFormat,
			@NonNull TextureParameters sampling
	) implements Image2D {
	}

	/**
	 * Immutable renderable + sample-able image (attach to FBO, then sample later).
	 */
	record RenderTarget(
			@NonNull String name,
			int width,
			int height,
			@NonNull TextureInternalFormat internalFormat,
			@NonNull TextureParameters sampling
	) implements Image2D {
		/**
		 * Validates that the dimensions for a render target are positive.
		 */
		public RenderTarget {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException(
						"RenderTarget dimensions must be positive. Got " + width + "x" + height);
			}
		}
	}
}