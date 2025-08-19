package de.markusfisch.android.shadereditor.platform.render.gl.managers;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.scene.Image2D;

public class GlesFramebufferManager implements AutoCloseable {
	private final Map<Image2D.RenderTarget, Integer> fboCache = new HashMap<>();
	private final GlesTextureManager textureManager;

	public GlesFramebufferManager(@NonNull GlesTextureManager textureManager) {
		this.textureManager = textureManager;
	}

	/**
	 * Gets the GL handle for a framebuffer that renders to the given image.
	 * The default framebuffer (0) is not managed by this class.
	 *
	 * @param image The render target image.
	 * @return The OpenGL FBO handle.
	 */
	public int getFramebufferHandle(@NonNull Image2D.RenderTarget image) {
		return fboCache.computeIfAbsent(image, this::createFbo);
	}

	@Override
	public void close() {
		// Delete all FBOs
		if (!fboCache.isEmpty()) {
			var fbos = fboCache.values().stream().mapToInt(Integer::intValue).toArray();
			GLES30.glDeleteFramebuffers(fbos.length, fbos, 0);
			fboCache.clear();
		}
	}

	private int createFbo(@NonNull Image2D.RenderTarget image) {
		int[] fboHandle = new int[1];
		GLES20.glGenFramebuffers(1, fboHandle, 0);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle[0]);

		// Get the texture handle for the render target image
		int tex = textureManager.getTextureHandle(image);

		// Attach the texture as the first and only color attachment
		int attachmentPoint = GLES20.GL_COLOR_ATTACHMENT0;
		GLES20.glFramebufferTexture2D(
				GLES20.GL_FRAMEBUFFER,
				attachmentPoint,
				GLES20.GL_TEXTURE_2D,
				tex,
				0
		);

		// Specify that we are drawing to this single attachment
		var drawBuffers = new int[]{attachmentPoint};
		GLES30.glDrawBuffers(1, drawBuffers, 0);

		if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) !=
				GLES20.GL_FRAMEBUFFER_COMPLETE) {
			Log.e("GlesFramebufferManager",
					"Framebuffer is not complete for image: " + image.name());
			// Clean up the failed FBO
			GLES20.glDeleteFramebuffers(1, fboHandle, 0);
			throw new RuntimeException("Framebuffer is not complete.");
		}

		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
		return fboHandle[0];
	}
}