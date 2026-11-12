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

	private PassSchedule() {
	}

	// reset is whether the targets were made since the last frame.
	@NonNull
	static List<Step> frame(boolean reset, boolean history) {
		if (!history) {
			return FRAME;
		}
		return reset ? HISTORY_RESET : HISTORY_FRAME;
	}
}
