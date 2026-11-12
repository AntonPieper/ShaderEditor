package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Hands the shaders hosts set on any thread to the GL thread. Every
// request is a revision, numbered in the order requested, even while its
// shader is still being made; a revision that a newer request supersedes
// is not built and never replaces the current build. The newest one
// builds as next while the current build keeps drawing, and replaces it
// once it is complete.
final class ShaderSwap<S, B extends ShaderSwap.Build> {
	interface Build {
		// Whether everything it draws with is uploaded.
		boolean isComplete();

		// Deletes its GL objects and stops its texture loads.
		void release();

		// Stops its texture loads; its GL objects went with the context.
		void discard();
	}

	interface Change<S> {
		@NonNull
		S apply(@NonNull S shader);
	}

	// Whether the targets of a build were made, are invalid for its
	// plan, or could not be allocated.
	enum Allocation {
		MADE,
		INVALID,
		FAILED
	}

	interface Targets<B> {
		// Makes the targets of build beside those of other builds.
		@NonNull
		Allocation make(@NonNull B build);

		// Releases the targets of build; false when it held none.
		boolean release(@NonNull B build);
	}

	record Revision<S>(@NonNull S shader, int number) {
	}

	// Guarded by this.
	// The number of the newest request.
	private int requested;
	// The newest request that will not draw: it failed or lost its
	// targets.
	private int ended;
	// What changes the shader of the newest request once it is offered.
	@Nullable
	private Change<S> change;
	@Nullable
	private Revision<S> pending;
	@Nullable
	private Revision<S> latest;
	// The newest revision taken.
	@Nullable
	private Revision<S> taken;
	// The revision that drew last before the context was lost, built
	// again when nothing newer can draw, until something draws or its
	// own build fails.
	@Nullable
	private Revision<S> fallback;
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

	// Any thread. Starts a revision whose shader is still being made and
	// returns its number. Revisions requested before it are superseded.
	synchronized int request() {
		change = null;
		return ++requested;
	}

	// Any thread. The shader of a request; false when a newer request
	// superseded it, which drops it.
	synchronized boolean offer(int number, @NonNull S shader) {
		if (number != requested) {
			return false;
		}
		if (change != null) {
			shader = change.apply(shader);
			change = null;
		}
		latest = new Revision<>(shader, number);
		pending = latest;
		return true;
	}

	// Any thread. Requests a revision with this shader.
	synchronized void offer(@NonNull S shader) {
		offer(request(), shader);
	}

	// Any thread. Offers a change of the shader requested last, if any;
	// one that is still being made changes once it is offered.
	synchronized void offerChanged(@NonNull Change<S> change) {
		if (latest != null && latest.number() == requested) {
			offer(change.apply(latest.shader()));
		} else if (requested > 0) {
			var first = this.change;
			this.change = first == null
					? change
					: shader -> change.apply(first.apply(shader));
		}
	}

	// Any thread, when the host goes away: the shader of a request that
	// is still being made is dropped once it is offered.
	synchronized void cancel() {
		change = null;
		if (requested > 0 && (latest == null || latest.number() != requested)) {
			++requested;
		}
	}

	// Any thread. The number of the newest request, or 0.
	synchronized int requested() {
		return requested;
	}

	// GL thread, at a frame boundary: the revision to build, or null.
	@Nullable
	synchronized Revision<S> take() {
		var revision = pending;
		pending = null;
		if (revision != null && superseded(revision)) {
			revision = null;
		}
		if (revision == null) {
			return current == null && next == null ? fallback : null;
		}
		if (taken == null || revision.number() > taken.number()) {
			taken = revision;
		}
		return revision;
	}

	// GL thread. This revision did not build.
	synchronized void failed(@NonNull Revision<S> revision) {
		if (is(fallback, revision.number())) {
			fallback = null;
		}
		end(revision.number());
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

	// GL thread. Makes the targets of the next build beside those of the
	// current build. When they cannot be allocated while the current
	// build holds targets, those go and they are made once more; the
	// current build has nothing to draw into then. Returns whether the
	// next build has its targets.
	boolean makeNextTargets(@NonNull Targets<B> targets) {
		if (next == null) {
			return false;
		}
		var allocation = targets.make(next);
		if (allocation == Allocation.FAILED &&
				current != null &&
				targets.release(current)) {
			allocation = targets.make(next);
		}
		return allocation == Allocation.MADE;
	}

	// GL thread. Swaps the next build in once it is complete and still
	// the newest request, and returns it, or returns null.
	@Nullable
	B swapIfComplete() {
		if (next() == null || !next.isComplete()) {
			return null;
		}
		if (current != null) {
			current.release();
		}
		current = next;
		currentRevision = nextRevision;
		next = null;
		nextRevision = null;
		synchronized (this) {
			fallback = null;
		}
		return current;
	}

	// GL thread. The next build can never complete: it is released, the
	// current build keeps drawing, and its revision is returned, or null
	// without one.
	@Nullable
	Revision<S> nextFailed() {
		var revision = nextRevision;
		if (revision != null) {
			failed(revision);
		}
		if (next != null) {
			next.release();
		}
		next = null;
		nextRevision = null;
		return revision;
	}

	// GL thread. The current build lost its targets for good: it is
	// released and nothing draws until a newer revision does.
	void currentFailed() {
		if (currentRevision != null) {
			synchronized (this) {
				end(currentRevision.number());
			}
		}
		if (current != null) {
			current.release();
		}
		current = null;
		currentRevision = null;
	}

	@Nullable
	B current() {
		return current;
	}

	@Nullable
	Revision<S> currentRevision() {
		return currentRevision;
	}

	// GL thread. The next build, or null; one that a newer request
	// superseded is released.
	@Nullable
	B next() {
		if (nextRevision != null && isSuperseded(nextRevision)) {
			next.release();
			next = null;
			nextRevision = null;
		}
		return next;
	}

	// GL thread. Whether to report the outcome of this revision. An older
	// build that replaces the picture after a newer revision failed, or
	// one that a newer request superseded, does not take that newer
	// outcome off the screen.
	synchronized boolean report(@NonNull Revision<S> revision) {
		if (revision.number() < reported || superseded(revision)) {
			return false;
		}
		reported = revision.number();
		return true;
	}

	// GL thread. Whether this revision may still draw: it is the newest
	// request and has not failed, or it draws, or it is built again
	// after the context was lost.
	synchronized boolean canDraw(int number) {
		return (number == requested && number != ended) ||
				is(currentRevision, number) ||
				is(fallback, number);
	}

	// After the context was lost. Its builds go with it, and the newest
	// revision taken is built again, unless a newer one waits. When that
	// cannot draw, the revision that drew last is built again.
	synchronized void contextLost() {
		if (currentRevision != null) {
			fallback = currentRevision;
		}
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

	private synchronized boolean isSuperseded(@NonNull Revision<S> revision) {
		return superseded(revision);
	}

	// Guarded by this. The revision that drew last stays the one to
	// build again until something newer draws.
	private boolean superseded(@NonNull Revision<S> revision) {
		return revision.number() < requested &&
				!is(fallback, revision.number());
	}

	// Guarded by this.
	private void end(int number) {
		if (number == requested) {
			ended = number;
		}
	}

	private static boolean is(@Nullable Revision<?> revision, int number) {
		return revision != null && revision.number() == number;
	}
}
