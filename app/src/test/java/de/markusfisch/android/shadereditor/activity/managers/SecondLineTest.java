package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SecondLineTest {
	@Test
	public void aNoticeHidesTheFrameRateUntilItsTime() {
		SecondLine line = new SecondLine();
		line.setFrameRate("60 fps");
		line.showNotice("Shader saved", 2000);
		line.setFrameRate("59 fps");
		assertEquals("Shader saved", line.shown(1999));
		assertEquals("59 fps", line.shown(2000));
	}

	@Test
	public void aLineOfItsOwnMovesTheFrameRateToTheQuality() {
		SecondLine line = new SecondLine();
		line.setFrameRate("60 fps");
		assertNull(line.besideQuality());
		line.setOwn("aurora");
		assertEquals("aurora", line.shown(0));
		assertEquals("60 fps", line.besideQuality());
	}

	@Test
	public void aPassShownPutsTheFrameRateFirstInTheLine() {
		SecondLine line = new SecondLine();
		line.setFrameRate("60 fps");
		line.setOwn("Ink");
		line.setRateFirst(true);
		assertEquals("60 fps \u00b7 Ink", line.shown(0));
		assertNull(line.besideQuality());
	}

	@Test
	public void pausedStandsWhereTheFrameRateWould() {
		SecondLine line = new SecondLine();
		line.setOwn("Ink");
		line.setPaused("paused");
		assertNull(line.besideQuality());
		line.setFrameRate("60 fps");
		assertEquals("paused", line.besideQuality());
		line.setRateFirst(true);
		assertEquals("paused \u00b7 Ink", line.shown(0));
	}
}
