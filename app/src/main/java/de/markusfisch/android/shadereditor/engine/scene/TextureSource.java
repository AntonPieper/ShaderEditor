package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.GpuObject;

/**
 * A declarative representation of the source for a texture sampler.
 * This tells the engine *what* texture to bind, including special
 * sources like the "read" buffer from a feedback loop.
 */
public sealed interface TextureSource extends GpuObject {
	/**
	 * A texture sourced from a concrete {@link Image2D} (e.g., loaded asset or transient render
	 * target).
	 *
	 * @param image The image to use.
	 */
	record FromImage(@NonNull Image2D image) implements TextureSource {
	}

	/**
	 * A texture sourced from the "read" buffer of a persistent, engine-managed swapchain.
	 * This implicitly provides the result of the previous frame's render-to-swapchain pass.
	 *
	 * @param swapchain The logical swapchain to read from.
	 */
	record FromSwapchain(@NonNull FrameSwapchain swapchain) implements TextureSource {
	}
}