package de.markusfisch.android.shadereditor.renderengine.gl;

import android.opengl.GLES20;

/**
 * A wrapper for an OpenGL Framebuffer Object (FBO) with a single
 * color texture attachment.
 */
public class Framebuffer {
	private final int[] framebufferId = new int[1];
	private final int[] colorTextureId = new int[1];
	private final int width;
	private final int height;

	public Framebuffer(int width, int height) {
		this.width = width;
		this.height = height;

		// Create framebuffer
		GLES20.glGenFramebuffers(1, framebufferId, 0);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId[0]);

		// Create color texture attachment
		GLES20.glGenTextures(1, colorTextureId, 0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTextureId[0]);
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
				width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
				GLES20.GL_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
				GLES20.GL_CLAMP_TO_EDGE);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
				GLES20.GL_CLAMP_TO_EDGE);

		// Attach texture to framebuffer
		GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
				GLES20.GL_TEXTURE_2D, colorTextureId[0], 0);

		// Unbind to be safe
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
	}

	public static void unbind(int screenWidth, int screenHeight) {
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
		GLES20.glViewport(0, 0, screenWidth, screenHeight);
	}

	public void bind() {
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId[0]);
		GLES20.glViewport(0, 0, width, height);
	}

	public int getColorTextureId() {
		return colorTextureId[0];
	}

	public void release() {
		GLES20.glDeleteFramebuffers(1, framebufferId, 0);
		GLES20.glDeleteTextures(1, colorTextureId, 0);
	}
}