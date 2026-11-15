package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

// What a frame draws while the host may pause the picture, step it, or
// have it drawn again, and the clock and histories it draws with. This
// table is the model; decide() picks the row, EngineInputs.capture()
// gives the clock of each Draw and PlanRuntime.draw() the histories.
//
// T, F, D, R: the time, frame number, timeDelta and runtime of the frame
// drawn last; Q: the frame rate measured last. A higher row wins.
//
// | Asked since the frame before       | Draw    | time  | frame | timeDelta | frameRate | runtime | histories read          |
// |------------------------------------|---------|-------|-------|-----------|-----------|---------|-------------------------|
// | playing: anything                  | FRAME   | clock | next  | measured  | counted   | counted | of the frame drawn last |
// | paused: Restart, a new build, or   | FIRST   | 0     | 0     | 1/60      | Q         | the new | as they start: first    |
// |   new targets (the clock restarted)|         |       |       |           |           | owner's | frames drawn again      |
// | paused: Step                       | STEP    | T + D | F + 1 | D         | Q         | R + D   | of the frame drawn last |
// | paused: a tuned value, a build that| AGAIN   | T     | F     | D         | Q         | R       | the ones frame F read:  |
// |   goes on from the picture, or one |         |       |       |           |           |         | F is drawn again in its |
// |   that has drawn nothing yet       |         |       |       |           |           |         | place                   |
// | paused: anything else (a pass      | PRESENT | -     | -     | -         | -         | -       | none: what F drew is    |
// |   shown, Fit, a thumbnail)         |         |       |       |           |           |         | presented               |
//
// Play after a pause goes on from the frame drawn last: the next frame
// is at T + D with timeDelta D, and the rate is counted from then on, so
// the stopped time is in neither. Playing, Restart, a new build and new
// targets start the clock again: the next FRAME is frame 0 with
// timeDelta 1/60 and the rate counted from it.
final class Playback {
	enum Draw {
		// A frame as the clock runs.
		FRAME,
		// Frame 0 at time 0 of a paused clock that started again.
		FIRST,
		// The next frame of a paused clock.
		STEP,
		// The frame drawn last again, in its place.
		AGAIN,
		// No pass drawn: what the frame drawn last drew, presented.
		PRESENT
	}

	enum Swap {
		// The build that draws did not change this frame.
		NONE,
		// A build began to draw with its clock started again.
		NEW,
		// A build began to draw that goes on from the build before.
		CONTINUED
	}

	private Playback() {
	}

	// restarted: the clock started again since the frame before, by
	// Restart, a new build or new targets. needsFrame: the build has drawn
	// nothing yet that could be presented.
	@NonNull
	static Draw decide(boolean paused, boolean step, boolean again,
			boolean restarted, boolean needsFrame, @NonNull Swap swap) {
		if (!paused) {
			return Draw.FRAME;
		} else if (restarted || swap == Swap.NEW) {
			return Draw.FIRST;
		} else if (step) {
			return Draw.STEP;
		} else if (again || swap == Swap.CONTINUED || needsFrame) {
			return Draw.AGAIN;
		}
		return Draw.PRESENT;
	}
}
