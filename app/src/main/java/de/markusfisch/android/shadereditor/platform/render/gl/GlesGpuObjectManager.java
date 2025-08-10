package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;

public class GlesGpuObjectManager {
	private final Map<Geometry, Integer> vaoCache = new HashMap<>();
	private final Map<Framebuffer, Integer> fboCache = new HashMap<>();

	public int getGeometryHandle(@NonNull Geometry geometry) {
		return vaoCache.computeIfAbsent(geometry, this::createVao);
	}

	public int getFramebufferHandle(@NonNull Framebuffer framebuffer) {
		if (framebuffer.isDefault()) {
			return 0; // The default FBO in OpenGL is 0
		}
		return fboCache.computeIfAbsent(framebuffer, this::createFbo);
	}

	public void destroy() {
		// Delete all VAOs
		for (int vao : vaoCache.values()) {
			GLES32.glDeleteVertexArrays(1, new int[]{vao}, 0);
		}
		vaoCache.clear();

		// Delete all FBOs
		for (int fbo : fboCache.values()) {
			GLES32.glDeleteFramebuffers(1, new int[]{fbo}, 0);
		}
		fboCache.clear();
	}

	private int createVao(@NonNull Geometry geometry) {
		FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(geometry.vertices().length * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		vertexBuffer.put(geometry.vertices()).position(0);

		final int[] vbo = new int[1];
		GLES32.glGenBuffers(1, vbo, 0);

		final int[] vaoHandle = new int[1];
		GLES32.glGenVertexArrays(1, vaoHandle, 0);

		GLES32.glBindVertexArray(vaoHandle[0]);
		GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vbo[0]);
		GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, geometry.vertices().length * 4, vertexBuffer,
				GLES32.GL_STATIC_DRAW);

		// Position attribute (x, y)
		GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 4 * 4, 0);
		GLES32.glEnableVertexAttribArray(0);

		// UV attribute (u, v)
		GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 4 * 4, 2 * 4);
		GLES32.glEnableVertexAttribArray(1);

		GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0);
		GLES32.glBindVertexArray(0);

		return vaoHandle[0];
	}

	private int createFbo(@NonNull Framebuffer framebuffer) {
		int[] fboHandle = new int[1];
		GLES32.glGenFramebuffers(1, fboHandle, 0);
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboHandle[0]);

		int colorAttachmentCount = framebuffer.colorAttachments().size();
		int[] drawBuffers = new int[colorAttachmentCount];

		for (int i = 0; i < colorAttachmentCount; i++) {
			var attachment = framebuffer.colorAttachments().get(i);
			int[] textureHandle = new int[1];
			GLES32.glGenTextures(1, textureHandle, 0);
			GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureHandle[0]);
			GLES32.glTexImage2D(GLES32.GL_TEXTURE_2D, 0, attachment.internalFormat(),
					attachment.width(), attachment.height(), 0,
					GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, null);
			GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER,
					GLES32.GL_LINEAR);
			GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER,
					GLES32.GL_LINEAR);

			int attachmentPoint = GLES32.GL_COLOR_ATTACHMENT0 + i;
			GLES32.glFramebufferTexture2D(GLES32.GL_FRAMEBUFFER, attachmentPoint,
					GLES32.GL_TEXTURE_2D, textureHandle[0], 0);
			drawBuffers[i] = attachmentPoint;
		}

		GLES32.glDrawBuffers(drawBuffers.length, drawBuffers, 0);

		if (GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER) != GLES32.GL_FRAMEBUFFER_COMPLETE) {
			throw new RuntimeException("Framebuffer is not complete.");
		}

		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0);
		return fboHandle[0];
	}
}