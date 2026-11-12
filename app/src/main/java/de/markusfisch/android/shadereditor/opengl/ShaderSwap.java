package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Hands the shaders hosts set on any thread to the GL thread. Every offer
// is a revision, numbered in the order offered, and only the newest one
// waiting is built. Its build waits as next while the current build keeps
// drawing, and replaces it once it is complete.
final class ShaderSwap<S, B extends ShaderSwap.Build> {
	interface Build {
		// Whether everything it draws with is uploaded.
		boolean isComplete();

		// Whether what it decoded was made for a surface of this size.
		boolean fits(int width, int height);

		// Deletes its GL objects and stops its texture loads.
		void release();

		// Stops its texture loads; its GL objects went with the context.
		void discard();
	}

	interface Change<S> {
		@NonNull
		S apply(@NonNull S shader);
	}

	record Revision<S>(@NonNull S shader, int number) {
	}

	// Guarded by this.
	@Nullable
	private Revision<S> pending;
	@Nullable
	private Revision<S> latest;
	// The newest revision taken.
	@Nullable
	private Revision<S> taken;
	// GL thread only.
	@Nullable
	private Revision<S> nextRevision;
	@Nullable
	private Revision<S> currentRevision;
	@Nullable
	private B next;
	@Nullable
	private B current;
	private int reported;

	// Any thread. Replaces a revision that was not taken yet.
	synchronized void offer(@NonNull S shader) {
		latest = new Revision<>(shader, offered() + 1);
		pending = latest;
	}

	// Any thread. Offers a change of the shader offered last, if any.
	synchronized void offerChanged(@NonNull Change<S> change) {
		if (latest != null) {
			offer(change.apply(latest.shader()));
		}
	}

	// Any thread. The number of the revision offered last, or 0.
	synchronized int offered() {
		return latest != null ? latest.number() : 0;
	}

	// GL thread, at a frame boundary: the revision to build, or null.
	@Nullable
	synchronized Revision<S> take() {
		var revision = pending;
		pending = null;
		if (revision != null &&
				(taken == null || revision.number() > taken.number())) {
			taken = revision;
		}
		return revision;
	}

	// GL thread. The build of this revision supersedes a next build that
	// is still waiting.
	void built(@NonNull Revision<S> revision, @NonNull B build) {
		if (next != null) {
			next.release();
		}
		next = build;
		nextRevision = revision;
	}

	// GL thread. Swaps the next build in once it is complete and returns
	// it, or returns null.
	@Nullable
	B swapIfComplete() {
		if (next == null || !next.isComplete()) {
			return null;
		}
		if (current != null) {
			current.release();
		}
		current = next;
		currentRevision = nextRevision;
		next = null;
		nextRevision = null;
		return current;
	}

	// GL thread. The next build can never complete: it is released, the
	// current build keeps drawing, and its revision is returned, or null
	// without one.
	@Nullable
	Revision<S> nextFailed() {
		var revision = nextRevision;
		if (next != null) {
			next.release();
		}
		next = null;
		nextRevision = null;
		return revision;
	}

	@Nullable
	B current() {
		return current;
	}

	@Nullable
	Revision<S> currentRevision() {
		return currentRevision;
	}

	@Nullable
	B next() {
		return next;
	}

	// GL thread. Whether to report the outcome of this revision. An older
	// build that replaces the picture after a newer revision failed does
	// not take that failure off the screen.
	boolean report(@NonNull Revision<S> revision) {
		if (revision.number() < reported) {
			return false;
		}
		reported = revision.number();
		return true;
	}

	// GL thread. Whether this revision may still draw: it waits to be
	// built, waits for its textures, or draws.
	synchronized boolean canDraw(int number) {
		return is(pending, number) ||
				is(nextRevision, number) ||
				is(currentRevision, number);
	}

	// GL thread, after the surface changed. Returns the next build when
	// it decoded for another size, so it decodes again. Without one, the
	// current build is built again when it decoded for another size,
	// unless a newer revision waits.
	@Nullable
	B resized(int width, int height) {
		if (next != null) {
			return next.fits(width, height) ? null : next;
		}
		if (current != null && !current.fits(width, height)) {
			synchronized (this) {
				if (pending == null) {
					pending = currentRevision;
				}
			}
		}
		return null;
	}

	// After the context was lost. Its builds go with it, and the newest
	// revision taken is built again, unless a newer one waits.
	synchronized void contextLost() {
		if (next != null) {
			next.discard();
		}
		if (current != null) {
			current.discard();
		}
		next = null;
		current = null;
		nextRevision = null;
		currentRevision = null;
		if (pending == null) {
			pending = taken;
		}
	}

	private static boolean is(@Nullable Revision<?> revision, int number) {
		return revision != null && revision.number() == number;
	}
}
