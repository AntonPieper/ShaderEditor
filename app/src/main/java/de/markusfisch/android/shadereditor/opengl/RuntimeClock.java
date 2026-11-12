package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Tracks rendered time without including pauses or depending on a GL context. */
final class RuntimeClock {
	@Nullable
	private Object owner;
	private long elapsedNanos;
	private long lastFrameNanos;
	private boolean hasLastFrame;

	synchronized long advance(long now) {
		if (hasLastFrame) {
			elapsedNanos += now - lastFrameNanos;
		}
		lastFrameNanos = now;
		hasLastFrame = true;
		return elapsedNanos;
	}

	synchronized void pause() {
		hasLastFrame = false;
	}

	// The time of this owner, or -1 while the clock counts another's.
	synchronized long getElapsedNanos(@NonNull Object owner) {
		return owner == this.owner ? elapsedNanos : -1;
	}

	// Counts the time of this owner from now on, starting at nanos.
	synchronized void start(@NonNull Object owner, long nanos) {
		this.owner = owner;
		elapsedNanos = Math.max(0, nanos);
		hasLastFrame = false;
	}
}
