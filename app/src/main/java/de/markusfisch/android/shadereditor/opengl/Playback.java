package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

// What a frame does while the host may pause the picture, step it one
// frame at a time, or have it drawn again.
//
// Paused, the clock stands at the frame drawn last and nothing is drawn
// but what is asked for:
// - Step draws the next frame, one frame's time later, every pass
//   advancing together.
// - A build that begins to draw is drawn as its first frame, frame 0 at
//   time 0, as its clock started again; one that goes on from the
//   build before, as the builds of a tuning session do, keeps the clock
//   and draws the frame drawn last again.
// - A change that is not a frame, such as a tuned value set, draws the
//   frame drawn last again, from the same last frames: the frame number
//   and time stay, and the next step goes on from the frame drawn last.
// - Restart clears the histories and the clock; paused, its frame 0 is
//   drawn and the picture stays paused.
// - Anything else, such as showing another pass, only presents what
//   the frame drawn last drew.
final class Playback {
	enum Draw {
		// A frame as the clock runs.
		FRAME,
		// The next frame of a paused clock.
		STEP,
		// The frame drawn last again; the histories do not move on.
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

	// needsFrame: the build has drawn nothing to present since its
	// targets were made or a restart.
	@NonNull
	static Draw decide(boolean paused, boolean step, boolean again,
			boolean needsFrame, @NonNull Swap swap) {
		if (!paused) {
			return Draw.FRAME;
		} else if (step || swap == Swap.NEW) {
			return Draw.STEP;
		} else if (swap == Swap.CONTINUED || again) {
			return Draw.AGAIN;
		} else if (needsFrame) {
			return Draw.STEP;
		}
		return Draw.PRESENT;
	}
}
