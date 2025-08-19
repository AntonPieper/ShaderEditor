package de.markusfisch.android.shadereditor.engine.data;

import de.markusfisch.android.shadereditor.engine.scene.RenderTarget;
import de.markusfisch.android.shadereditor.engine.scene.TextureSource;

/**
 * Defines the data keys for accessing the platform-provided backbuffer service.
 * This allows plugins to declaratively request resources for feedback loops.
 */
public final class BackbufferDataKeys {
	/**
	 * Provides the "read" texture for the backbuffer swapchain from the *previous* frame.
	 * This is the key to use when binding the `backbuffer` uniform.
	 */
	public static final DataKey<TextureSource> BACKBUFFER_TEXTURE = DataKey.of(
			"platform.backbuffer.texture", TextureSource.class);

	/**
	 * Provides the "write" render target for the backbuffer swapchain for the *current* frame.
	 * This is the key to use when a render pass should output to the backbuffer.
	 */
	public static final DataKey<RenderTarget> BACKBUFFER_TARGET = DataKey.of(
			"platform.backbuffer.target", RenderTarget.class);

	private BackbufferDataKeys() {
		throw new UnsupportedOperationException(
				"This is a utility class and cannot be instantiated");
	}
}