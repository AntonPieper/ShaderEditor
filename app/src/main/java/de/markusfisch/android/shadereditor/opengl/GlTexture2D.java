package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.project.RenderPlan;

final class GlTexture2D implements GlTexture {
	private int id;
	private int width;
	private int height;
	// What its parameters were set to sample with last, or null.
	@Nullable
	private RenderPlan.Sampling sampling;

	GlTexture2D(int id) {
		this.id = id;
	}

	@Override
	public int getId() {
		return id;
	}

	@Override
	public int getTarget() {
		return GLES20.GL_TEXTURE_2D;
	}

	@Override
	public int getWidth() {
		return width;
	}

	@Override
	public int getHeight() {
		return height;
	}

	@Override
	public boolean isValid() {
		return id != 0;
	}

	@Nullable
	RenderPlan.Sampling sampling() {
		return sampling;
	}

	void setSampling(@Nullable RenderPlan.Sampling sampling) {
		this.sampling = sampling;
	}

	void setSize(int width, int height) {
		this.width = width;
		this.height = height;
	}

	@Override
	public void invalidate() {
		id = 0;
		width = 0;
		height = 0;
		sampling = null;
	}
}
