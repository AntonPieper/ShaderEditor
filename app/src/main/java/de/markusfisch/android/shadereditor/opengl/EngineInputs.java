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

	// What a frame without one before it is said to have taken: the
	// first after a restart or a pause.
	static final float FIRST_TIME_DELTA = 1f / 60f;
	// The most a frame is said to have taken, so that frames that
	// stopped coming for a while are not one long step.
	static final float LONGEST_TIME_DELTA = .5f;
	// The rate before frames were counted for the first time.
	static final float FIRST_FRAME_RATE = 60f;

	private static final float NS_PER_SECOND = 1000000000f;
	// Frames are counted for longer than this, then the rate is told.
	private static final long RATE_WINDOW_NS = 500000000L;

	private final RuntimeClock runtimeClock = new RuntimeClock();
	private final FrameInputs frameInputs = new FrameInputs();
	private final Object lock = new Object();
	// Guarded by lock. Touches as the surface reports them, from its
	// top left; a frame takes them to the render size it is drawn at.
	private final float[] pointers = new float[FrameInputs.MAX_POINTERS * 3];
	private int pointerCount;
	private float touchX;
	private float touchY;
	private float touchStartX;
	private float touchStartY;
	// Until the first touch, and the first touch down, they read 0.
	private boolean touched;
	private boolean started;
	// Whether a touch began that no frame was told of yet.
	private boolean began;
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
	// When the frame before was captured; none after a restart or a
	// pause.
	private long lastCapture;
	private boolean hasLastCapture;
	// The frames counted since rateSince for the next rate; not
	// counting after a pause, until a frame comes.
	private float frameRate = FIRST_FRAME_RATE;
	private long rateSince;
	private int rateFrames;
	private boolean counting;
	// The clock of the frame drawn last, where a paused clock stands,
	// and whether the clock started again since: see Playback.
	private float lastTime;
	private int lastFrame;
	private float lastDelta = FIRST_TIME_DELTA;
	private float lastRuntime;
	private boolean paused;
	private boolean restarted;

	// At least one pixel, as a target has.
	static int renderSize(int surfaceSize, float quality) {
		return Math.max(1, Math.round(surfaceSize * quality));
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

	// When no frames will come for a while: the time until the next
	// one is no frame's duration, and is not counted into the rate.
	void pause() {
		runtimeClock.pause();
		hasLastCapture = false;
		counting = false;
	}

	void post(@NonNull MotionEvent e) {
		post(Touch.of(e));
	}

	void post(@NonNull Touch touch) {
		synchronized (lock) {
			float[] p = touch.pointers();
			if (touch.count() > 0) {
				touchX = p[0];
				touchY = p[1];
				touched = true;
			}
			switch (touch.action()) {
				case MotionEvent.ACTION_DOWN:
					touchStartX = touchX;
					touchStartY = touchY;
					started = touched;
					began = touched;
					break;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					pointerCount = 0;
					return;
				default:
					break;
			}
			pointerCount = touch.count();
			System.arraycopy(p, 0, pointers, 0, pointerCount * 3);
		}
	}

	void postOffset(float x, float y) {
		synchronized (lock) {
			offsetX = x;
			offsetY = y;
		}
	}

	// GL thread. Returns true when the render size changed; the clock
	// restarts only with restart().
	boolean resize(int width, int height) {
		surfaceWidth = width;
		surfaceHeight = height;
		synchronized (lock) {
			return setRenderSize();
		}
	}

	// GL thread. Restarts the clock and the frame number at the same
	// quality.
	void restart(long now) {
		float quality;
		synchronized (lock) {
			quality = this.quality;
		}
		restart(quality, now);
	}

	// GL thread. Restarts the clock for a new shader of this quality: the
	// next frame is frame 0, drawn as Playback.Draw.FIRST while paused.
	void restart(float quality, long now) {
		android.util.Log.d("TuningClock", "time restarts", new Throwable());
		runtimeClock.pause();
		startTime = now;
		startRandom = (float) Math.random();
		frame = 0;
		restarted = true;
		hasLastCapture = false;
		frameRate = FIRST_FRAME_RATE;
		rateSince = now;
		rateFrames = 0;
		// Paused, no frame comes until Play, which starts counting.
		counting = !paused;
		// Together, so a pointer is never scaled with one of the new
		// quality and the old size.
		synchronized (lock) {
			this.quality = quality;
			setRenderSize();
		}
	}

	// Guarded by lock.
	private boolean setRenderSize() {
		int w = renderSize(surfaceWidth, quality);
		int h = renderSize(surfaceHeight, quality);
		boolean changed = w != renderWidth || h != renderHeight;
		renderWidth = w;
		renderHeight = h;
		return changed;
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

	// The frames per second of the last time they were counted for
	// more than half a second, which this frame may end.
	private float countFrame(long now) {
		if (!counting) {
			rateSince = now;
			rateFrames = 0;
			counting = true;
			return frameRate;
		}
		++rateFrames;
		if (now - rateSince > RATE_WINDOW_NS) {
			frameRate = rateFrames * NS_PER_SECOND / (now - rateSince);
			rateSince = now;
			rateFrames = 0;
		}
		return frameRate;
	}

	// GL thread. Stops the clock at the frame drawn last, or lets it run
	// on from there: the first frame after is one frame's time later.
	void setPaused(boolean paused, long now) {
		if (this.paused == paused) {
			return;
		}
		this.paused = paused;
		if (paused) {
			runtimeClock.pause();
			counting = false;
			return;
		}
		long delta = (long) (lastDelta * NS_PER_SECOND);
		startTime = now - (long) (lastTime * NS_PER_SECOND) - delta;
		lastCapture = now - delta;
		hasLastCapture = true;
		// The rate is counted from the first frame on, not over the time
		// it stood.
		counting = false;
	}

	// GL thread. The number of the frame drawn last.
	int lastFrame() {
		return lastFrame;
	}

	// GL thread. Q in Playback's table: the rate measured last, which
	// the frameRate uniform has while paused.
	float frameRate() {
		return frameRate;
	}

	// GL thread. Whether the clock started again since the frame drawn
	// last.
	boolean restarted() {
		return restarted;
	}

	// GL thread, once at the start of a frame: the frame as it runs.
	@NonNull
	FrameInputs capture(long now, float fTimeMax) {
		return capture(Playback.Draw.FRAME, now, fTimeMax);
	}

	// GL thread, once at the start of a frame: the clock of draw, as the
	// table in Playback says; the frame becomes the frame drawn last.
	@NonNull
	FrameInputs capture(@NonNull Playback.Draw draw, long now,
			float fTimeMax) {
		var in = frameInputs;
		switch (draw) {
			case FIRST -> {
				in.time = 0;
				in.frame = frame++;
				in.timeDelta = FIRST_TIME_DELTA;
				in.frameRate = frameRate;
				in.runtime = seconds(runtimeClock.elapsed());
			}
			case STEP -> {
				in.time = lastTime + lastDelta;
				in.frame = frame++;
				in.timeDelta = lastDelta;
				in.frameRate = frameRate;
				in.runtime = seconds(runtimeClock.add(
						(long) (lastDelta * NS_PER_SECOND)));
			}
			case AGAIN -> {
				in.time = lastTime;
				in.frame = lastFrame;
				in.timeDelta = lastDelta;
				in.frameRate = frameRate;
				in.runtime = lastRuntime;
			}
			default -> {
				in.time = (now - startTime) / NS_PER_SECOND;
				in.runtime = seconds(runtimeClock.advance(now));
				in.frame = frame++;
				in.timeDelta = hasLastCapture
						? Math.min((now - lastCapture) / NS_PER_SECOND,
						LONGEST_TIME_DELTA)
						: FIRST_TIME_DELTA;
				lastCapture = now;
				hasLastCapture = true;
				in.frameRate = countFrame(now);
			}
		}
		restarted = false;
		lastTime = in.time;
		lastFrame = in.frame;
		lastDelta = in.timeDelta;
		lastRuntime = in.runtime;
		return fill(in, fTimeMax);
	}

	private static float seconds(long nanos) {
		return (float) (nanos / 1000000000.0);
	}

	// What every kind of frame takes as it is now.
	@NonNull
	private FrameInputs fill(@NonNull FrameInputs in, float fTimeMax) {
		in.fTimeMax = fTimeMax;
		in.startRandom = startRandom;
		in.surfaceWidth = surfaceWidth;
		in.surfaceHeight = surfaceHeight;
		synchronized (lock) {
			in.width = renderWidth;
			in.height = renderHeight;
			// From the bottom left, in render pixels, as the engine has
			// always given them.
			in.touchX = touched ? touchX * quality : 0;
			in.touchY = touched ? renderHeight - touchY * quality : 0;
			in.touchStartX = started ? touchStartX * quality : 0;
			in.touchStartY = started
					? renderHeight - touchStartY * quality
					: 0;
			in.touchBegan = began;
			began = false;
			in.mouseX = touched ? in.touchX / renderWidth : 0;
			in.mouseY = touched ? 1 - touchY * quality / renderHeight : 0;
			in.pointerCount = pointerCount;
			for (int i = 0, end = pointerCount * 3; i < end; i += 3) {
				in.pointers[i] = pointers[i] * quality;
				in.pointers[i + 1] = renderHeight - pointers[i + 1] * quality;
				in.pointers[i + 2] = pointers[i + 2];
			}
			in.offsetX = offsetX;
			in.offsetY = offsetY;
		}
		return in;
	}
}
