package de.markusfisch.android.shadereditor.hardware;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RetryTest {
	private static final class Scheduler implements Retry.Scheduler {
		Runnable scheduled;
		long delay;

		@Override
		public void postDelayed(@NonNull Runnable runnable, long delayMs) {
			scheduled = runnable;
			delay = delayMs;
		}

		@Override
		public void remove(@NonNull Runnable runnable) {
			if (scheduled == runnable) {
				scheduled = null;
			}
		}

		void runScheduled() {
			var runnable = scheduled;
			scheduled = null;
			runnable.run();
		}
	}

	private final Scheduler scheduler = new Scheduler();
	private final List<String> attempts = new ArrayList<>();

	// A microphone without its permission: the permission arrives later,
	// then the device is busy once, then it records.
	@Test
	public void triesAgainUntilAnAttemptSucceeds() {
		var outcomes = new ArrayList<>(List.of(false, false, true));
		var retry = new Retry(scheduler, () -> {
			attempts.add("attempt");
			return outcomes.remove(0);
		});
		retry.later();
		assertEquals(Retry.INTERVAL_MS, scheduler.delay);
		scheduler.runScheduled();
		scheduler.runScheduled();
		scheduler.runScheduled();
		assertNull(scheduler.scheduled);
		assertEquals(3, attempts.size());
	}

	@Test
	public void stopRemovesTheNextAttempt() {
		var retry = new Retry(scheduler, () -> {
			attempts.add("attempt");
			return false;
		});
		retry.later();
		retry.stop();
		assertNull(scheduler.scheduled);
		retry.later();
		assertNull(scheduler.scheduled);
		assertEquals(List.of(), attempts);
	}

	@Test
	public void attemptAlreadyDueAfterStopDoesNotRun() {
		var retry = new Retry(scheduler, () -> {
			attempts.add("attempt");
			return false;
		});
		retry.later();
		var due = scheduler.scheduled;
		retry.stop();
		due.run();
		assertEquals(List.of(), attempts);
		assertNull(scheduler.scheduled);
	}

	@Test
	public void stopDuringAnAttemptSchedulesNoOther() {
		var retry = new Retry[1];
		retry[0] = new Retry(scheduler, () -> {
			retry[0].stop();
			return false;
		});
		retry[0].later();
		scheduler.runScheduled();
		assertNull(scheduler.scheduled);
	}
}
