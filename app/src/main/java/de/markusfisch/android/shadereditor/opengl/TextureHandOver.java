package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Hands what a decoding thread produced to the GL thread. Only the newest
// load counts; whatever an older one delivers is recycled.
final class TextureHandOver<T extends TextureHandOver.Recyclable> {
	interface Recyclable {
		void recycle();
	}

	private int generation;
	@Nullable
	private T ready;

	// Supersedes every earlier load and returns the one to deliver.
	synchronized int begin() {
		cancel();
		return generation;
	}

	synchronized void cancel() {
		++generation;
		if (ready != null) {
			ready.recycle();
			ready = null;
		}
	}

	synchronized void deliver(int generation, @NonNull T result) {
		if (generation != this.generation || ready != null) {
			result.recycle();
			return;
		}
		ready = result;
	}

	@Nullable
	synchronized T take() {
		T result = ready;
		ready = null;
		return result;
	}
}
