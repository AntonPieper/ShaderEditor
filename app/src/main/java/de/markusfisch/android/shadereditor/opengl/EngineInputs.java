package de.markusfisch.android.shadereditor.opengl;

import android.view.MotionEvent;

import androidx.annotation.NonNull;

// Clock, pointers, wallpaper offset and surface size. Other threads post
// values; the GL thread captures them into FrameInputs once per frame.
final class EngineInputs {
	record Touch(int action, @NonNull float[] pointers, int count) {
		@NonNull
		static Touch of(@NonNull MotionEvent e) {
			int count = Math.min(e.getPointerCount(), FrameInputs.MAX_POINTERS);
			float[] pointers = new float[count * 3];
			for (int i = 0, p = 0; i < count; ++i) {
				pointers[p++] = e.getX(i);
				pointers[p++] = e.getY(i);
				pointers[p++] = e.getTouchMajor(i);
			}
			return new Touch(e.getActionMasked(), pointers, count);
		}
	}

	private static final float NS_PER_SECOND = 1000000000f;

	private final RuntimeClock runtimeClock = new RuntimeClock();
	private final FrameInputs frameInputs = new FrameInputs();
	private final Object lock = new Object();
	// Guarded by lock.
	private final float[] pointers = new float[FrameInputs.MAX_POINTERS * 3];
	private int pointerCount;
	private float touchX;
	private float touchY;
	private float touchStartX;
	private float touchStartY;
	private float mouseX;
	private float mouseY;
	private float offsetX;
	private float offsetY;
	private float quality = 1f;
	private int renderWidth;
	private int renderHeight;
	// GL thread only.
	private int surfaceWidth;
	private int surfaceHeight;
	private long startTime;
	private int frame;
	private float startRandom;

	static int renderSize(int surfaceSize, float quality) {
		return Math.round(surfaceSize * quality);
	}

	// Any thread. The runtime of this owner while it is shown, or -1.
	long getRuntimeNanos(@NonNull Object owner) {
		return runtimeClock.getElapsedNanos(owner);
	}

	// GL thread, when a shader is shown. The runtime counts for this
	// owner and starts at nanos, unless it counts for this owner already.
	void showRuntime(@NonNull Object owner, long nanos) {
		if (runtimeClock.getElapsedNanos(owner) < 0) {
			runtimeClock.start(owner, nanos);
		}
	}

	void pauseRuntime() {
		runtimeClock.pause();
	}

	void setQuality(float quality) {
		synchronized (lock) {
			this.quality = quality;
		}
	}

	void post(@NonNull MotionEvent e) {
		post(Touch.of(e));
	}

	void post(@NonNull Touch touch) {
		synchronized (lock) {
			float[] p = touch.pointers();
			if (touch.count() > 0) {
				touchX = p[0] * quality;
				touchY = renderHeight - p[1] * quality;
				mouseX = touchX / renderWidth;
				mouseY = 1 - p[1] * quality / renderHeight;
			}
			switch (touch.action()) {
				case MotionEvent.ACTION_DOWN:
					touchStartX = touchX;
					touchStartY = touchY;
					break;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					pointerCount = 0;
					return;
				default:
					break;
			}
			pointerCount = touch.count();
			for (int i = 0, end = pointerCount * 3; i < end; i += 3) {
				pointers[i] = p[i] * quality;
				pointers[i + 1] = renderHeight - p[i + 1] * quality;
				pointers[i + 2] = p[i + 2];
			}
		}
	}

	void postOffset(float x, float y) {
		synchronized (lock) {
			offsetX = x;
			offsetY = y;
		}
	}

	// GL thread. Restarts the clock and returns true when the render size changed.
	boolean resize(int width, int height, long now) {
		runtimeClock.pause();
		startTime = now;
		startRandom = (float) Math.random();
		frame = 0;
		surfaceWidth = width;
		surfaceHeight = height;
		synchronized (lock) {
			int w = renderSize(width, quality);
			int h = renderSize(height, quality);
			boolean changed = w != renderWidth || h != renderHeight;
			renderWidth = w;
			renderHeight = h;
			return changed;
		}
	}

	// GL thread. Restarts the clock for a new shader of this quality.
	void restart(float quality, long now) {
		setQuality(quality);
		resize(surfaceWidth, surfaceHeight, now);
	}

	int getSurfaceWidth() {
		return surfaceWidth;
	}

	int getSurfaceHeight() {
		return surfaceHeight;
	}

	int getRenderWidth() {
		return renderWidth;
	}

	int getRenderHeight() {
		return renderHeight;
	}

	// GL thread, once at the start of a frame.
	@NonNull
	FrameInputs capture(long now, float fTimeMax) {
		var in = frameInputs;
		in.time = (now - startTime) / NS_PER_SECOND;
		in.runtime = (float) (runtimeClock.advance(now) / 1000000000.0);
		in.frame = frame++;
		in.fTimeMax = fTimeMax;
		in.startRandom = startRandom;
		in.surfaceWidth = surfaceWidth;
		in.surfaceHeight = surfaceHeight;
		synchronized (lock) {
			in.width = renderWidth;
			in.height = renderHeight;
			in.touchX = touchX;
			in.touchY = touchY;
			in.touchStartX = touchStartX;
			in.touchStartY = touchStartY;
			in.mouseX = mouseX;
			in.mouseY = mouseY;
			in.pointerCount = pointerCount;
			System.arraycopy(pointers, 0, in.pointers, 0, pointerCount * 3);
			in.offsetX = offsetX;
			in.offsetY = offsetY;
		}
		return in;
	}
}
