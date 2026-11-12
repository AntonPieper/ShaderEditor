package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.List;

// The steps of one frame of the running pass, in order. Its history
// starts again on the first frame after its targets were made, which is
// before a new plan, a restart or a new context replaces what is shown,
// and whenever the render size changed.
final class PassSchedule {
	enum Step {
		// Clears the previous frame to opaque black.
		CLEAR_HISTORY,
		// Draws the initial content over the previous frame.
		INITIAL,
		// Clears what the pass draws into to opaque black.
		CLEAR,
		DRAW,
		// Makes the frame just drawn the previous one.
		SWAP,
		// Scales the frame just drawn onto the surface.
		PRESENT
	}

	private static final List<Step> FRAME = List.of(
			Step.CLEAR, Step.DRAW, Step.PRESENT);
	private static final List<Step> HISTORY_FRAME = List.of(
			Step.CLEAR, Step.DRAW, Step.SWAP, Step.PRESENT);
	private static final List<Step> HISTORY_RESET = List.of(
			Step.CLEAR_HISTORY, Step.CLEAR, Step.DRAW, Step.SWAP,
			Step.PRESENT);
	private static final List<Step> INITIAL_RESET = List.of(
			Step.CLEAR_HISTORY, Step.INITIAL, Step.CLEAR, Step.DRAW,
			Step.SWAP, Step.PRESENT);

	private PassSchedule() {
	}

	// reset is whether the targets were made since the last frame,
	// initial whether the pass has an initial source, which has no effect
	// without history.
	@NonNull
	static List<Step> frame(boolean reset, boolean history, boolean initial) {
		if (!history) {
			return FRAME;
		} else if (!reset) {
			return HISTORY_FRAME;
		}
		return initial ? INITIAL_RESET : HISTORY_RESET;
	}
}
