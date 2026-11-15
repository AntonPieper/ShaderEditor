package de.markusfisch.android.shadereditor.activity.managers;

import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Reads images for the drawer, one at a time, on threads of its own:
 * never on the queue every project saves and keeps its unsaved text
 * on, so a slow provider holds up no save. A read can be cancelled
 * before it starts, while it runs (a provider call through its
 * {@link CancellationSignal}), and by a time limit: one that takes
 * longer is let go and answered as failed for now, and the next one
 * starts. While two reads let go still run, nothing more starts, so a
 * provider that hangs costs at most two threads. Call it on the main
 * thread; answers come there.
 */
final class ImageReader {
	/** How long a read may take, in milliseconds. */
	static final long LIMIT_MILLIS = 5000;
	// How many reads let go may still run before nothing more starts.
	private static final int LET_GO = 2;

	/** Whether a read is still wanted, and how to stop a provider call. */
	static final class Cancel {
		final CancellationSignal signal = new CancellationSignal();
		private volatile boolean cancelled;

		void cancel() {
			cancelled = true;
			signal.cancel();
		}

		boolean cancelled() {
			return cancelled;
		}
	}

	/** A read, off the main thread. */
	interface Task<T> {
		@Nullable
		T read(@NonNull Cancel cancel) throws Exception;
	}

	/**
	 * What a read found, or null; again says it failed for now (time,
	 * storage) rather than found nothing, and may be tried again.
	 */
	interface Done<T> {
		void done(@Nullable T found, boolean again);
	}

	private static final class Job<T> {
		final Object key;
		final Task<T> task;
		final Done<T> done;
		final Cancel cancel = new Cancel();
		boolean letGo;

		Job(@NonNull Object key, @NonNull Task<T> task,
				@NonNull Done<T> done) {
			this.key = key;
			this.task = task;
			this.done = done;
		}
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService threads = Executors.newCachedThreadPool();
	// By key, the one asked for first first.
	private final Map<Object, Job<?>> queue = new LinkedHashMap<>();
	@Nullable
	private Job<?> running;
	private int lettingGo;
	private boolean destroyed;

	/**
	 * Read with task, in place of a read of the same key not started
	 * yet; done hears what it found unless it was cancelled first.
	 */
	@NonNull
	<T> Cancel read(@NonNull Object key, @NonNull Task<T> task,
			@NonNull Done<T> done) {
		Job<T> job = new Job<>(key, task, done);
		if (destroyed) {
			job.cancel.cancel();
			return job.cancel;
		}
		queue.remove(key);
		queue.put(key, job);
		next();
		return job.cancel;
	}

	/** The read of key is no longer wanted, started or not. */
	void cancel(@NonNull Object key) {
		Job<?> queued = queue.remove(key);
		if (queued != null) {
			queued.cancel.cancel();
		}
		if (running != null && running.key.equals(key)) {
			letGo(running);
		}
	}

	/** No read is wanted anymore. */
	void cancelAll() {
		for (Job<?> job : queue.values()) {
			job.cancel.cancel();
		}
		queue.clear();
		if (running != null) {
			letGo(running);
		}
	}

	void destroy() {
		destroyed = true;
		cancelAll();
		main.removeCallbacksAndMessages(null);
		threads.shutdownNow();
	}

	/** Whether a read of key is asked for and not answered yet. */
	boolean asked(@NonNull Object key) {
		return queue.containsKey(key) ||
				(running != null && running.key.equals(key));
	}

	private void next() {
		if (destroyed || running != null || lettingGo >= LET_GO ||
				queue.isEmpty()) {
			return;
		}
		Iterator<Job<?>> first = queue.values().iterator();
		Job<?> job = first.next();
		first.remove();
		start(job);
	}

	private <T> void start(@NonNull Job<T> job) {
		running = job;
		main.postDelayed(() -> {
			if (running == job) {
				letGo(job);
				job.done.done(null, true);
			}
		}, LIMIT_MILLIS);
		try {
			threads.execute(() -> {
				T found = null;
				boolean again = false;
				try {
					found = job.cancel.cancelled()
							? null
							: job.task.read(job.cancel);
				} catch (OutOfMemoryError e) {
					// Too large to read: nothing to try again.
				} catch (Exception e) {
					again = true;
				}
				T value = found;
				boolean retry = again;
				main.post(() -> finished(job, value, retry));
			});
		} catch (RejectedExecutionException e) {
			running = null;
		}
	}

	private <T> void finished(@NonNull Job<T> job, @Nullable T found,
			boolean again) {
		if (job.letGo) {
			--lettingGo;
		} else if (running == job) {
			running = null;
			if (!job.cancel.cancelled() && !destroyed) {
				job.done.done(found, again);
			}
		}
		next();
	}

	// The running read is not waited for: it may still run, and what it
	// finds is dropped.
	private void letGo(@NonNull Job<?> job) {
		job.cancel.cancel();
		job.letGo = true;
		++lettingGo;
		if (running == job) {
			running = null;
		}
		next();
	}
}
