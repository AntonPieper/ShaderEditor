package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;

public enum GlslType {
	INT("int", GLES20.GL_INT, GLES20.GL_BOOL),
	FLOAT("float", GLES20.GL_FLOAT, GLES20.GL_BOOL),
	VEC2("vec2", GLES20.GL_FLOAT_VEC2, GLES20.GL_BOOL_VEC2),
	VEC3("vec3", GLES20.GL_FLOAT_VEC3, GLES20.GL_BOOL_VEC3),
	VEC4("vec4", GLES20.GL_FLOAT_VEC4, GLES20.GL_BOOL_VEC4),
	MAT2("mat2", GLES20.GL_FLOAT_MAT2, GLES20.GL_FLOAT_MAT2),
	MAT3("mat3", GLES20.GL_FLOAT_MAT3, GLES20.GL_FLOAT_MAT3),
	SAMPLER_2D("sampler2D", GLES20.GL_SAMPLER_2D, GLES20.GL_SAMPLER_2D),
	SAMPLER_EXTERNAL_OES("samplerExternalOES",
			GLES11Ext.GL_SAMPLER_EXTERNAL_OES,
			GLES11Ext.GL_SAMPLER_EXTERNAL_OES);

	@NonNull
	private final String keyword;
	private final int glType;
	// glUniform1i and glUniform*f also load a bool declaration of the same size.
	private final int boolType;

	GlslType(@NonNull String keyword, int glType, int boolType) {
		this.keyword = keyword;
		this.glType = glType;
		this.boolType = boolType;
	}

	@NonNull
	public String getKeyword() {
		return keyword;
	}

	boolean accepts(int glType) {
		return glType == this.glType || glType == boolType;
	}
}
