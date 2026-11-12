package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.PassSchedule.Step.CLEAR;
import static de.markusfisch.android.shadereditor.opengl.PassSchedule.Step.CLEAR_HISTORY;
import static de.markusfisch.android.shadereditor.opengl.PassSchedule.Step.DRAW;
import static de.markusfisch.android.shadereditor.opengl.PassSchedule.Step.PRESENT;
import static de.markusfisch.android.shadereditor.opengl.PassSchedule.Step.SWAP;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class PassScheduleTest {
	@Test
	public void theFirstFrameAfterTargetsWereMadeStartsTheHistoryFromBlack() {
		assertEquals(List.of(CLEAR_HISTORY, CLEAR, DRAW, SWAP, PRESENT),
				PassSchedule.frame(true, true));
	}

	@Test
	public void laterFramesClearDrawSwapAndPresent() {
		assertEquals(List.of(CLEAR, DRAW, SWAP, PRESENT),
				PassSchedule.frame(false, true));
	}

	@Test
	public void withoutHistoryThereIsNothingToClearOrSwap() {
		assertEquals(List.of(CLEAR, DRAW, PRESENT),
				PassSchedule.frame(true, false));
		assertEquals(List.of(CLEAR, DRAW, PRESENT),
				PassSchedule.frame(false, false));
	}
}
