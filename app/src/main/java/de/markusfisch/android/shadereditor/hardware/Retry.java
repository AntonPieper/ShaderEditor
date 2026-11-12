package de.markusfisch.android.shadereditor.hardware;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * Tries again on the main thread until an attempt succeeds or it stops.
 * No callback reaches a listener when a permission is granted, in the
 * dialog, in the settings or with adb, and a device may be busy for a
 * while, so a listener that cannot start yet checks again.
 */
public final class Retry {
	public interface Attempt {
		// Returns true when no other attempt is needed.
		boolean run();
	}

	interface Scheduler {
		void postDelayed(@NonNull Runnable runnable, long delayMs);

		void remove(@NonNull Runnable runnable);
	}

	static final long INTERVAL_MS = 500;

	@NonNull
	private final Scheduler scheduler;
	@NonNull
	private final Attempt attempt;
	private final Runnable poll = this::poll;
	private volatile boolean stopped;

	public Retry(@NonNull Attempt attempt) {
		this(mainThread(), attempt);
	}

	Retry(@NonNull Scheduler scheduler, @NonNull Attempt attempt) {
		this.scheduler = scheduler;
		this.attempt = attempt;
	}

	// Any thread. Tries again after a while, until an attempt succeeds.
	public void later() {
		if (!stopped) {
			scheduler.postDelayed(poll, INTERVAL_MS);
		}
	}

	// Any thread. Schedules no other attempt; one that runs already
	// still ends.
	public void stop() {
		stopped = true;
		scheduler.remove(poll);
	}

	private void poll() {
		if (!stopped && !attempt.run()) {
			later();
		}
	}

	@NonNull
	private static Scheduler mainThread() {
		var handler = new Handler(Looper.getMainLooper());
		return new Scheduler() {
			@Override
			public void postDelayed(@NonNull Runnable runnable, long delayMs) {
				handler.postDelayed(runnable, delayMs);
			}

			@Override
			public void remove(@NonNull Runnable runnable) {
				handler.removeCallbacks(runnable);
			}
		};
	}
}
