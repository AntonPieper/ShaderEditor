package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES32;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.engine.Renderer;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.scene.Material;
import de.markusfisch.android.shadereditor.engine.scene.RenderPass;

public class GlesRenderer implements Renderer, ShaderIntrospector, AutoCloseable {
	private static final String TAG = "GlesRenderer";
	private final Map<ShaderAsset, GlesProgram> shaderCache = new HashMap<>();
	private final GlesGpuObjectManager gpuObjectManager = new GlesGpuObjectManager();

	@Override
	public void onSurfaceCreated() {
		shaderCache.clear();
		gpuObjectManager.destroy();
		GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
	}

	@Override
	public void onSurfaceChanged(int width, int height) {
		GLES32.glViewport(0, 0, width, height);
	}

	@Override
	public void render(@NonNull RenderPass renderPass) {
		var binder = new GlesBinder(gpuObjectManager);
		var program = prepareShader(renderPass.material().shader());
		var fboHandle = gpuObjectManager.getFramebufferHandle(renderPass.framebuffer());
		var vaoHandle = gpuObjectManager.getGeometryHandle(renderPass.geometry());


		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboHandle);
		GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT);
		GLES32.glUseProgram(program.programId());

		setUniforms(program, renderPass.material(), binder);
		GLES32.glBindVertexArray(vaoHandle);
		GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0,
				renderPass.geometry().vertexCount());
		GlesUtil.checkErrors("render");

		// Unbind for safety
		GLES32.glBindVertexArray(0);
		GLES32.glUseProgram(0);
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0);
	}


	@Override
	public void close() {
		shaderCache.clear();
	}

	@NonNull
	@Override
	public ShaderMetadata introspect(@NonNull ShaderAsset asset) {
		return prepareShader(asset);
	}

	@NonNull
	private GlesProgram prepareShader(@NonNull ShaderAsset shader) {
		return shaderCache.computeIfAbsent(shader, (s) -> {
			int vertexShader = compileShader(GLES32.GL_VERTEX_SHADER,
					s.vertexSource());
			if (vertexShader == 0) return GlesProgram.INVALID;
			int fragmentShader = compileShader(GLES32.GL_FRAGMENT_SHADER,
					s.fragmentSource());
			if (fragmentShader == 0) return GlesProgram.INVALID;
			int programId = linkProgram(vertexShader, fragmentShader);
			var activeUniforms = introspectActiveUniforms(programId);
			return new GlesProgram(
					programId,
					Collections.unmodifiableMap(activeUniforms));
		});
	}

	@NonNull
	private Map<String, UniformInfo> introspectActiveUniforms(int programId) {
		Map<String, UniformInfo> activeUniforms = new HashMap<>();
		int[] count = new int[1];
		GLES32.glGetProgramiv(programId, GLES32.GL_ACTIVE_UNIFORMS, count, 0);

		if (count[0] == 0) {
			return Collections.emptyMap();
		}

		int[] length = new int[1];
		int[] size = new int[1];
		int[] type = new int[1];
		byte[] nameBytes = getNameBuffer(programId);

		for (int i = 0; i < count[0]; i++) {
			GLES32.glGetActiveUniform(programId, i, nameBytes.length, length, 0, size, 0, type, 0,
					nameBytes, 0);
			String name = normalizeArrayName(length[0], nameBytes);
			activeUniforms.put(name,
					new UniformInfo(GLES32.glGetUniformLocation(programId, name)));
		}

		return activeUniforms;
	}

	private void setUniforms(
			@NonNull GlesProgram program,
			@NonNull Material material,
			@NonNull GlesBinder binder) {
		for (var entry : material.uniforms().entrySet()) {
			int location = program.locate(entry.getKey());
			if (location == -1) continue;

			var value = entry.getValue();
			binder.bind(location, value);
		}
	}

	private int compileShader(int type, String source) {
		int shader = GLES32.glCreateShader(type);
		GLES32.glShaderSource(shader, source);
		GLES32.glCompileShader(shader);

		int[] compileStatus = new int[1];
		GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, compileStatus, 0);
		if (compileStatus[0] == 0) {
			Log.e(TAG, "Shader Compile Error: " + GLES32.glGetShaderInfoLog(shader));
			GLES32.glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	private int linkProgram(int vertexShader, int fragmentShader) {
		int program = GLES32.glCreateProgram();
		GLES32.glAttachShader(program, vertexShader);
		GLES32.glAttachShader(program, fragmentShader);
		GLES32.glLinkProgram(program);

		int[] linkStatus = new int[1];
		GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, linkStatus, 0);
		if (linkStatus[0] == 0) {
			Log.e(TAG, "Program Link Error: " + GLES32.glGetProgramInfoLog(program));
			GLES32.glDeleteProgram(program);
			return 0;
		}
		return program;
	}

	@NonNull
	private static String normalizeArrayName(int length, byte[] nameBytes) {
		// For uniform arrays, the name might be "myArray[0]". We want "myArray".
		for (int j = 0; j < length; j++) {
			if (nameBytes[j] == '[') {
				return new String(nameBytes, 0, j);
			}
		}
		return new String(nameBytes, 0, length);
	}

	@NonNull
	private static byte[] getNameBuffer(int programId) {
		int[] nameLength = new int[1];
		GLES32.glGetProgramiv(programId, GLES32.GL_ACTIVE_UNIFORM_MAX_LENGTH, nameLength, 0);
		return new byte[nameLength[0]];
	}

	private record UniformInfo(int location) {
	}

	private record GlesProgram(
			int programId,
			@NonNull Map<String, UniformInfo> activeUniforms)
			implements ShaderIntrospector.ShaderMetadata {
		public static final GlesProgram INVALID = new GlesProgram(0, Collections.emptyMap());

		/**
		 * Returns the location of the uniform with the given name.
		 * If the uniform is not found, -1 is returned.
		 *
		 * @param name The name of the uniform.
		 * @return The location of the uniform, or -1 if not found.
		 */
		public int locate(@NonNull String name) {
			var info = activeUniforms().get(name);
			if (info == null) {
				return -1;
			}
			return info.location();
		}

		@NonNull
		@Override
		public Set<String> getActiveUniformNames() {
			return activeUniforms().keySet();
		}
	}
}