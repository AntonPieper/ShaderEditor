package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.NonNull;

// A sampler array of size n takes the n units from unit on.
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

	// The target an empty texture is bound to for this sampler, or 0 for
	// a target this engine never binds.
	int textureTarget() {
		return switch (type) {
			case GLES20.GL_SAMPLER_2D,
					GLES30.GL_SAMPLER_2D_SHADOW,
					GLES30.GL_INT_SAMPLER_2D,
					GLES30.GL_UNSIGNED_INT_SAMPLER_2D -> GLES20.GL_TEXTURE_2D;
			case GLES20.GL_SAMPLER_CUBE,
					GLES30.GL_SAMPLER_CUBE_SHADOW,
					GLES30.GL_INT_SAMPLER_CUBE,
					GLES30.GL_UNSIGNED_INT_SAMPLER_CUBE -> GLES20.GL_TEXTURE_CUBE_MAP;
			case GLES11Ext.GL_SAMPLER_EXTERNAL_OES -> GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
			default -> 0;
		};
	}

	@NonNull
	int[] units() {
		int[] units = new int[size];
		for (int i = 0; i < size; ++i) {
			units[i] = unit + i;
		}
		return units;
	}

	@NonNull
	GlUniform withUnit(int unit) {
		return new GlUniform(name, type, size, location, unit);
	}
}
