package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.graphics.TextureMagFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureMinFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureWrap;
import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;

public class GlesGpuObjectManager {
	private final Map<Geometry, Integer> vaoCache = new HashMap<>();
	private final Map<Framebuffer, Integer> fboCache = new HashMap<>();
	private final Map<Uniform.Sampler, Integer> textureCache = new HashMap<>();

	public int getGeometryHandle(@NonNull Geometry geometry) {
		return vaoCache.computeIfAbsent(geometry, this::createVao);
	}

	public int getTextureHandle(@NonNull Uniform.Sampler sampler) {
		return textureCache.computeIfAbsent(sampler,
				this::createTexture);
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
			GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
		}
		vaoCache.clear();

		// Delete all FBOs
		for (int fbo : fboCache.values()) {
			GLES30.glDeleteFramebuffers(1, new int[]{fbo}, 0);
		}
		fboCache.clear();

		// Delete all textures
		int[] textureHandles = textureCache.values().stream().mapToInt(i -> i).toArray();
		if (textureHandles.length > 0) {
			GLES30.glDeleteTextures(textureHandles.length, textureHandles, 0);
		}
		textureCache.clear();
	}

	private int createVao(@NonNull Geometry geometry) {
		FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(geometry.vertices().length * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		vertexBuffer.put(geometry.vertices()).position(0);

		final int[] vbo = new int[1];
		GLES30.glGenBuffers(1, vbo, 0);

		final int[] vaoHandle = new int[1];
		GLES30.glGenVertexArrays(1, vaoHandle, 0);

		GLES30.glBindVertexArray(vaoHandle[0]);
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0]);
		GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, geometry.vertices().length * 4, vertexBuffer,
				GLES30.GL_STATIC_DRAW);

		// Position attribute (x, y)
		GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0);
		GLES30.glEnableVertexAttribArray(0);

		// UV attribute (u, v)
		GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4);
		GLES30.glEnableVertexAttribArray(1);

		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
		GLES30.glBindVertexArray(0);

		return vaoHandle[0];
	}

	private int createFbo(@NonNull Framebuffer framebuffer) {
		int[] fboHandle = new int[1];
		GLES30.glGenFramebuffers(1, fboHandle, 0);
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboHandle[0]);

		int colorAttachmentCount = framebuffer.colorAttachments().size();
		int[] drawBuffers = new int[colorAttachmentCount];

		for (int i = 0; i < colorAttachmentCount; i++) {
			var attachment = framebuffer.colorAttachments().get(i);
			int[] textureHandle = new int[1];
			GLES30.glGenTextures(1, textureHandle, 0);
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle[0]);
			GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, attachment.internalFormat(),
					attachment.width(), attachment.height(), 0,
					GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
					GLES30.GL_LINEAR);
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER,
					GLES30.GL_LINEAR);

			int attachmentPoint = GLES30.GL_COLOR_ATTACHMENT0 + i;
			GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, attachmentPoint,
					GLES30.GL_TEXTURE_2D, textureHandle[0], 0);
			drawBuffers[i] = attachmentPoint;
		}

		GLES30.glDrawBuffers(drawBuffers.length, drawBuffers, 0);

		if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
			throw new RuntimeException("Framebuffer is not complete.");
		}

		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
		return fboHandle[0];
	}

	private int createTexture(@NonNull Uniform.Sampler sampler) {
		var tex = sampler.texture();
		var p = sampler.params();
		int[] handle = new int[1];
		GLES30.glGenTextures(1, handle, 0);
		if (handle[0] == 0) throw new RuntimeException("Error creating texture.");

		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle[0]);

		// Wrap
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, toGL(p.wrapS()));
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, toGL(p.wrapT()));

		// Filters
		GLES30.glTexParameteri(
				GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, toGL(p.minFilter()));
		GLES30.glTexParameteri(
				GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, toGL(p.magFilter()));

		// Internal format (best effort sRGB if available; otherwise fallback)
		int internalFormat = (p.sRGB() && hasSRGB()) ? GLES30.GL_SRGB8_ALPHA8 : tex.format();

		GLES30.glTexImage2D(
				GLES30.GL_TEXTURE_2D, 0,
				internalFormat,
				tex.width(), tex.height(), 0,
				GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, tex.pixels());

		// Mipmaps if requested OR if a mipmap min filter is selected
		boolean needsMips = requiresMips(p.minFilter()) || p.mipmaps();
		if (needsMips) {
			GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
		}

		// Anisotropy (extension)
		if (!Float.isNaN(p.anisotropy()) && p.anisotropy() > 1f) {
			setAnisotropy(p.anisotropy());
		}

		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
		GlesUtil.checkErrors("createTexture(params)");
		return handle[0];
	}

	@Contract(pure = true)
	private static boolean requiresMips(@NonNull TextureMinFilter f) {
		return switch (f) {
			case NEAREST_MIPMAP_NEAREST,
				 LINEAR_MIPMAP_NEAREST,
				 NEAREST_MIPMAP_LINEAR,
				 LINEAR_MIPMAP_LINEAR -> true;
			default -> false;
		};
	}

	private static boolean hasSRGB() {
		String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
		// ES3 core has sRGB; extension allows on ES2 devices
		return ext != null && (ext.contains("GL_EXT_sRGB") || ext.contains(
				"GL_EXT_sRGB_write_control"));
	}

	private static void setAnisotropy(float level) {
		String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
		if (ext != null && ext.contains("GL_EXT_texture_filter_anisotropic")) {
			// 0x84FE = GL_TEXTURE_MAX_ANISOTROPY_EXT, 0x84FF = GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT
			int GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE;
			int GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF;
			float[] max = new float[1];
			GLES30.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, max, 0);
			float clamped = Math.max(1f, Math.min(level, max[0]));
			GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, clamped);
		}
	}

	@Contract(pure = true)
	private static int toGL(@NonNull TextureWrap w) {
		return switch (w) {
			case REPEAT -> GLES30.GL_REPEAT;
			case MIRRORED_REPEAT -> GLES30.GL_MIRRORED_REPEAT;
			case CLAMP_TO_EDGE -> GLES30.GL_CLAMP_TO_EDGE;
		};
	}

	private static int toGL(@NonNull TextureMagFilter f) {
		return (f == TextureMagFilter.NEAREST)
				? GLES30.GL_NEAREST
				: GLES30.GL_LINEAR;
	}

	@Contract(pure = true)
	private static int toGL(@NonNull TextureMinFilter f) {
		return switch (f) {
			case NEAREST -> GLES30.GL_NEAREST;
			case LINEAR -> GLES30.GL_LINEAR;
			case NEAREST_MIPMAP_NEAREST -> GLES30.GL_NEAREST_MIPMAP_NEAREST;
			case LINEAR_MIPMAP_NEAREST -> GLES30.GL_LINEAR_MIPMAP_NEAREST;
			case NEAREST_MIPMAP_LINEAR -> GLES30.GL_NEAREST_MIPMAP_LINEAR;
			case LINEAR_MIPMAP_LINEAR -> GLES30.GL_LINEAR_MIPMAP_LINEAR;
		};
	}

}