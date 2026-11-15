package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// What the engine tells of its frame rate follows Playback's table: a
// measured mean while playing, Q while paused, and nothing that drifts
// while paused or counts the stopped time after Play.
public class FrameRateReportTest {
	private static final long MILLI = 1000000L;
	private static final int NOTHING = FrameRateReport.NOTHING;

	@Test
	public void playingItTellsTheMeasuredRate() {
		var report = new FrameRateReport();
		report.start();
		int told = NOTHING;
		for (int i = 1; i <= 30; ++i) {
			int fps = report.frame(i * 16_667_000L);
			if (fps != NOTHING) {
				told = fps;
			}
		}
		assertEquals(60, told);
	}

	// Paused, it tells Q once and nothing else: Steps and redraws do not
	// change it; a restart back to the first rate does.
	@Test
	public void pausedItTellsQAndHoldsIt() {
		var report = playedAt60();
		assertEquals(57, report.paused(57.4f));
		for (int step = 0; step < 10; ++step) {
			assertEquals(NOTHING, report.paused(57.4f));
		}
		assertEquals(60, report.paused(EngineInputs.FIRST_FRAME_RATE));
	}

	// After Play, the first rate is one of frames, not of the 30 s the
	// picture stood.
	@Test
	public void afterPlayTheStoppedTimeIsInNoSample() {
		var report = playedAt60();
		report.paused(57f);
		long play = 30_000 * MILLI;
		report.start();
		assertEquals(NOTHING, report.frame(play));
		assertEquals(60, report.frame(play + 16_667_000L));
	}

	// A build swapped in just before a frame: the moment between them is
	// no frame at the display's rate.
	@Test
	public void theFrameAfterABuildOnlyMarksTheTime() {
		var report = playedAt60();
		report.start();
		long frame = 1000 * MILLI;
		assertEquals(NOTHING, report.frame(frame));
		assertEquals(30, report.frame(frame + 33_333_000L));
	}

	// The host cleared its label for the new shader: the same rate is
	// told again.
	@Test
	public void aNewShaderAtTheSameRateIsToldAgain() {
		var report = playedAt60();
		report.start();
		long frame = 1000 * MILLI;
		report.frame(frame);
		assertEquals(60, report.frame(frame + 16_667_000L));
	}

	@Test
	public void nothingDrawingIsZeroOnce() {
		var report = playedAt60();
		assertEquals(0, report.none());
		assertEquals(NOTHING, report.none());
	}

	private static FrameRateReport playedAt60() {
		var report = new FrameRateReport();
		report.start();
		for (int i = 1; i <= 30; ++i) {
			report.frame(i * 16_667_000L);
		}
		return report;
	}
}
