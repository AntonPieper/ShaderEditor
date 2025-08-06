package de.markusfisch.android.shadereditor.engine.model;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Framebuffer {
	private final int fbo;
	private final int width;
	private final int height;
	private final List<Texture> colorAttachments;
	// Add depth/stencil attachments here if needed

	public Framebuffer(int width, int height, int colorAttachmentCount) {
		// throw if not GL Thread
		this.width = width;
		this.height = height;
		this.colorAttachments = new ArrayList<>();

		int[] fboHandle = new int[1];
		GLES32.glGenFramebuffers(1, fboHandle, 0);
		this.fbo = fboHandle[0];
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, this.fbo);

		int[] drawBuffers = new int[colorAttachmentCount];

		for (int i = 0; i < colorAttachmentCount; i++) {
			int[] textureHandle = new int[1];
			GLES32.glGenTextures(1, textureHandle, 0);
			GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureHandle[0]);
			GLES32.glTexImage2D(GLES32.GL_TEXTURE_2D, 0, GLES32.GL_RGBA, width, height, 0,
					GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, null);
			GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER,
					GLES32.GL_LINEAR);
			GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER,
					GLES32.GL_LINEAR);

			int attachmentPoint = GLES32.GL_COLOR_ATTACHMENT0 + i;
			GLES32.glFramebufferTexture2D(GLES32.GL_FRAMEBUFFER, attachmentPoint,
					GLES32.GL_TEXTURE_2D, textureHandle[0], 0);

			colorAttachments.add(new Texture(textureHandle[0], width, height, GLES32.GL_RGBA));
			drawBuffers[i] = attachmentPoint;
		}

		GLES32.glDrawBuffers(drawBuffers.length, drawBuffers, 0);

		if (GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER) != GLES32.GL_FRAMEBUFFER_COMPLETE) {
			throw new RuntimeException("Framebuffer is not complete.");
		}

		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0);
	}

	// Special constructor for the default (on-screen) framebuffer
	private Framebuffer() {
		this.fbo = 0; // 0 is the default framebuffer in OpenGL
		this.width = 0; // Determined by surface
		this.height = 0; // Determined by surface
		this.colorAttachments = Collections.emptyList();
	}

	@NonNull
	@Contract(" -> new")
	public static Framebuffer defaultFramebuffer() {
		return new Framebuffer();
	}

	public int getFbo() {
		return fbo;
	}

	public Texture getTexture(int attachmentIndex) {
		if (attachmentIndex >= colorAttachments.size()) {
			throw new IndexOutOfBoundsException("Invalid color attachment index.");
		}
		return colorAttachments.get(attachmentIndex);
	}
}