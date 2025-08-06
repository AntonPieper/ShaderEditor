package de.markusfisch.android.shadereditor.engine.renderer;

import android.opengl.GLES32;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.Renderer;
import de.markusfisch.android.shadereditor.engine.model.Material;
import de.markusfisch.android.shadereditor.engine.model.RenderPass;
import de.markusfisch.android.shadereditor.engine.model.Shader;

public class GlesRenderer implements Renderer {
	private record GlesProgram(int programId, Map<String, Integer> uniformCache) {
		public GlesProgram(int programId) {
			this(programId, new HashMap<>());
		}

		public int locate(@NonNull String name) {
			return uniformCache.computeIfAbsent(name, (n) ->
					GLES32.glGetUniformLocation(programId, n));
		}
	}

	private static final String TAG = "GlesRenderer";
	private int viewportWidth;
	private int viewportHeight;
	private final Map<Shader, GlesProgram> shaderCache = new HashMap<>();

	@Override
	public void onSurfaceCreated() {
		shaderCache.clear();
		GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
	}

	@Override
	public void onSurfaceChanged(int width, int height) {
		this.viewportWidth = width;
		this.viewportHeight = height;
		GLES32.glViewport(0, 0, width, height);
	}

	@Override
	public void render(@NonNull RenderPass renderPass) {
		var program = prepareMaterial(renderPass.material());

		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, renderPass.framebuffer().getFbo());
		GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT);
		GLES32.glUseProgram(program.programId());

		// This is a good place to set global uniforms like resolution
		int resolutionLocation =
				GLES32.glGetUniformLocation(program.programId(),
						"u_resolution");
		GLES32.glUniform2f(resolutionLocation, (float) viewportWidth, (float) viewportHeight);

		setUniforms(program, renderPass.material());
		GLES32.glBindVertexArray(renderPass.geometry().getVao());
		GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0,
				renderPass.geometry().getVertexCount());
		// Unbind for safety
		GLES32.glBindVertexArray(0);
		GLES32.glUseProgram(0);
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0);
	}

	@NonNull
	private GlesProgram prepareMaterial(@NonNull Material material) {
		return shaderCache.computeIfAbsent(material.shader(), (shader) -> {
			int vertexShader = compileShader(GLES32.GL_VERTEX_SHADER,
					shader.vertexShader());
			int fragmentShader = compileShader(GLES32.GL_FRAGMENT_SHADER,
					shader.fragmentShader());
			int programId = linkProgram(vertexShader, fragmentShader);
			return new GlesProgram(programId);
		});
	}

	private void setUniforms(@NonNull GlesProgram program, @NonNull Material material) {
		for (var entry : material.uniforms().entrySet()) {
			int location = program.locate(entry.getKey());
			if (location == -1) continue;

			var value = entry.getValue();
			GlesBinder.bind(location, value);
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
}