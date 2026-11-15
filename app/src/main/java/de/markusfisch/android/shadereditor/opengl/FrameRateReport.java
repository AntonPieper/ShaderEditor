package de.markusfisch.android.shadereditor.opengl;

// GL thread. What the engine tells its listener of the frame rate
// (Listener.onFramesPerSecond), as Playback's table has it:
// - playing: the mean rate of the frames drawn since measuring started,
//   at most the display's, told at most every 200 ms when it changed;
// - paused: Q, the rate the frameRate uniform has, told when the picture
//   pauses and when a paused draw changes it (a restart back to the
//   first rate); Step, a frame drawn again and a frame presented tell
//   nothing else, so the number shown can be trusted;
// - a new build, new targets and Play measure again from the first frame
//   after them, so the stopped time, or the time a build took, is in no
//   sample;
// - nothing drawing: 0.
// Each call returns the rate to tell, or NOTHING.
final class FrameRateReport {
	static final int NOTHING = -1;

	private static final float NS_PER_SECOND = 1000000000f;
	private static final long TELL_EVERY_NS = 200000000L;

	private float refreshRate = 60f;
	private float sum;
	private float samples;
	private long next;
	private long last;
	private boolean marked;
	private int told;

	void setRefreshRate(float refreshRate) {
		this.refreshRate = refreshRate;
	}

	// Measuring starts again: the next frame only marks the time the
	// one after it is measured from.
	void start() {
		sum = 0;
		samples = 0;
		next = 0;
		marked = false;
	}

	// A frame drawn while playing.
	int frame(long now) {
		long delta = marked ? now - last : 0;
		marked = true;
		last = now;
		if (delta > 0) {
			sum += Math.min(NS_PER_SECOND / delta, refreshRate);
			if (++samples > 0xffff) {
				sum = sum / samples;
				samples = 1;
			}
		}
		if (now <= next || samples == 0) {
			return NOTHING;
		}
		next = now + TELL_EVERY_NS;
		return tell(Math.round(sum / samples));
	}

	// Any frame while paused: rate is Q.
	int paused(float rate) {
		return tell(Math.round(rate));
	}

	// Nothing draws.
	int none() {
		return tell(0);
	}

	private int tell(int fps) {
		if (fps == told) {
			return NOTHING;
		}
		told = fps;
		return fps;
	}
}
