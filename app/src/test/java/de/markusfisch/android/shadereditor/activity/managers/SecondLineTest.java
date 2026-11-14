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
}
