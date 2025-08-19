package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.scene.TextureSource;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesSwapchainManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesTextureManager;


/**
 * A utility class for binding uniform values to OpenGL ES 2.0 shaders.
 * This class is stateless and all dependencies are provided per-method.
 */
public final class GlesBinder {
	private final GlesTextureManager textureManager;
	private final GlesSwapchainManager swapchainManager;
	private int nextTextureUnit = 0;

	public GlesBinder(
			@NonNull GlesTextureManager textureManager,
			@NonNull GlesSwapchainManager swapchainManager) {
		this.textureManager = textureManager;
		this.swapchainManager = swapchainManager;
	}

	/**
	 * Binds a uniform value to a specified location in a shader program.
	 *
	 * @param location The location of the uniform variable in the shader program.
	 * @param uniform  The {@link Uniform} object containing the value to be bound.
	 * @param viewport The current rendering viewport, required for resolving swapchains.
	 */
	public void bind(int location, @NonNull Uniform uniform, @NonNull Viewport viewport) {
		switch (uniform) {
			case Uniform.FloatMat2(var value) ->
					GLES20.glUniformMatrix2fv(location, 1, false, value, 0);
			case Uniform.FloatMat3(var value) ->
					GLES20.glUniformMatrix3fv(location, 1, false, value, 0);
			case Uniform.FloatMat4(var value) ->
					GLES20.glUniformMatrix4fv(location, 1, false, value, 0);
			case Uniform.FloatScalar(var value) -> GLES20.glUniform1fv(location, 1, value, 0);
			case Uniform.FloatVec2(var value) -> GLES20.glUniform2fv(location, 1, value, 0);
			case Uniform.FloatVec3(var value) -> GLES20.glUniform3fv(location, 1, value, 0);
			case Uniform.FloatVec4(var value) -> GLES20.glUniform4fv(location, 1, value, 0);
			case Uniform.IntScalar(var value) -> GLES20.glUniform1iv(location, 1, value, 0);
			case Uniform.Sampler2D(var source) -> {
				int textureId = switch (source) {
					case TextureSource.FromImage(var image) ->
							textureManager.getTextureHandle(image);
					case TextureSource.FromSwapchain(var swapchain) ->
							swapchainManager.getReadTextureHandle(swapchain, viewport);
				};
				bindTexture(location, textureId);
			}
		}
	}

	public void bindTexture(int location, int textureId) {
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + nextTextureUnit);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
		GLES20.glUniform1i(location, nextTextureUnit);
		nextTextureUnit++;
	}


	public void resetTextureUnits() {
		nextTextureUnit = 0;
	}
}