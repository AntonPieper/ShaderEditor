package de.markusfisch.android.shadereditor.platform.render.gl.managers;

import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;

public class GlesFramebufferManager implements AutoCloseable {
	private final Map<Framebuffer, Integer> fboCache = new HashMap<>();
	private final GlesTextureManager textureManager;

	public GlesFramebufferManager(@NonNull GlesTextureManager textureManager) {
		this.textureManager = textureManager;
	}

	public int getFramebufferHandle(@NonNull Framebuffer framebuffer) {
		if (framebuffer.isDefault()) {
			return 0; // The default FBO in OpenGL is 0
		}
		return fboCache.computeIfAbsent(framebuffer, this::createFbo);
	}

	@Override
	public void close() {
		// Delete all FBOs
		var fbos = fboCache.values().stream().mapToInt(Integer::intValue).toArray();
		GLES30.glDeleteFramebuffers(fbos.length, fbos, 0);
		fboCache.clear();
	}

	private int createFbo(@NonNull Framebuffer framebuffer) {
		int[] fboHandle = new int[1];
		GLES20.glGenFramebuffers(1, fboHandle, 0);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle[0]);

		int i = 0;
		int[] drawBuffers = new int[framebuffer.colorAttachments().size()];
		for (var ca : framebuffer.colorAttachments()) {
			var rt = ca.image(); // compile-time: must be RenderTarget
			int tex = textureManager.getTextureHandle(rt); // same texture used later for sampling
			int att = GLES20.GL_COLOR_ATTACHMENT0 + i;
			GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, att, GLES20.GL_TEXTURE_2D, tex,
					0);
			drawBuffers[i++] = att;
		}
		GLES30.glDrawBuffers(drawBuffers.length, drawBuffers, 0);

		if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			throw new RuntimeException("Framebuffer is not complete.");
		}

		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
		return fboHandle[0];
	}
}