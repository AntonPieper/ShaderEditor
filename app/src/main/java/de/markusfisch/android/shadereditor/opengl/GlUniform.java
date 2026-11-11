package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.NonNull;

record GlUniform(@NonNull String name, int type, int size, int location, int unit) {
	static final int NO_UNIT = -1;

	GlUniform(@NonNull String name, int type, int size, int location) {
		this(name, type, size, location, NO_UNIT);
	}

	boolean isSampler() {
		return switch (type) {
			case GLES20.GL_SAMPLER_2D,
					GLES20.GL_SAMPLER_CUBE,
					GLES11Ext.GL_SAMPLER_EXTERNAL_OES,
					GLES30.GL_SAMPLER_3D,
					GLES30.GL_SAMPLER_2D_SHADOW,
					GLES30.GL_SAMPLER_2D_ARRAY,
					GLES30.GL_SAMPLER_2D_ARRAY_SHADOW,
					GLES30.GL_SAMPLER_CUBE_SHADOW,
					GLES30.GL_INT_SAMPLER_2D,
					GLES30.GL_INT_SAMPLER_3D,
					GLES30.GL_INT_SAMPLER_CUBE,
					GLES30.GL_INT_SAMPLER_2D_ARRAY,
					GLES30.GL_UNSIGNED_INT_SAMPLER_2D,
					GLES30.GL_UNSIGNED_INT_SAMPLER_3D,
					GLES30.GL_UNSIGNED_INT_SAMPLER_CUBE,
					GLES30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY -> true;
			default -> false;
		};
	}

	@NonNull
	GlUniform withUnit(int unit) {
		return new GlUniform(name, type, size, location, unit);
	}
}
