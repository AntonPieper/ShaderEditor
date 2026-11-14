package de.markusfisch.android.shadereditor.opengl;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.opengles.GL11ExtensionPack;

import de.markusfisch.android.shadereditor.graphics.BitmapEditor;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.SourceMap;

// Every GL call the engine makes, so that tests can drive the engine
// with a device of their own.
class GlDevice {
	private static final int[] CUBE_MAP_TARGETS = {
			GL11ExtensionPack.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
			GL11ExtensionPack.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z,
			GL11ExtensionPack.GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
			GL11ExtensionPack.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
			GL11ExtensionPack.GL_TEXTURE_CUBE_MAP_POSITIVE_Z,
			GL11ExtensionPack.GL_TEXTURE_CUBE_MAP_POSITIVE_X};

	private int maxTextureUnits;
	private int maxTextureSize;
	private int maxCubemapSize;
	@Nullable
	private GlTexture2D emptyTexture2D;
	@Nullable
	private GlCubemap emptyCubemap;
	@Nullable
	private GlExternalTexture emptyExternalTexture;

	private record ShaderCompileResult(
			@Nullable GlShader shader,
			@NonNull List<ProjectDiagnostic> infoLog) {
	}

	// Call once for every new context, before anything else.
	void createContextResources() {
		int[] units = new int[1];
		GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, units, 0);
		maxTextureUnits = units[0];
		GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, units, 0);
		maxTextureSize = units[0];
		GLES20.glGetIntegerv(GLES20.GL_MAX_CUBE_MAP_TEXTURE_SIZE, units, 0);
		maxCubemapSize = units[0];

		ByteBuffer black = ByteBuffer.allocateDirect(4);
		black.put(3, (byte) 0xff);
		emptyTexture2D = createTexture2D();
		bindTexture(0, emptyTexture2D);
		fillEmpty(GLES20.GL_TEXTURE_2D, black);
		setNearest(GLES20.GL_TEXTURE_2D);
		emptyCubemap = createCubemap();
		bindTexture(0, emptyCubemap);
		for (int target : CUBE_MAP_TARGETS) {
			fillEmpty(target, black);
		}
		setNearest(GLES20.GL_TEXTURE_CUBE_MAP);
		// Without an image, an external texture samples as opaque black.
		emptyExternalTexture = createExternalTexture();
	}

	// Gives every element of every active sampler an opaque black texture,
	// so a unit nothing else binds never samples a leftover texture.
	void bindEmptyTextures(@NonNull List<GlUniform> samplers) {
		for (var sampler : samplers) {
			GlTexture texture = switch (sampler.textureTarget()) {
				case GLES20.GL_TEXTURE_2D -> emptyTexture2D;
				case GLES20.GL_TEXTURE_CUBE_MAP -> emptyCubemap;
				case GLES11Ext.GL_TEXTURE_EXTERNAL_OES -> emptyExternalTexture;
				default -> null;
			};
			for (int i = 0; i < sampler.size(); ++i) {
				bindTexture(sampler.unit() + i, texture);
			}
		}
	}

	int getMaxTextureSize() {
		return maxTextureSize;
	}

	int getMaxCubemapSize() {
		return maxCubemapSize;
	}

	void disable(int cap) {
		GLES20.glDisable(cap);
	}

	void setClearColor(float red, float green, float blue, float alpha) {
		GLES20.glClearColor(red, green, blue, alpha);
	}

	void clear(int mask) {
		GLES20.glClear(mask);
	}

	void setViewport(int x, int y, int width, int height) {
		GLES20.glViewport(x, y, width, height);
	}

	@NonNull
	GlTexture2D createTexture2D() {
		int[] textures = new int[1];
		GLES20.glGenTextures(1, textures, 0);
		return new GlTexture2D(textures[0]);
	}

	@NonNull
	GlCubemap createCubemap() {
		int[] textures = new int[1];
		GLES20.glGenTextures(1, textures, 0);
		return new GlCubemap(textures[0]);
	}

	@NonNull
	GlExternalTexture createExternalTexture() {
		int[] textures = new int[1];
		GLES20.glGenTextures(1, textures, 0);
		return new GlExternalTexture(textures[0]);
	}

	void deleteTexture(@Nullable GlTexture texture) {
		if (texture == null || !texture.isValid()) {
			return;
		}
		GLES20.glDeleteTextures(1, new int[]{texture.getId()}, 0);
		texture.invalidate();
	}

	void bindTexture(int unit, @Nullable GlTexture texture) {
		if (texture == null || !texture.isValid() || unit < 0 || unit >= maxTextureUnits) {
			return;
		}
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
		GLES20.glBindTexture(texture.getTarget(), texture.getId());
	}

	void applySampling(@NonNull GlTexture texture,
			@NonNull Sampling sampling) {
		applySampling(0, texture, sampling);
	}

	// Leaves the texture bound to unit.
	void applySampling(int unit, @NonNull GlTexture texture,
			@NonNull Sampling sampling) {
		bindTexture(unit, texture);
		int target = texture.getTarget();
		int minFilter = GlSampling.minFilter(sampling);
		int wrapS = GlSampling.wrap(sampling.wrapS());
		int wrapT = GlSampling.wrap(sampling.wrapT());
		if (target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
			minFilter = switch (minFilter) {
				case GLES20.GL_NEAREST, GLES20.GL_LINEAR -> minFilter;
				default -> GLES20.GL_LINEAR;
			};
			wrapS = GLES20.GL_CLAMP_TO_EDGE;
			wrapT = GLES20.GL_CLAMP_TO_EDGE;
		}
		GLES20.glTexParameteri(
				target,
				GLES20.GL_TEXTURE_MIN_FILTER,
				minFilter);
		GLES20.glTexParameteri(
				target,
				GLES20.GL_TEXTURE_MAG_FILTER,
				GlSampling.magFilter(sampling));
		GLES20.glTexParameteri(
				target,
				GLES20.GL_TEXTURE_WRAP_S,
				wrapS);
		GLES20.glTexParameteri(
				target,
				GLES20.GL_TEXTURE_WRAP_T,
				wrapT);
	}

	void allocateTexture2D(@NonNull GlTexture2D texture, int width, int height) {
		bindTexture(0, texture);
		GLES20.glTexImage2D(
				GLES20.GL_TEXTURE_2D,
				0,
				GLES20.GL_RGBA,
				width,
				height,
				0,
				GLES20.GL_RGBA,
				GLES20.GL_UNSIGNED_BYTE,
				null);
		texture.setSize(width, height);
	}

	@Nullable
	String uploadTexture2D(@NonNull GlTexture2D texture,
			@NonNull Bitmap bitmap,
			boolean flipY) {
		bindTexture(0, texture);
		String message = uploadBitmapInternal(
				GLES20.GL_TEXTURE_2D,
				bitmap,
				flipY);
		if (message == null) {
			texture.setSize(bitmap.getWidth(), bitmap.getHeight());
		}
		return message;
	}

	// Takes RGBA8 rows from the bottom.
	@Nullable
	String uploadTexture2D(@NonNull GlTexture2D texture,
			int width,
			int height,
			@NonNull Buffer rgba) {
		bindTexture(0, texture);
		String message = uploadRgba(GLES20.GL_TEXTURE_2D, width, height, rgba);
		if (message == null) {
			texture.setSize(width, height);
		}
		return message;
	}

	// Takes the faces in CUBE_MAP_TARGETS order.
	@Nullable
	String uploadCubemap(@NonNull GlCubemap cubemap, @NonNull Bitmap[] faces) {
		bindTexture(0, cubemap);
		for (int i = 0; i < CUBE_MAP_TARGETS.length; ++i) {
			String message = uploadBitmapInternal(
					CUBE_MAP_TARGETS[i],
					faces[i],
					false);
			if (message != null) {
				return message;
			}
		}
		cubemap.setSideLength(faces[0].getWidth());
		return null;
	}

	// Takes face 0 to 5, +X, -X, +Y, -Y, +Z, -Z, as RGBA8 rows from the
	// top.
	@Nullable
	String uploadCubemapFace(@NonNull GlCubemap cubemap,
			int face,
			int side,
			@NonNull Buffer rgba) {
		bindTexture(0, cubemap);
		String message = uploadRgba(GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face,
				side, side, rgba);
		if (message == null) {
			cubemap.setSideLength(side);
		}
		return message;
	}

	// Returns why it could not, or null.
	@Nullable
	String generateMipmap(@NonNull GlTexture texture) {
		bindTexture(0, texture);
		if (texture.getTarget() == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
			return null;
		}
		clearGlErrors();
		GLES20.glGenerateMipmap(texture.getTarget());
		return takeError("glGenerateMipmap");
	}

	// Before GL calls whose own errors takeError() reports.
	void clearErrors() {
		clearGlErrors();
	}

	// Why the GL calls since clearErrors() failed, or null.
	@Nullable
	String takeError(@NonNull String call) {
		int error = getLastGlError();
		return error != GLES20.GL_NO_ERROR
				? call + " failed with GL error 0x" + Integer.toHexString(error)
				: null;
	}

	// Covers the viewport with the program in use.
	void draw(@NonNull FullScreenQuad quad) {
		GLES20.glEnableVertexAttribArray(FullScreenQuad.POSITION_LOCATION);
		GLES20.glVertexAttribPointer(
				FullScreenQuad.POSITION_LOCATION,
				2,
				GLES20.GL_BYTE,
				false,
				0,
				quad.vertices);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
	}

	// A sampler object of a GLES3 context that samples as asked.
	int createSampler(@NonNull Sampling sampling) {
		int[] sampler = new int[1];
		GLES30.glGenSamplers(1, sampler, 0);
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_MIN_FILTER,
				GlSampling.minFilter(sampling));
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_MAG_FILTER,
				GlSampling.magFilter(sampling));
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_WRAP_S,
				GlSampling.wrap(sampling.wrapS()));
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_WRAP_T,
				GlSampling.wrap(sampling.wrapT()));
		return sampler[0];
	}

	// 0 lets the unit sample by texture parameters again.
	void bindSampler(int unit, int sampler) {
		GLES30.glBindSampler(unit, sampler);
	}

	void deleteSamplers(@NonNull int[] samplers) {
		GLES30.glDeleteSamplers(samplers.length, samplers, 0);
	}

	@NonNull
	GlFramebuffer createFramebuffer() {
		int[] framebuffers = new int[1];
		GLES20.glGenFramebuffers(1, framebuffers, 0);
		return new GlFramebuffer(framebuffers[0]);
	}

	void deleteFramebuffer(@Nullable GlFramebuffer framebuffer) {
		if (framebuffer == null || !framebuffer.isValid()) {
			return;
		}
		GLES20.glDeleteFramebuffers(1, new int[]{framebuffer.getId()}, 0);
		framebuffer.invalidate();
	}

	void bindFramebuffer(@Nullable GlFramebuffer framebuffer) {
		int framebufferId = framebuffer != null && framebuffer.isValid()
				? framebuffer.getId()
				: 0;
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId);
	}

	void attachColor(@NonNull GlFramebuffer framebuffer,
			@NonNull GlTexture2D texture) {
		bindFramebuffer(framebuffer);
		GLES20.glFramebufferTexture2D(
				GLES20.GL_FRAMEBUFFER,
				GLES20.GL_COLOR_ATTACHMENT0,
				GLES20.GL_TEXTURE_2D,
				texture.getId(),
				0);
		framebuffer.setColorAttachment(texture);
	}

	int checkFramebufferStatus(@NonNull GlFramebuffer framebuffer) {
		bindFramebuffer(framebuffer);
		return GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
	}

	@NonNull
	GlProgramBuildResult createProgram(
			@NonNull String vertexSource,
			@NonNull String fragmentSource,
			@Nullable SourceMap fragmentSourceMap) {
		ShaderCompileResult vertexCompile = compileShader(
				GLES20.GL_VERTEX_SHADER,
				vertexSource,
				null);
		if (vertexCompile.shader == null) {
			return GlProgramBuildResult.failure(vertexCompile.infoLog);
		}

		ShaderCompileResult fragmentCompile = compileShader(
				GLES20.GL_FRAGMENT_SHADER,
				fragmentSource,
				fragmentSourceMap);
		if (fragmentCompile.shader == null) {
			deleteShader(vertexCompile.shader);
			return GlProgramBuildResult.failure(fragmentCompile.infoLog);
		}

		int programId = GLES20.glCreateProgram();
		if (programId == 0) {
			deleteShader(vertexCompile.shader);
			deleteShader(fragmentCompile.shader);
			return GlProgramBuildResult.failure(
					List.of(ProjectDiagnostic.error("Cannot create program")));
		}

		GLES20.glAttachShader(programId, vertexCompile.shader.getId());
		GLES20.glAttachShader(programId, fragmentCompile.shader.getId());
		GLES20.glBindAttribLocation(
				programId,
				FullScreenQuad.POSITION_LOCATION,
				FullScreenQuad.POSITION);
		GLES20.glLinkProgram(programId);

		deleteShader(vertexCompile.shader);
		deleteShader(fragmentCompile.shader);

		int[] linkStatus = new int[1];
		GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
		if (linkStatus[0] != GLES20.GL_TRUE) {
			List<ProjectDiagnostic> infoLog = DriverLog.parse(
					GLES20.glGetProgramInfoLog(programId),
					fragmentSourceMap);
			GLES20.glDeleteProgram(programId);
			return GlProgramBuildResult.failure(infoLog);
		}

		var program = new GlProgram(programId, queryActiveUniforms(programId));
		ProjectDiagnostic unitError = program.checkTextureUnits(maxTextureUnits);
		if (unitError != null) {
			GLES20.glDeleteProgram(programId);
			return GlProgramBuildResult.failure(List.of(unitError));
		}
		useProgram(program);
		for (GlUniform sampler : program.getSamplers()) {
			int[] units = sampler.units();
			// Drivers that emulate a samplerExternalOES as a sampler2D,
			// the emulator's among them, see its unit only through
			// glUniform1i.
			if (units.length == 1) {
				GLES20.glUniform1i(sampler.location(), units[0]);
			} else {
				GLES20.glUniform1iv(sampler.location(), units.length, units, 0);
			}
		}
		return GlProgramBuildResult.success(program);
	}

	@NonNull
	private static List<GlUniform> queryActiveUniforms(int programId) {
		int[] count = new int[1];
		GLES20.glGetProgramiv(programId, GLES20.GL_ACTIVE_UNIFORMS, count, 0);
		List<GlUniform> uniforms = new ArrayList<>();
		int[] size = new int[1];
		int[] type = new int[1];
		for (int i = 0; i < count[0]; ++i) {
			String name = GLES20.glGetActiveUniform(programId, i, size, 0, type, 0);
			if (name == null || name.isEmpty()) {
				continue;
			}
			uniforms.add(new GlUniform(name, type[0], size[0],
					GLES20.glGetUniformLocation(programId, name)));
		}
		return uniforms;
	}

	void deleteProgram(@Nullable GlProgram program) {
		if (program == null || !program.isValid()) {
			return;
		}
		GLES20.glDeleteProgram(program.getId());
		program.invalidate();
	}

	void useProgram(@Nullable GlProgram program) {
		int programId = program != null && program.isValid()
				? program.getId()
				: 0;
		GLES20.glUseProgram(programId);
	}

	int getUniformLocation(@NonNull GlProgram program, @NonNull String name) {
		GlUniform uniform = program.getUniforms().get(name);
		return uniform != null ? uniform.location() : -1;
	}

	void uniform1i(int location, int value) {
		if (location > -1) {
			GLES20.glUniform1i(location, value);
		}
	}

	void uniform1f(int location, float value) {
		if (location > -1) {
			GLES20.glUniform1f(location, value);
		}
	}

	void uniform2fv(int location, int count, @NonNull float[] values) {
		if (location > -1 && count > 0) {
			GLES20.glUniform2fv(location, count, values, 0);
		}
	}

	void uniform3fv(int location, int count, @NonNull float[] values) {
		if (location > -1 && count > 0) {
			GLES20.glUniform3fv(location, count, values, 0);
		}
	}

	void uniform4fv(int location, int count, @NonNull float[] values) {
		if (location > -1 && count > 0) {
			GLES20.glUniform4fv(location, count, values, 0);
		}
	}

	// GLES 2.0 only takes matrices column by column, without transposing.
	void uniformMatrix2fv(int location, @NonNull float[] values) {
		if (location > -1) {
			GLES20.glUniformMatrix2fv(location, 1, false, values, 0);
		}
	}

	void uniformMatrix3fv(int location, @NonNull float[] values) {
		if (location > -1) {
			GLES20.glUniformMatrix3fv(location, 1, false, values, 0);
		}
	}

	void readPixels(int x, int y, int width, int height, @NonNull Buffer buffer) {
		GLES20.glReadPixels(
				x,
				y,
				width,
				height,
				GLES20.GL_RGBA,
				GLES20.GL_UNSIGNED_BYTE,
				buffer);
	}

	@Nullable
	private ShaderCompileResult compileShader(int type,
			@NonNull String source,
			@Nullable SourceMap sourceMap) {
		int shaderId = GLES20.glCreateShader(type);
		if (shaderId == 0) {
			return new ShaderCompileResult(
					null,
					List.of(ProjectDiagnostic.error("Cannot create shader")));
		}

		GLES20.glShaderSource(shaderId, source);
		GLES20.glCompileShader(shaderId);

		int[] compiled = new int[1];
		GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compiled, 0);
		if (compiled[0] == 0) {
			List<ProjectDiagnostic> infoLog = DriverLog.parse(
					GLES20.glGetShaderInfoLog(shaderId),
					sourceMap);
			GLES20.glDeleteShader(shaderId);
			return new ShaderCompileResult(null, infoLog);
		}

		return new ShaderCompileResult(
				new GlShader(shaderId, type, source),
				java.util.Collections.emptyList());
	}

	private void deleteShader(@Nullable GlShader shader) {
		if (shader == null || !shader.isValid()) {
			return;
		}
		GLES20.glDeleteShader(shader.getId());
		shader.invalidate();
	}

	private static void fillEmpty(int target, @NonNull ByteBuffer pixel) {
		GLES20.glTexImage2D(
				target,
				0,
				GLES20.GL_RGBA,
				1,
				1,
				0,
				GLES20.GL_RGBA,
				GLES20.GL_UNSIGNED_BYTE,
				pixel);
	}

	private static void setNearest(int target) {
		GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
	}

	@Nullable
	private static String uploadBitmapInternal(int target,
			@NonNull Bitmap bitmap,
			boolean flipY) {
		return uploadRgba(target, bitmap.getWidth(), bitmap.getHeight(),
				BitmapEditor.createRgbaBuffer(bitmap, flipY));
	}

	@Nullable
	private static String uploadRgba(int target,
			int width,
			int height,
			@NonNull Buffer rgba) {
		clearGlErrors();
		GLES20.glTexImage2D(
				target,
				0,
				GLES20.GL_RGBA,
				width,
				height,
				0,
				GLES20.GL_RGBA,
				GLES20.GL_UNSIGNED_BYTE,
				rgba);
		int error = getLastGlError();
		if (error != GLES20.GL_NO_ERROR) {
			return "glTexImage2D failed with GL error 0x" + Integer.toHexString(error);
		}
		return null;
	}

	private static void clearGlErrors() {
		while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
			// Drain stale GL errors so uploads only report their own failures.
		}
	}

	private static int getLastGlError() {
		int lastError = GLES20.GL_NO_ERROR;
		int error;
		while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
			lastError = error;
		}
		return lastError;
	}
}
