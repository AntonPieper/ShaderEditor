package de.markusfisch.android.shadereditor.renderengine;

import android.opengl.GLES20;

/**
 * A utility class holding singleton instances of {@link UniformType} strategies.
 * This provides a central, clean way to access different uniform applicators.
 */
public final class UniformTypes {
	// This applicator works ONLY with Float.
	public static final UniformType<Float> FLOAT = (loc, val) -> () ->
			GLES20.glUniform1f(loc, val);
	// These applicators work ONLY with float[].
	public static final UniformType<float[]> VEC1 = (loc, val) -> () ->
			GLES20.glUniform1fv(loc, val.length, val, 0);
	public static final UniformType<float[]> VEC2 = (loc, val) -> () ->
			GLES20.glUniform3fv(loc, val.length / 2, val, 0);
	public static final UniformType<float[]> VEC3 = (loc, val) -> () ->
			GLES20.glUniform3fv(loc, val.length / 3, val, 0);
	public static final UniformType<float[]> VEC4 = (loc, val) -> () ->
			GLES20.glUniform4fv(loc, val.length / 4, val, 0);
	public static final UniformType<float[]> MAT3 = (loc, val) -> () ->
			GLES20.glUniformMatrix3fv(loc, val.length / 9, false, val, 0);
	public static final UniformType<float[]> MAT4 = (loc, val) -> () ->
			GLES20.glUniformMatrix4fv(loc, val.length / 16, false, val, 0);
	// This applicator works ONLY with Integer.
	public static final UniformType<Integer> INT = (loc, val) -> () ->
			GLES20.glUniform1i(loc, val);
	public static final UniformType<Integer> SAMPLER_2D = (loc, val) -> () ->
			GLES20.glUniform1i(loc, val);

	private UniformTypes() {
	} // Private constructor to prevent instantiation.
}