package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

// Who watches the passes. A call to a watcher and the test that it still
// watches are one step under one lock, so no call starts after set()
// replaced it, from any thread, also from inside one of its own calls.
// A call under way when it is replaced ends first: set() waits for it,
// so a watcher must not wait in a call for the thread that replaces it.
final class PassWatching {
	private final Object lock = new Object();
	@Nullable
	private ShaderEngine.PassWatcher watcher;

	// Any thread.
	void set(@Nullable ShaderEngine.PassWatcher watcher) {
		synchronized (lock) {
			this.watcher = watcher;
		}
	}

	@Nullable
	ShaderEngine.PassWatcher get() {
		synchronized (lock) {
			return watcher;
		}
	}

	// GL thread. Tells watcher the frame while it still watches; false
	// when it does not.
	boolean frame(@NonNull ShaderEngine.PassWatcher watcher, int frame) {
		synchronized (lock) {
			if (this.watcher != watcher) {
				return false;
			}
			watcher.onFrame(frame);
			return true;
		}
	}

	// GL thread. Tells watcher the pictures while it still watches;
	// false when it does not.
	boolean pictures(@NonNull ShaderEngine.PassWatcher watcher,
			@NonNull List<PassPicture> pictures) {
		synchronized (lock) {
			if (this.watcher != watcher) {
				return false;
			}
			watcher.onPictures(pictures);
			return true;
		}
	}

	// GL thread. Tells watcher the facts while it still watches; false
	// when it does not.
	boolean facts(@NonNull ShaderEngine.PassWatcher watcher,
			@NonNull List<PassFacts> facts) {
		synchronized (lock) {
			if (this.watcher != watcher) {
				return false;
			}
			watcher.onFacts(facts);
			return true;
		}
	}
}
