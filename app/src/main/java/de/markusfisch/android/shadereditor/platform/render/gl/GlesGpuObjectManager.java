package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;
import de.markusfisch.android.shadereditor.engine.graphics.TextureMagFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureMinFilter;
import de.markusfisch.android.shadereditor.engine.graphics.TextureWrap;
import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Image2D;

public class GlesGpuObjectManager {
	private final Map<Geometry, Integer> vaoCache = new HashMap<>();
	private final Map<Framebuffer, Integer> fboCache = new HashMap<>();
	private final Map<Image2D, Integer> textureCache = new HashMap<>();

	public int getGeometryHandle(@NonNull Geometry geometry) {
		return vaoCache.computeIfAbsent(geometry, this::createVao);
	}

	public int getTextureHandle(@NonNull Image2D img) {
		return textureCache.computeIfAbsent(img,
				this::createTextureFor);
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
		GLES20.glGenFramebuffers(1, fboHandle, 0);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle[0]);

		int i = 0;
		int[] drawBuffers = new int[framebuffer.colorAttachments().size()];
		for (var ca : framebuffer.colorAttachments()) {
			var rt = ca.image(); // compile-time: must be RenderTarget
			int tex = getTextureHandle(rt); // same texture used later for sampling
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

	private int createTextureFor(@NonNull Image2D img) {
		int[] id = new int[1];
		GLES30.glGenTextures(1, id, 0);
		if (id[0] == 0) throw new RuntimeException("Error creating texture.");

		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);

		final int internalFormat;
		final int width;
		final int height;
		final TextureParameters p;

		switch (img) {
			case Image2D.FromAsset fa -> {
				internalFormat = fa.internalFormat();
				width = fa.asset().width();
				height = fa.asset().height();
				p = fa.sampling();
				GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat,
						width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
						fa.asset().pixels());
				if (requiresMips(p.minFilter()) || p.mipmaps()) {
					GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
				}
			}
			case Image2D.RenderTarget rt -> {
				internalFormat = rt.internalFormat();
				width = rt.width();
				height = rt.height();
				p = rt.sampling();
				GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat,
						width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
			}
		}

		// Wrap
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, toGL(p.wrapS()));
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, toGL(p.wrapT()));

		// Filters
		GLES30.glTexParameteri(
				GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, toGL(p.minFilter()));
		GLES30.glTexParameteri(
				GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, toGL(p.magFilter()));

		// Anisotropy (extension)
		if (!Float.isNaN(p.anisotropy()) && p.anisotropy() > 1f) {
			setAnisotropy(p.anisotropy());
		}

		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
		GlesUtil.checkErrors("createTextureFor(params)");
		return id[0];
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