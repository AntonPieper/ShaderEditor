package de.markusfisch.android.shadereditor.opengl;

final class FrameInputs {
	static final int MAX_POINTERS = 10;

	final float[] pointers = new float[MAX_POINTERS * 3];
	float time;
	int frame;
	float fTimeMax;
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
}
