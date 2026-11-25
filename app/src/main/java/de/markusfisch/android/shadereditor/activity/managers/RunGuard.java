package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps a file that the app ended on from running by itself again. A
 * file is pending from the moment what it runs is handed to the engine
 * until the first frame of it, or until it failed to build, which draws
 * nothing; an app that starts with a file pending ended in between, and
 * holds that file: it is shown, and runs once the user asks for it. A file that drew a frame since the app started
 * is not pending again, so that the app being ended later, as for an
 * update, is not taken for a crash.
 */
public final class RunGuard {
	/** Where the two files are kept between starts; "" for none. */
	public interface Store {
		@NonNull
		String pending();

		void setPending(@NonNull String file);

		@NonNull
		String held();

		void setHeld(@NonNull String file);
	}

	@NonNull
	private final Store store;
	@NonNull
	private String pending = "";
	@NonNull
	private String held;
	// The files that drew a frame since the app started.
	@NonNull
	private final Set<String> drew = new HashSet<>();

	public RunGuard(@NonNull Store store) {
		this.store = store;
		String left = store.pending();
		if (left.isEmpty()) {
			held = store.held();
		} else {
			held = left;
			store.setHeld(left);
			store.setPending("");
		}
	}

	/**
	 * Return whether the file at path, of the folder with the session
	 * key, is held from running by itself.
	 */
	public synchronized boolean holds(@NonNull String key,
			@NonNull String path) {
		return held.equals(file(key, path));
	}

	/** What the file runs is handed to the engine. */
	public synchronized void running(@NonNull String key,
			@NonNull String path) {
		String file = file(key, path);
		if (!file.equals(pending) && !drew.contains(file)) {
			pending = file;
			store.setPending(file);
		}
	}

	/** A frame was drawn of the file that is pending. */
	public synchronized void ran() {
		if (!pending.isEmpty()) {
			drew.add(pending);
			left();
		}
	}

	/**
	 * What the file that is pending runs failed to build: it draws
	 * nothing, so it cannot end the app. Pending again when it is run
	 * again, as it never drew.
	 */
	public void failed() {
		left();
	}

	/** The screen left the front: it draws nothing until it is back. */
	public synchronized void left() {
		if (!pending.isEmpty()) {
			pending = "";
			store.setPending("");
		}
	}

	/** The user asked to run the file that is held. */
	public synchronized void release() {
		if (!held.isEmpty()) {
			held = "";
			store.setHeld("");
		}
	}

	@NonNull
	private static String file(@NonNull String key, @NonNull String path) {
		return key + "\n" + path;
	}
}
