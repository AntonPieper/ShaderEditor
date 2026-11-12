package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Hands the shaders hosts set on any thread to the GL thread. Only the
// newest one waiting is built. Its build waits as next while the current
// build keeps drawing, and replaces it once it is complete.
final class ShaderSwap<S, B extends ShaderSwap.Build> {
	interface Build {
		// Whether everything it draws with is uploaded.
		boolean isComplete();

		// Deletes its GL objects and stops its texture loads.
		void release();

		// Stops its texture loads; its GL objects went with the context.
		void discard();
	}

	// Guarded by this.
	@Nullable
	private S pending;
	@Nullable
	private S latest;
	@Nullable
	private S taken;
	// GL thread only.
	@Nullable
	private S nextShader;
	@Nullable
	private S currentShader;
	@Nullable
	private B next;
	@Nullable
	private B current;

	// Any thread. Replaces a shader that was not taken yet.
	synchronized void offer(@NonNull S shader) {
		pending = shader;
		latest = shader;
	}

	// Any thread. The shader offered last.
	@Nullable
	synchronized S latest() {
		return latest;
	}

	// GL thread, at a frame boundary: the newest shader to build, or null.
	@Nullable
	synchronized S take() {
		S shader = pending;
		pending = null;
		if (shader != null) {
			taken = shader;
		}
		return shader;
	}

	// GL thread. The build of the shader taken last supersedes a next
	// build that is still waiting. When a build fails, nothing calls
	// this, and the current build keeps drawing.
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
		next = null;
		nextShader = null;
		return current;
	}

	@Nullable
	B current() {
		return current;
	}

	@Nullable
	B next() {
		return next;
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
