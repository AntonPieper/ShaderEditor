package de.markusfisch.android.shadereditor.platform.render.gl.managers;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;
import de.markusfisch.android.shadereditor.engine.graphics.TextureInternalFormat;
import de.markusfisch.android.shadereditor.engine.scene.FrameSwapchain;
import de.markusfisch.android.shadereditor.engine.scene.Image2D;

/**
 * Manages the lifecycle of OpenGL resources for engine {@link FrameSwapchain} objects.
 * For each logical swapchain, it creates and maintains two textures and two FBOs
 * to facilitate ping-pong rendering techniques.
 */
public class GlesSwapchainManager implements AutoCloseable {
	private static final String TAG = "GlesSwapchainManager";
	private final Map<FrameSwapchain, SwapchainResources> cache = new HashMap<>();
	private final GlesTextureManager textureManager;
	private final GlesFramebufferManager framebufferManager;

	public GlesSwapchainManager(
			@NonNull GlesTextureManager textureManager,
			@NonNull GlesFramebufferManager framebufferManager) {
		this.textureManager = textureManager;
		this.framebufferManager = framebufferManager;
	}

	public int getReadTextureHandle(@NonNull FrameSwapchain swapchain,
			@NonNull Viewport viewport) {
		SwapchainResources resources = getOrCreate(swapchain, viewport);
		return textureManager.getTextureHandle(resources.read);
	}

	public int getWriteFramebufferHandle(@NonNull FrameSwapchain swapchain,
			@NonNull Viewport viewport) {
		SwapchainResources resources = getOrCreate(swapchain, viewport);
		return framebufferManager.getFramebufferHandle(resources.write);
	}

	/**
	 * Retrieves the texture handle of the buffer that was written to in the current frame.
	 * This is primarily used for blitting the final result to the screen.
	 */
	public int getWriteTextureHandle(@NonNull FrameSwapchain swapchain,
			@NonNull Viewport viewport) {
		SwapchainResources resources = getOrCreate(swapchain, viewport);
		return textureManager.getTextureHandle(resources.write);
	}


	public void swapAll() {
		for (SwapchainResources resources : cache.values()) {
			resources.swap();
		}
	}

	@Override
	public void close() {
		// The underlying textures and FBOs are owned by their respective
		// managers, so we just need to clear our cache.
		cache.clear();
		Log.d(TAG, "Swapchain manager closed.");
	}

	@NonNull
	private SwapchainResources getOrCreate(@NonNull FrameSwapchain swapchain,
			@NonNull Viewport viewport) {
		SwapchainResources resources = cache.get(swapchain);
		if (resources == null ||
				resources.read.width() != viewport.width() ||
				resources.read.height() != viewport.height()) {
			Log.d(TAG, "Creating or resizing resources for swapchain '" + swapchain.name() +
					"' to " + viewport.width() + "x" + viewport.height());
			resources = new SwapchainResources(swapchain, viewport);
			cache.put(swapchain, resources);
		}
		return resources;
	}

	// Inner class to hold the pair of resources for a single swapchain.
	private static class SwapchainResources {
		private Image2D.RenderTarget read;
		private Image2D.RenderTarget write;

		SwapchainResources(@NonNull FrameSwapchain swapchain, @NonNull Viewport viewport) {
			// Create two distinct Image2D.RenderTarget objects. The unique name ensures
			// they map to different GPU resources in the texture/FBO managers.
			var imageA = new Image2D.RenderTarget(
					swapchain.name() + "_ping",
					viewport.width(),
					viewport.height(),
					// TODO: These could be configurable from the FrameSwapchain record
					// For now, hardcode to reasonable defaults.
					TextureInternalFormat.RGBA8,
					TextureParameters.DEFAULT);

			var imageB = new Image2D.RenderTarget(
					swapchain.name() + "_pong",
					viewport.width(),
					viewport.height(),
					TextureInternalFormat.RGBA8,
					TextureParameters.DEFAULT);

			// Initial state: read from A, write to B
			this.read = imageA;
			this.write = imageB;
		}

		void swap() {
			Image2D.RenderTarget temp = read;
			read = write;
			write = temp;
		}
	}
}