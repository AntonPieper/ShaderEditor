package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.GpuObject;

/**
 * A declarative representation of a rendering destination.
 * This tells the engine *where* a pass should draw, without exposing
 * low-level concepts like framebuffer objects to the plugin.
 */
public sealed interface RenderTarget extends GpuObject {
	/**
	 * Represents the default system-provided framebuffer (i.e., the screen).
	 */
	record ToScreen() implements RenderTarget {
	}

	/**
	 * Represents a transient, offscreen texture.
	 *
	 * @param image The renderable image to target.
	 */
	record ToImage(@NonNull Image2D.RenderTarget image) implements RenderTarget {
	}

	/**
	 * Represents the "write" buffer of a persistent, engine-managed swapchain.
	 *
	 * @param swapchain The logical swapchain to target.
	 */
	record ToSwapchain(@NonNull FrameSwapchain swapchain) implements RenderTarget {
	}
}