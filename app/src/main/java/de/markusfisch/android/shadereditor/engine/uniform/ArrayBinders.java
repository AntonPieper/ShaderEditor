package de.markusfisch.android.shadereditor.engine.uniform;

import android.opengl.GLES20;


/**
 * Uniform Binders for Buffers.
 */
public class ArrayBinders {
	// region Float
	public static final UniformBinder<float[]> F1 = (location, value) ->
			GLES20.glUniform1fv(location, value.length, value, 0);
	public static final UniformBinder<float[]> F2 = (location, value) ->
			GLES20.glUniform2fv(location, value.length / 2, value, 0);
	public static final UniformBinder<float[]> F3 = (location, value) ->
			GLES20.glUniform3fv(location, value.length / 3, value, 0);
	public static final UniformBinder<float[]> F4 = (location, value) ->
			GLES20.glUniform4fv(location, value.length / 4, value, 0);
	public static final UniformBinder<float[]> F2X2 = (location, value) ->
			GLES20.glUniformMatrix2fv(location, value.length / 4, false, value, 0);
	public static final UniformBinder<float[]> F3X3 = (location, value) ->
			GLES20.glUniformMatrix3fv(location, value.length / 9, false, value, 0);
	public static final UniformBinder<float[]> F4X4 = (location, value) ->
			GLES20.glUniformMatrix4fv(location, value.length / 16, false, value, 0);
	// region Integer
	public static final UniformBinder<int[]> I1 = (location, value) ->
			GLES20.glUniform1iv(location, value.length, value, 0);
	// endregion Float
	public static final UniformBinder<int[]> I2 = (location, value) ->
			GLES20.glUniform2iv(location, value.length / 2, value, 0);
	public static final UniformBinder<int[]> I3 = (location, value) ->
			GLES20.glUniform3iv(location, value.length / 3, value, 0);
	public static final UniformBinder<int[]> I4 = (location, value) ->
			GLES20.glUniform4iv(location, value.length / 4, value, 0);
	// endregion Integer

	private ArrayBinders() {
	}
}
