package de.markusfisch.android.shadereditor.navigation;

import java.util.List;

/**
 * What stands under the rows of a level that is being read or cannot
 * be: what is going on, the reason storage gave, or null, and the ways
 * out.
 */
public record Notice(Kind kind, String reason) {
	public enum Kind {
		/** Reading, just begun. */
		READING,
		/** Still reading, for long enough to offer to stop. */
		SLOW,
		/** Reading was stopped. */
		STOPPED,
		/** The folder holds nothing to show. */
		EMPTY,
		/** The folder could not be listed. */
		FAILED
	}

	public enum Action {
		STOP,
		TRY_AGAIN
	}

	public List<Action> actions() {
		return switch (kind) {
			case SLOW -> List.of(Action.STOP);
			case STOPPED, FAILED -> List.of(Action.TRY_AGAIN);
			default -> List.of();
		};
	}
}
