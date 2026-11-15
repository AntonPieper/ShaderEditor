package de.markusfisch.android.shadereditor.fragment;

import androidx.annotation.NonNull;

/**
 * What the list of errors can show: what the build of what is open
 * reports is not known yet, it reported errors, or it reported none.
 * Only a report of none closes an open list; one that does not know yet
 * waits, as after a recreate until the build of the new screen reports.
 */
public final class ListedErrors {
	public enum State {
		UNKNOWN,
		ERRORS,
		NONE
	}

	@NonNull
	private State state = State.UNKNOWN;

	@NonNull
	public State state() {
		return state;
	}

	/** A build reported; any is whether it found anything. */
	public void reported(boolean any) {
		state = any ? State.ERRORS : State.NONE;
	}

	/** A run starts, or nothing runs: what comes is not known yet. */
	public void unknown() {
		state = State.UNKNOWN;
	}

	/**
	 * A file is shown with what its last build found. Without anything
	 * it waits for the build that follows, and when none follows, there
	 * is nothing to wait for.
	 */
	public void kept(boolean any, boolean reportFollows) {
		state = any
				? State.ERRORS
				: reportFollows ? State.UNKNOWN : State.NONE;
	}
}
