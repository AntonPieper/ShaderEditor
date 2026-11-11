package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// The GL texture parameters that select a sampling.
final class GlSampling {
	private GlSampling() {
	}

	static int minFilter(@NonNull Sampling sampling) {
		boolean linear = sampling.min() == Filter.LINEAR;
		return switch (sampling.mipmap()) {
			case NONE -> linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
			case NEAREST -> linear
					? GLES20.GL_LINEAR_MIPMAP_NEAREST
					: GLES20.GL_NEAREST_MIPMAP_NEAREST;
			case LINEAR -> linear
					? GLES20.GL_LINEAR_MIPMAP_LINEAR
					: GLES20.GL_NEAREST_MIPMAP_LINEAR;
		};
	}

	static int magFilter(@NonNull Sampling sampling) {
		return sampling.mag() == Filter.LINEAR
				? GLES20.GL_LINEAR
				: GLES20.GL_NEAREST;
	}

	static int wrap(@NonNull Wrap wrap) {
		return switch (wrap) {
			case CLAMP -> GLES20.GL_CLAMP_TO_EDGE;
			case MIRROR -> GLES20.GL_MIRRORED_REPEAT;
			case REPEAT -> GLES20.GL_REPEAT;
		};
	}
}
