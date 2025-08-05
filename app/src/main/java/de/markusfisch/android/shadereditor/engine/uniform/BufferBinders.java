package de.markusfisch.android.shadereditor.engine.uniform;

import android.opengl.GLES20;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;


/**
 * Uniform Binders for Buffers.
 */
public class BufferBinders {
	// region Float
	public static final UniformBinder<FloatBuffer> F1 = (location, value) ->
			GLES20.glUniform1fv(location, value.remaining(), value);
	public static final UniformBinder<FloatBuffer> F2 = (location, value) ->
			GLES20.glUniform2fv(location, value.remaining() / 2, value);
	public static final UniformBinder<FloatBuffer> F3 = (location, value) ->
			GLES20.glUniform3fv(location, value.remaining() / 3, value);
	public static final UniformBinder<FloatBuffer> F4 = (location, value) ->
			GLES20.glUniform4fv(location, value.remaining() / 4, value);
	public static final UniformBinder<FloatBuffer> F2X2 = (location, value) ->
			GLES20.glUniformMatrix2fv(location, value.remaining() / 4, false, value);
	public static final UniformBinder<FloatBuffer> F3X3 = (location, value) ->
			GLES20.glUniformMatrix3fv(location, value.remaining() / 9, false, value);
	public static final UniformBinder<FloatBuffer> F4X4 = (location, value) ->
			GLES20.glUniformMatrix4fv(location, value.remaining() / 16, false, value);
	// region Integer
	public static final UniformBinder<IntBuffer> I1 = (location, value) ->
			GLES20.glUniform1iv(location, value.remaining(), value);
	// endregion Float
	public static final UniformBinder<IntBuffer> I2 = (location, value) ->
			GLES20.glUniform2iv(location, value.remaining() / 2, value);
	public static final UniformBinder<IntBuffer> I3 = (location, value) ->
			GLES20.glUniform3iv(location, value.remaining() / 3, value);
	public static final UniformBinder<IntBuffer> I4 = (location, value) ->
			GLES20.glUniform4iv(location, value.remaining() / 4, value);
	// endregion Integer

	private BufferBinders() {
	}
}
