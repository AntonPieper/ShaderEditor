package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
		assertEquals(60, firstTold(report, play, 16_667_000L));
	}

	// A build swapped in just before a frame: the moment between them is
	// no frame at the display's rate.
	@Test
	public void theFrameAfterABuildOnlyMarksTheTime() {
		var report = playedAt60();
		report.start();
		assertEquals(30, firstTold(report, 1000 * MILLI, 33_333_000L));
	}

	// After a start nothing is told before 200 ms of frames are in, so
	// a new picture's slow first frames are not shown as its rate.
	@Test
	public void theFirstRateWaitsForItsWindow() {
		var report = new FrameRateReport();
		report.start();
		long t = 0;
		assertEquals(NOTHING, report.frame(t));
		// Warm-up: one frame of 90 ms, then 60 a second.
		t += 90 * MILLI;
		assertEquals(NOTHING, report.frame(t));
		int told = NOTHING;
		while (told == NOTHING) {
			t += 16_667_000L;
			told = report.frame(t);
			if (told == NOTHING) {
				assertTrue(t <= 200 * MILLI);
			}
		}
		assertTrue(t > 200 * MILLI);
		assertTrue("told " + told, told > 50);
	}

	// The host cleared its label for the new shader: the same rate is
	// told again, once its window is in.
	@Test
	public void aNewShaderAtTheSameRateIsToldAgain() {
		var report = playedAt60();
		report.start();
		assertEquals(60, firstTold(report, 1000 * MILLI, 16_667_000L));
	}

	// A launch or a rotation before the build draws: no label to clear.
	@Test
	public void nothingDrawnSinceAStartTellsNothing() {
		var report = playedAt60();
		report.start();
		assertEquals(NOTHING, report.none());
		assertEquals(60, firstTold(report, 1000 * MILLI, 16_667_000L));
		assertEquals(0, report.none());
	}

	@Test
	public void nothingDrawingIsZeroOnce() {
		var report = playedAt60();
		assertEquals(0, report.none());
		assertEquals(NOTHING, report.none());
	}

	// The first rate told after a start, frames every interval from
	// start on; it is never told before 200 ms after the first frame.
	private static int firstTold(FrameRateReport report, long start,
			long interval) {
		for (long t = start; t < start + 2000 * MILLI; t += interval) {
			int told = report.frame(t);
			if (told != NOTHING) {
				assertTrue(t > start + 200 * MILLI);
				return told;
			}
		}
		return NOTHING;
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
