package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.Buffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.SourceMap;

// A GL device without GL for JVM tests. It hands out object names,
// links a program by taking every uniform its fragment shader declares
// as active, and records every draw: the texture drawn into, the program,
// the texture on every unit and the uniforms written. A test can make one
// call fail with failAt.
class FakeGlDevice extends GlDevice {
	// What one draw read and wrote. target is the texture drawn into, 0
	// for the surface.
	record Draw(int target, int program, Map<Integer, Integer> units,
			Map<String, float[]> uniforms) {
	}

	static final int EMPTY = -1;
	private static final Pattern UNIFORM = Pattern.compile(
			"\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)" +
					"\\s*(?:\\[\\s*(\\d+)\\s*\\])?\\s*;");
	private static final Pattern COMMENT = Pattern.compile(
			"//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);

	final List<Draw> draws = new ArrayList<>();
	// Calls by name, in order, for tests that count them.
	final List<String> calls = new ArrayList<>();
	final Map<Integer, GlProgram> programs = new HashMap<>();
	int maxTextureSize = 4096;
	int maxTextureUnits = 16;
	// The name of the call that fails next, or null.
	@Nullable
	String failAt;
	// Textures and framebuffers made and not deleted.
	final java.util.Set<Integer> live = new java.util.HashSet<>();
	// Textures that were allocated or attached.
	final List<Integer> touched = new ArrayList<>();
	private int failAfter;
	private final Map<Integer, Integer> units = new HashMap<>();
	private final Map<Integer, Map<String, float[]>> values = new HashMap<>();
	private int nextName = 1;
	private int program;
	@Nullable
	private GlFramebuffer framebuffer;

	@Override
	void createContextResources() {
		calls.add("createContextResources");
	}

	@Override
	void bindEmptyTextures(@NonNull List<GlUniform> samplers) {
		for (var sampler : samplers) {
			for (int i = 0; i < sampler.size(); ++i) {
				units.put(sampler.unit() + i, EMPTY);
			}
		}
	}

	@Override
	int getMaxTextureSize() {
		return maxTextureSize;
	}

	@Override
	int getMaxCubemapSize() {
		return maxTextureSize;
	}

	@Override
	void disable(int cap) {
	}

	@Override
	void setClearColor(float red, float green, float blue, float alpha) {
	}

	@Override
	void clear(int mask) {
		calls.add("clear " + target());
	}

	@Override
	void setViewport(int x, int y, int width, int height) {
	}

	@NonNull
	@Override
	GlTexture2D createTexture2D() {
		calls.add("createTexture2D");
		return new GlTexture2D(liveName("createTexture2D"));
	}

	@NonNull
	@Override
	GlCubemap createCubemap() {
		return new GlCubemap(name("createCubemap"));
	}

	@NonNull
	@Override
	GlExternalTexture createExternalTexture() {
		return new GlExternalTexture(name("createExternalTexture"));
	}

	@Override
	void deleteTexture(@Nullable GlTexture texture) {
		if (texture != null && texture.isValid()) {
			calls.add("deleteTexture");
			live.remove(texture.getId());
			texture.invalidate();
		}
	}

	@Override
	void bindTexture(int unit, @Nullable GlTexture texture) {
		if (texture != null && texture.isValid()) {
			units.put(unit, texture.getId());
		}
	}

	@Override
	void applySampling(int unit, @NonNull GlTexture texture,
			@NonNull Sampling sampling) {
		bindTexture(unit, texture);
	}

	@Override
	void allocateTexture2D(@NonNull GlTexture2D texture, int width, int height) {
		calls.add("allocateTexture2D");
		texture.setSize(width, height);
	}

	// Like the device: an object without a name is refused before any
	// call.
	@Nullable
	String allocate(@NonNull GlTexture2D texture, int width, int height) {
		if (!texture.isValid()) {
			return "no texture";
		}
		touched.add(texture.getId());
		allocateTexture2D(texture, width, height);
		return fails("allocateTexture2D") ? "allocation failed" : null;
	}

	@Nullable
	@Override
	String allocateTexture2D(@NonNull GlTexture2D texture, int width,
			int height, int internalFormat, int type) {
		return allocate(texture, width, height);
	}

	@Nullable
	@Override
	String uploadTexture2D(@NonNull GlTexture2D texture, int width, int height,
			@NonNull Buffer rgba) {
		texture.setSize(width, height);
		return fails("uploadTexture2D") ? "upload failed" : null;
	}

	@Nullable
	@Override
	String uploadCubemapFace(@NonNull GlCubemap cubemap, int face, int side,
			@NonNull Buffer rgba) {
		cubemap.setSideLength(side);
		return null;
	}

	@Nullable
	@Override
	String generateMipmap(@NonNull GlTexture texture) {
		return null;
	}

	@Override
	void clearErrors() {
	}

	@Nullable
	@Override
	String takeError(@NonNull String call) {
		return null;
	}

	@Override
	void draw(@NonNull FullScreenQuad quad) {
		var uniforms = new HashMap<String, float[]>();
		var written = values.get(program);
		if (written != null) {
			uniforms.putAll(written);
		}
		draws.add(new Draw(target(), program, new HashMap<>(units), uniforms));
	}

	@Override
	int createSampler(@NonNull Sampling sampling) {
		return name("createSampler");
	}

	@Override
	void bindSampler(int unit, int sampler) {
	}

	@Override
	void deleteSamplers(@NonNull int[] samplers) {
	}

	@NonNull
	@Override
	GlFramebuffer createFramebuffer() {
		calls.add("createFramebuffer");
		return new GlFramebuffer(liveName("createFramebuffer"));
	}

	@Override
	void deleteFramebuffer(@Nullable GlFramebuffer framebuffer) {
		if (framebuffer != null && framebuffer.isValid()) {
			calls.add("deleteFramebuffer");
			live.remove(framebuffer.getId());
			framebuffer.invalidate();
		}
	}

	@Override
	void bindFramebuffer(@Nullable GlFramebuffer framebuffer) {
		this.framebuffer = framebuffer != null && framebuffer.isValid()
				? framebuffer
				: null;
	}

	@Nullable
	@Override
	String attachColor(@NonNull GlFramebuffer framebuffer,
			@NonNull GlTexture2D texture) {
		if (!framebuffer.isValid() || !texture.isValid()) {
			return "no name";
		}
		touched.add(texture.getId());
		bindFramebuffer(framebuffer);
		framebuffer.setColorAttachment(texture);
		return fails("attachColor") ? "attach failed" : null;
	}

	// The window's framebuffer is complete, so a failed attachment would
	// pass this check if it did not look at what is bound.
	@Override
	int checkFramebufferStatus(@NonNull GlFramebuffer framebuffer) {
		if (!framebuffer.isValid()) {
			return 0;
		}
		bindFramebuffer(framebuffer);
		return fails("checkFramebufferStatus")
				? GLES20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
				: GLES20.GL_FRAMEBUFFER_COMPLETE;
	}

	@NonNull
	@Override
	GlProgramBuildResult createProgram(
			@NonNull String vertexSource,
			@NonNull String fragmentSource,
			@Nullable SourceMap fragmentSourceMap) {
		if (fails("createProgram")) {
			return GlProgramBuildResult.failure(List.of(
					ProjectDiagnostic.error("link failed")));
		}
		var uniforms = new ArrayList<GlUniform>();
		var matcher = UNIFORM.matcher(COMMENT.matcher(fragmentSource)
				.replaceAll(" "));
		int location = 0;
		while (matcher.find()) {
			int size = matcher.group(3) != null
					? Integer.parseInt(matcher.group(3))
					: 1;
			uniforms.add(new GlUniform(size > 1
					? matcher.group(2) + "[0]"
					: matcher.group(2), type(matcher.group(1)), size,
					location));
			location += size;
		}
		var program = new GlProgram(nextName++, uniforms);
		programs.put(program.getId(), program);
		return GlProgramBuildResult.success(program);
	}

	@Override
	void deleteProgram(@Nullable GlProgram program) {
		if (program != null && program.isValid()) {
			calls.add("deleteProgram");
			program.invalidate();
		}
	}

	@Override
	void useProgram(@Nullable GlProgram program) {
		this.program = program != null ? program.getId() : 0;
	}

	@Override
	void uniform1i(int location, int value) {
		write(location, value);
	}

	@Override
	void uniform1f(int location, float value) {
		write(location, value);
	}

	@Override
	void uniform2fv(int location, int count, @NonNull float[] values) {
		write(location, values[0], values[1]);
	}

	@Override
	void uniform3fv(int location, int count, @NonNull float[] values) {
		write(location, values[0], values[1], values[2]);
	}

	@Override
	void uniform4fv(int location, int count, @NonNull float[] values) {
		write(location, values[0], values[1], values[2], values[3]);
	}

	@Override
	void uniformMatrix2fv(int location, @NonNull float[] values) {
		write(location, values.clone());
	}

	@Override
	void uniformMatrix3fv(int location, @NonNull float[] values) {
		write(location, values.clone());
	}

	// The texture the bound framebuffer draws into, 0 for the surface.
	int target() {
		return framebuffer != null && framebuffer.getColorAttachment() != null
				? framebuffer.getColorAttachment().getId()
				: 0;
	}

	// The draws of one program.
	@NonNull
	List<Draw> drawsOf(int program) {
		var of = new ArrayList<Draw>();
		for (var draw : draws) {
			if (draw.program() == program) {
				of.add(draw);
			}
		}
		return of;
	}

	private void write(int location, float... value) {
		var uniforms = programs.get(program);
		if (uniforms == null) {
			return;
		}
		for (var uniform : uniforms.getUniforms().values()) {
			if (uniform.location() == location) {
				var written = values.get(program);
				if (written == null) {
					written = new HashMap<>();
					values.put(program, written);
				}
				written.put(uniform.name(), value);
				return;
			}
		}
	}

	// Makes the occurrence-th call of that name from now on fail.
	void fail(@NonNull String call, int occurrence) {
		failAt = call;
		failAfter = occurrence - 1;
	}

	private int name(@NonNull String call) {
		return fails(call) ? 0 : nextName++;
	}

	private int liveName(@NonNull String call) {
		int name = name(call);
		if (name != 0) {
			live.add(name);
		}
		return name;
	}

	private boolean fails(@NonNull String call) {
		if (!call.equals(failAt)) {
			return false;
		} else if (failAfter > 0) {
			--failAfter;
			return false;
		}
		failAt = null;
		return true;
	}

	private static int type(@NonNull String type) {
		return switch (type) {
			case "int" -> GLES20.GL_INT;
			case "vec2" -> GLES20.GL_FLOAT_VEC2;
			case "vec3" -> GLES20.GL_FLOAT_VEC3;
			case "vec4" -> GLES20.GL_FLOAT_VEC4;
			case "mat2" -> GLES20.GL_FLOAT_MAT2;
			case "mat3" -> GLES20.GL_FLOAT_MAT3;
			case "sampler2D" -> GLES20.GL_SAMPLER_2D;
			case "samplerCube" -> GLES20.GL_SAMPLER_CUBE;
			case "samplerExternalOES" -> GLES11Ext.GL_SAMPLER_EXTERNAL_OES;
			default -> GLES20.GL_FLOAT;
		};
	}
}
