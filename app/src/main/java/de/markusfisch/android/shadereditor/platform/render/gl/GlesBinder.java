package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.scene.Uniform;


/**
 * A utility class for binding uniform values to OpenGL ES 2.0 shaders.
 *
 * <p>This class provides a static {@link #bind(int, Uniform)} method that
 * uses a switch statement to determine the appropriate GLES20.glUniform*
 * function to call based on the type of the {@link Uniform} provided.
 *
 * <p>It is declared as {@code final} with a private constructor to prevent
 * instantiation, as it only contains static utility methods.
 */
public final class GlesBinder {
	@NonNull
	private final GlesGpuObjectManager gpuObjectManager;
	private int nextTextureUnit = 0;

	public GlesBinder(@NonNull GlesGpuObjectManager gpuObjectManager) {
		this.gpuObjectManager = gpuObjectManager;
	}

	/**
	 * Binds a uniform value to a specified location in a shader program.
	 *
	 * <p>This method uses a switch statement to determine the type of the uniform and calls the
	 * appropriate GLES20.glUniform* method to bind the value.
	 *
	 * @param location The location of the uniform variable in the shader program.
	 * @param uniform  The {@link Uniform} object containing the value to be bound.
	 *                 This object also indicates the type of the uniform.
	 */
	public void bind(int location, @NonNull Uniform uniform) {
		switch (uniform) {
			case Uniform.FloatMat2(float[] value) ->
					GLES20.glUniformMatrix2fv(location, value.length / (2 * 2), false, value, 0);
			case Uniform.FloatMat3(float[] value) ->
					GLES20.glUniformMatrix3fv(location, value.length / (3 * 3), false, value, 0);
			case Uniform.FloatMat4(float[] value) ->
					GLES20.glUniformMatrix4fv(location, value.length / (4 * 4), false, value, 0);
			case Uniform.FloatScalar(float[] value) ->
					GLES20.glUniform1fv(location, value.length, value, 0);
			case Uniform.FloatVec2(float[] value) ->
					GLES20.glUniform2fv(location, value.length / 2, value, 0);
			case Uniform.FloatVec3(float[] value) ->
					GLES20.glUniform3fv(location, value.length / 3, value, 0);
			case Uniform.FloatVec4(float[] value) ->
					GLES20.glUniform4fv(location, value.length / 4, value, 0);
			case Uniform.IntScalar(int[] value) ->
					GLES20.glUniform1iv(location, value.length, value, 0);
			case Uniform.Sampler sampler -> {
				int textureId = gpuObjectManager.getTextureHandle(sampler);
				GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + nextTextureUnit);
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
				GLES20.glUniform1i(location, nextTextureUnit);
				nextTextureUnit++;
			}
		}
	}
}