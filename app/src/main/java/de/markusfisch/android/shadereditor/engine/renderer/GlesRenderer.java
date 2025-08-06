package de.markusfisch.android.shadereditor.engine.renderer;

import android.opengl.GLES32;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;

import de.markusfisch.android.shadereditor.engine.Renderer;
import de.markusfisch.android.shadereditor.engine.model.Material;
import de.markusfisch.android.shadereditor.engine.model.RenderPass;

public class GlesRenderer implements Renderer {
	private static final String TAG = "GlesRenderer";
	private int viewportWidth;
	private int viewportHeight;

	@Override
	public void onSurfaceCreated() {
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
		prepareMaterial(renderPass.material());

		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, renderPass.framebuffer().getFbo());
		GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT);
		try {
			GLES32.glUseProgram(renderPass.material().getProgramId());

			// This is a good place to set global uniforms like resolution
			int resolutionLocation =
					GLES32.glGetUniformLocation(renderPass.material().getProgramId(),
							"u_resolution");
			GLES32.glUniform2f(resolutionLocation, (float) viewportWidth, (float) viewportHeight);

			setUniforms(renderPass.material());
			GLES32.glBindVertexArray(renderPass.geometry().getVao());
			GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0,
					renderPass.geometry().getVertexCount());
		} catch (Exception e) {
			Log.e(TAG, "Render Error", e);
		} finally {
			// Unbind for safety
			GLES32.glBindVertexArray(0);
			GLES32.glUseProgram(0);
			GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0);
		}
	}

	private void prepareMaterial(@NonNull Material material) {
		if (material.getProgramId() != -1) {
			// Already compiled
			return;
		}

		int vertexShader = compileShader(GLES32.GL_VERTEX_SHADER,
				material.getVertexShaderSource());
		int fragmentShader = compileShader(GLES32.GL_FRAGMENT_SHADER,
				material.getFragmentShaderSource());
		int programId = linkProgram(vertexShader, fragmentShader);
		material.setProgramId(programId);
	}

	private void setUniforms(@NonNull Material material) {
		int programId = material.getProgramId();
		for (Map.Entry<String, Object> entry : material.getUniforms().entrySet()) {
			int location = GLES32.glGetUniformLocation(programId, entry.getKey());
			if (location == -1) continue;

			Object value = entry.getValue();
			if (value instanceof Float) {
				GLES32.glUniform1f(location, (Float) value);
			} else if (value instanceof float[] floats) {
				switch (floats.length) {
					case 2 -> GLES32.glUniform2fv(location, 1, floats, 0);
					case 3 -> GLES32.glUniform3fv(location, 1, floats, 0);
					case 4 -> GLES32.glUniform4fv(location, 1, floats, 0);
				}
			} else if (value instanceof Integer) {
				GLES32.glUniform1i(location, (Integer) value);
			}
			// Add other types (matrices, etc.) as needed
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