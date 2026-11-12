package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Hands the shaders hosts set on any thread to the GL thread. Only the
// newest one waiting is built. Its build waits as next while the current
// build keeps drawing, and replaces it once it is complete. Shaders are
// numbered in the order they were offered.
final class ShaderSwap<S, B extends ShaderSwap.Build> {
	interface Build {
		// Whether everything it draws with is uploaded.
		boolean isComplete();

		// Deletes its GL objects and stops its texture loads.
		void release();

		// Stops its texture loads; its GL objects went with the context.
		void discard();
	}

	private record Offer<S>(@NonNull S shader, int number) {
	}

	// Guarded by this.
	@Nullable
	private Offer<S> pending;
	@Nullable
	private Offer<S> taken;
	@Nullable
	private S latest;
	private int offered;
	// GL thread only.
	@Nullable
	private Offer<S> nextShader;
	@Nullable
	private Offer<S> currentShader;
	@Nullable
	private B next;
	@Nullable
	private B current;
	private int shown;

	// Any thread. Replaces a shader that was not taken yet.
	synchronized void offer(@NonNull S shader) {
		pending = new Offer<>(shader, ++offered);
		latest = shader;
	}

	// Any thread. The number of the shader offered last.
	synchronized int offered() {
		return offered;
	}

	// Any thread. The shader offered last.
	@Nullable
	synchronized S latest() {
		return latest;
	}

	// GL thread, at a frame boundary: the newest shader to build, or null.
	@Nullable
	synchronized S take() {
		if (pending == null) {
			return null;
		}
		taken = pending;
		pending = null;
		return taken.shader();
	}

	// GL thread. The shader taken last did not build; the current build
	// keeps drawing and shows its outcome.
	synchronized void failed() {
		if (taken != null) {
			shown = Math.max(shown, taken.number());
		}
	}

	// GL thread. The build of the shader taken last supersedes a next
	// build that is still waiting.
	synchronized void built(@NonNull B build) {
		if (next != null) {
			next.release();
		}
		next = build;
		nextShader = taken;
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
		currentShader = nextShader;
		if (currentShader != null) {
			shown = Math.max(shown, currentShader.number());
		}
		next = null;
		nextShader = null;
		return current;
	}

	// GL thread. The next build can never complete: it is released, and
	// the current build keeps drawing and shows its outcome.
	synchronized void nextFailed() {
		if (next == null) {
			return;
		}
		next.release();
		if (nextShader != null) {
			shown = Math.max(shown, nextShader.number());
		}
		next = null;
		nextShader = null;
	}

	@Nullable
	B current() {
		return current;
	}

	@Nullable
	B next() {
		return next;
	}

	// GL thread. The number of the newest shader whose outcome is on
	// screen: it draws, or it failed and an older one draws.
	int shown() {
		return shown;
	}

	// GL thread. Builds the shader of the current build again, unless a
	// newer one waits.
	synchronized void rebuildCurrent() {
		if (pending == null) {
			pending = currentShader;
		}
	}

	// After the context was lost. Its builds go with it, and the newest
	// shader taken is built again, unless a newer one waits.
	synchronized void contextLost() {
		if (next != null) {
			next.discard();
		}
		if (current != null) {
			current.discard();
		}
		next = null;
		current = null;
		nextShader = null;
		currentShader = null;
		if (pending == null) {
			pending = taken;
		}
	}
}
