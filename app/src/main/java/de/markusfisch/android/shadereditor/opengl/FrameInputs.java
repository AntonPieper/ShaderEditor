package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

final class FrameInputs {
	static final int MAX_POINTERS = 10;

	final float[] pointers = new float[MAX_POINTERS * 3];
	float time;
	float runtime;
	int frame;
	float fTimeMax;
	int surfaceWidth;
	int surfaceHeight;
	float width;
	float height;
	float touchX;
	float touchY;
	float touchStartX;
	float touchStartY;
	float mouseX;
	float mouseY;
	int pointerCount;
	float offsetX;
	float offsetY;
	float startRandom;

	// Takes a frame of the project render size as a pass of this size
	// sees it: its resolution, with pixel positions scaled to it.
	void scale(@NonNull FrameInputs project, int width, int height) {
		float sx = project.width > 0 ? width / project.width : 1;
		float sy = project.height > 0 ? height / project.height : 1;
		time = project.time;
		runtime = project.runtime;
		frame = project.frame;
		fTimeMax = project.fTimeMax;
		surfaceWidth = project.surfaceWidth;
		surfaceHeight = project.surfaceHeight;
		this.width = width;
		this.height = height;
		touchX = project.touchX * sx;
		touchY = project.touchY * sy;
		touchStartX = project.touchStartX * sx;
		touchStartY = project.touchStartY * sy;
		mouseX = project.mouseX;
		mouseY = project.mouseY;
		pointerCount = project.pointerCount;
		for (int i = 0, end = pointerCount * 3; i < end; i += 3) {
			pointers[i] = project.pointers[i] * sx;
			pointers[i + 1] = project.pointers[i + 1] * sy;
			pointers[i + 2] = project.pointers[i + 2];
		}
		offsetX = project.offsetX;
		offsetY = project.offsetY;
		startRandom = project.startRandom;
	}
}
